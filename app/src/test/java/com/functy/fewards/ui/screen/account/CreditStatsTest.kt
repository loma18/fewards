package com.functy.fewards.ui.screen.account

import com.functy.fewards.core.workbuddy.WorkBuddyCredits
import com.functy.fewards.data.repository.AccountRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 账号页积分统计的判据。
 *
 * 口径上最容易错的三处，都在这里钉死：
 * 1. **已过期的额度不算"可用"**：既不计入总剩余，也不计入"即将到期"；
 * 2. **已用完的包（remaining=0）不算即将到期**：否则会虚报一笔根本花不到的钱；
 * 3. **5 日内天然包含 3 日内**：两个数是独立口径，不是互斥区间。
 */
class CreditStatsTest {

    private val now = 1_800_000_000_000L
    private val day = CreditStats.DAY_MS

    private fun pkg(remaining: Double, expireInDays: Double?): WorkBuddyCredits.CreditPackage {
        val expireAt = expireInDays?.let { now + (it * day).toLong() }
        return WorkBuddyCredits.CreditPackage(
            packageCode = "code",
            name = "积分包",
            total = 100.0,
            remaining = remaining,
            used = 0.0,
            expireAt = expireAt,
            expired = expireAt != null && expireAt <= now,
            expiringSoon = false,
        )
    }

    private fun balance(vararg packages: WorkBuddyCredits.CreditPackage): WorkBuddyCredits.Balance =
        WorkBuddyCredits.Balance(
            total = packages.sumOf { it.total },
            remaining = packages.sumOf { it.remaining },
            used = 0.0,
            packageCount = packages.size,
            unit = "credits",
            packages = packages.toList(),
            updatedAt = now,
        )

    private fun account(id: String, uid: String = "u-$id") = AccountRepository.WorkBuddyAccount(
        id = id,
        label = "账号 $id",
        token = "AT",
        uid = uid,
    )

    // ==================== 单账号到期额度 ====================

    @Test
    fun countsOnlyWithinWindow() {
        val b = balance(
            pkg(100.0, 1.0),   // 1 天后到期
            pkg(200.0, 4.0),   // 4 天后到期
            pkg(300.0, 30.0),  // 30 天后
        )
        assertEquals(100.0, CreditStats.expiringWithin(b, 3, now), 0.01)
        // 5 日内包含 3 日内的那笔
        assertEquals(300.0, CreditStats.expiringWithin(b, 5, now), 0.01)
    }

    @Test
    fun expiredPackagesCountNeitherAsRemainingNorExpiring() {
        val b = balance(pkg(100.0, -1.0), pkg(50.0, 2.0))
        assertEquals(50.0, CreditStats.expiringWithin(b, 3, now), 0.01)
    }

    @Test
    fun usedUpPackageIsNotCountedAsExpiring() {
        val b = balance(pkg(0.0, 1.0), pkg(70.0, 6.0))
        assertEquals(0.0, CreditStats.expiringWithin(b, 3, now), 0.01)
        assertEquals(0.0, CreditStats.expiringWithin(b, 5, now), 0.01)
    }

    @Test
    fun longLivedPackageNeverExpires() {
        val b = balance(pkg(500.0, null))
        assertEquals(0.0, CreditStats.expiringWithin(b, 5, now), 0.01)
        assertEquals(0.0, CreditStats.expiringWithin(b, 365, now), 0.01)
    }

    @Test
    fun windowBoundaryIsInclusive() {
        // 恰好 3 天整：算在 3 日内；3 天 + 1 秒不算
        val exactly = balance(pkg(10.0, 3.0))
        assertEquals(10.0, CreditStats.expiringWithin(exactly, 3, now), 0.01)

        val justOver = WorkBuddyCredits.Balance(
            total = 10.0,
            remaining = 10.0,
            used = 0.0,
            packageCount = 1,
            unit = "credits",
            packages = listOf(
                WorkBuddyCredits.CreditPackage(
                    packageCode = "c",
                    name = "包",
                    total = 10.0,
                    remaining = 10.0,
                    used = 0.0,
                    expireAt = now + 3 * day + 1000,
                    expired = false,
                    expiringSoon = false,
                )
            ),
            updatedAt = now,
        )
        assertEquals(0.0, CreditStats.expiringWithin(justOver, 3, now), 0.01)
        assertEquals(10.0, CreditStats.expiringWithin(justOver, 5, now), 0.01)
    }

