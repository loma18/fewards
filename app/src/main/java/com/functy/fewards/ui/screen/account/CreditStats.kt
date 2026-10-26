package com.functy.fewards.ui.screen.account

import com.functy.fewards.core.workbuddy.WorkBuddyCredits
import com.functy.fewards.data.repository.AccountRepository

/**
 * 积分统计：账号页顶部的汇总，以及每个账号的「N 日内到期可用积分」。
 *
 * 口径（与官方客户端的到期展示一致）：
 *
 * - **只算未过期的额度**：`expireAt <= now` 的包余额已经不可用，计入"总剩余"会虚高，
 *   计入"即将到期"更没有意义（已经过期了）。
 * - **按包汇总，不按笔**：官方只对包给出 `remaining`，同一商品码下的多笔历史无法拆出
 *   各自剩余，所以到期的粒度只能是「包」。
 * - **3 日与 5 日是两个独立的数**，不是"3 日内 + 3~5 日"。5 日内天然包含 3 日内的部分，
 *   这样 UI 上两个数字可以直接比较：3 日内的永远不会超过 5 日内的。
 * - **缺 uid 的账号不参与统计**：它连余额都查不了，算进"查询中"会让顶部一直挂着待办。
 */
object CreditStats {

    const val DAY_MS = 24L * 3600 * 1000

    /** 顶部汇总 */
    data class Summary(
        /** 所有账号剩余可用积分之和 */
        val remaining: Double = 0.0,
        /** 3 日内到期的可用积分（未过期、剩余 > 0） */
        val expiringIn3Days: Double = 0.0,
        /** 5 日内到期的可用积分 */
        val expiringIn5Days: Double = 0.0,
        /** 已成功拿到余额的账号数 */
        val counted: Int = 0,
        /** 有 uid 但还没拿到结果（查询中） */
        val loading: Int = 0,
        /** 查询失败 */
        val failed: Int = 0,
        /** 缺 uid，无法查询 */
        val skipped: Int = 0,
    ) {
        val total: Int get() = counted + loading + failed + skipped
    }

    /**
     * 某账号在 [days] 天内到期的可用积分。
     *
     * @param now 毫秒时间戳
     */
    fun expiringWithin(
        balance: WorkBuddyCredits.Balance?,
        days: Int,
        now: Long,
    ): Double {
        if (balance == null) return 0.0
        val limit = now + days * DAY_MS
        var sum = 0.0
        for (pkg in balance.packages) {
            val expireAt = pkg.expireAt ?: continue
            // 已经过期的不算；恰好等于 now 视为已过期
            if (expireAt <= now || expireAt > limit) continue
            if (pkg.remaining <= 0) continue
            sum += pkg.remaining
        }
        return sum
    }

    /**
     * 汇总所有账号。
     *
     * @param accounts 账号列表（来自仓储，顺序决定不了什么，只用来判断"有没有结果"）
     * @param credits 账号 id → 积分状态
     */
    fun summarize(
        accounts: List<AccountRepository.WorkBuddyAccount>,
        credits: Map<String, WbCreditState>,
        now: Long,
    ): Summary {
        var remaining = 0.0
        var three = 0.0
        var five = 0.0
        var counted = 0
        var loading = 0
        var failed = 0
        var skipped = 0

        for (acc in accounts) {
            if (acc.uid.isEmpty()) {
                skipped++
                continue
            }
            val state = credits[acc.id]
            val balance = state?.balance
            if (balance == null) {
                if (state?.error?.isNotEmpty() == true) failed++ else loading++
                continue
            }
            counted++
            remaining += balance.remaining
            three += expiringWithin(balance, 3, now)
            five += expiringWithin(balance, 5, now)
        }

        return Summary(
            remaining = remaining,
            expiringIn3Days = three,
            expiringIn5Days = five,
            counted = counted,
            loading = loading,
            failed = failed,
            skipped = skipped,
        )
    }
}
