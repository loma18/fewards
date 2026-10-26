package com.functy.fewards.ui.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 账号页凭据详情的两个纯函数。
 *
 * 守的是"看不出来但会误导人"的两点：
 *
 * 1. **0 不能渲染成 1970 年**：`expiresAt`/`refreshExpiresAt` 为 0 表示记录里没有这个字段
 *    （判断不了），必须显示"未知"。写成 1970 会被当成"早就过期"。
 * 2. **两个时间各自算**：access 与 refresh 用的是不同的字段，混用会让"refresh 还有 30 天"
 *    显示成"已过期"，反过来则更危险。
 *
 * 只用「剩余 / 已过期」这两个与本地时区无关的片段做断言，避免测试跟着时区漂。
 */
class WbCredentialFormatTest {

    private val now = 1_767_225_600_000L

    @Test
    fun zeroExpiryIsUnknownNot1970() {
        assertEquals("未知（这条记录没带这个时间）", formatCredentialExpiry(0, now))
    }

    @Test
    fun expiredShowsExpired() {
        assertTrue(formatCredentialExpiry(now / 1000 - 60, now).contains("已过期"))
    }

    @Test
    fun hoursAndDaysUseDifferentUnits() {
        assertTrue(formatCredentialExpiry(now / 1000 + 3600, now).contains("剩余 1.0 小时"))
        assertTrue(formatCredentialExpiry(now / 1000 + 72 * 3600, now).contains("剩余 3.0 天"))
    }

    @Test
    fun maskKeepsHeadAndTailOnly() {
        val token = "abcdefghijklmnopqrstuvwxyz0123456789"
        val masked = maskCredential(token)
        assertTrue(masked.startsWith("abcdef"))
        assertTrue(masked.endsWith("6789"))
        assertTrue(masked.contains("…"))
        // 中间那段绝不能露出来，否则掩码毫无意义
        assertTrue(!masked.contains("klmnop"))
    }

    @Test
    fun shortValuesAreFullyMasked() {
        // 占位文案 / 异常数据：首尾拼接会把内容露光
        assertEquals("••••••", maskCredential("（无）"))
        assertEquals("••••••", maskCredential(""))
    }
}
