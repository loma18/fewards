package com.functy.fewards.core.lansync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 凭据有效性判定。
 *
 * 这层不联网，但口径错了后果很实在：
 *
 * - 把"有效"判成"失效" → 「从电脑同步凭据」会拿电脑端的旧值覆盖手机上还能用的凭据；
 * - 把"失效"判成"有效" → 按钮点了什么也不做，用户以为功能坏了。
 *
 * 恒定用 2026-01-01 00:00:00 UTC 当"现在"，避免测试跟着机器时间漂。
 */
class CredentialHealthTest {

    /** 毫秒 */
    private val now = 1_767_225_600_000L

    /** 秒 */
    private val nowSec = now / 1000

    @Test
    fun expiredAccessTokenIsDead() {
        assertTrue(CredentialHealth.isDead("AT", nowSec - 60, now))
        // 恰好等于"现在"也算过期：边界上用 <=，避免差一秒时反复横跳
        assertTrue(CredentialHealth.isDead("AT", nowSec, now))
    }

    @Test
    fun liveAccessTokenIsUsable() {
        assertFalse(CredentialHealth.isDead("AT", nowSec + 60, now))
        assertTrue(CredentialHealth.isUsable("AT", nowSec + 60, now))
    }

    @Test
    fun unknownExpiryCountsAsUsable() {
        // 0 = 判断不了（老数据 / 手动粘贴的裸 token）。当成过期会把还能用的账号判死
        assertFalse(CredentialHealth.isDead("AT", 0, now))
        assertTrue(CredentialHealth.isUsable("AT", 0, now))
    }

    @Test
    fun blankTokenIsDeadRegardlessOfExpiry() {
        assertTrue(CredentialHealth.isDead("", nowSec + 3600, now))
        assertTrue(CredentialHealth.isDead("   ", nowSec + 3600, now))
    }

    @Test
    fun boundaryIsSecondExact() {
        // 秒 → 毫秒的换算必须真的做了：nowSec 恰好等于"现在"，再少一秒就过期，多一秒就还能用。
        // 少了这个 *1000，边界会整体右移 1000 倍，任何"刚过期"的账号都会漏判。
        assertTrue(CredentialHealth.isDead("AT", nowSec - 1, now))
        assertFalse(CredentialHealth.isDead("AT", nowSec + 1, now))
    }

    @Test
    fun refreshExpiryOnlyAppliesWhenKnown() {
        assertTrue(CredentialHealth.isRefreshExpired("", 0, now))
        // refresh_token 在、但过期时间未知：算可用（手机端自己扫码拿到的记录就没有这个字段）
        assertFalse(CredentialHealth.isRefreshExpired("RT", 0, now))
        assertTrue(CredentialHealth.isRefreshExpired("RT", nowSec - 1, now))
        assertFalse(CredentialHealth.isRefreshExpired("RT", nowSec + 3600, now))
    }
}