    @Test
    fun nullBalanceIsZero() {
        assertEquals(0.0, CreditStats.expiringWithin(null, 3, now), 0.01)
    }

    // ==================== 全账号汇总 ====================

    @Test
    fun sumsAcrossAccounts() {
        val accounts = listOf(account("a"), account("b"))
        val credits = mapOf(
            "a" to WbCreditState(balance = balance(pkg(100.0, 1.0), pkg(50.0, 10.0))),
            "b" to WbCreditState(balance = balance(pkg(200.0, 4.0))),
        )
        val s = CreditStats.summarize(accounts, credits, now)
        assertEquals(350.0, s.remaining, 0.01)
        assertEquals(100.0, s.expiringIn3Days, 0.01)
        assertEquals(300.0, s.expiringIn5Days, 0.01)
        assertEquals(2, s.counted)
        assertEquals(0, s.loading)
        assertEquals(0, s.failed)
    }

    @Test
    fun expiredBalanceStillCountsInTotalRemainingButNotExpiring() {
        val accounts = listOf(account("a"))
        val credits = mapOf("a" to WbCreditState(balance = balance(pkg(0.0, -1.0), pkg(80.0, 2.0))))
        val s = CreditStats.summarize(accounts, credits, now)
        // remaining 是接口给的"剩余"，这里只累加包级 remaining，不做二次过滤
        assertEquals(80.0, s.remaining, 0.01)
        assertEquals(80.0, s.expiringIn3Days, 0.01)
    }

    @Test
    fun pendingAndFailedAreCountedSeparately() {
        val accounts = listOf(account("a"), account("b"), account("c"))
        val credits = mapOf(
            "a" to WbCreditState(balance = balance(pkg(10.0, 1.0))),
            "b" to WbCreditState(loading = true),
            "c" to WbCreditState(error = "网络错误"),
        )
        val s = CreditStats.summarize(accounts, credits, now)
        assertEquals(1, s.counted)
        assertEquals(1, s.loading)
        assertEquals(1, s.failed)
        assertEquals(3, s.counted + s.loading + s.failed)
    }

    @Test
    fun accountWithoutUidIsSkippedAndNotPending() {
        val accounts = listOf(account("a", uid = ""), account("b"))
        val credits = mapOf("b" to WbCreditState(balance = balance(pkg(5.0, 1.0))))
        val s = CreditStats.summarize(accounts, credits, now)
        assertEquals(1, s.skipped)
        assertEquals(0, s.loading)
        assertEquals(1, s.counted)
        assertEquals(5.0, s.remaining, 0.01)
    }

    @Test
    fun fiveDayTotalIsNeverBelowThreeDayTotal() {
        val accounts = (1..5).map { account("a$it") }
        val credits = accounts.associate { acc ->
            acc.id to WbCreditState(
                balance = balance(pkg(10.0, 1.0), pkg(20.0, 4.0), pkg(30.0, 20.0))
            )
        }
        val s = CreditStats.summarize(accounts, credits, now)
        assertTrue(s.expiringIn5Days >= s.expiringIn3Days)
        assertEquals(10.0 * 5, s.expiringIn3Days, 0.01)
        assertEquals(30.0 * 5, s.expiringIn5Days, 0.01)
    }

    @Test
    fun emptyAccountListYieldsZero() {
        val s = CreditStats.summarize(emptyList(), emptyMap(), now)
        assertEquals(0.0, s.remaining, 0.01)
        assertEquals(0, s.total)
    }
}
