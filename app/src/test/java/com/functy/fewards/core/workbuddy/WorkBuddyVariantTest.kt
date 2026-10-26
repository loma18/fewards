package com.functy.fewards.core.workbuddy

import com.functy.fewards.data.repository.AccountRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 档位（国内版 / 国际版）。
 *
 * 这里钉的是"搞错了不会崩、但会让账号一直 401"的几条：
 *
 * 1. **归一化**：只认 `cn` / `ai`，其余（含历史数据缺失）一律国内版，避免老账号被误判
 *    成国际版后打到错域名；
 * 2. **域名与端点**：积分走官网域、刷新走 chat 域，两档各自一套，**绝不混用**；
 * 3. **能力差异**：签到与成长中心目前只有国内版有接口，国际版必须被跳过而不是硬打；
 * 4. **分组顺序**：界面按 `ordered()` 渲染，国内版在前。
 */
class WorkBuddyVariantTest {

    private fun account(variant: String): AccountRepository.WorkBuddyAccount =
        AccountRepository.WorkBuddyAccount(id = "wb_1", label = "测试号", token = "AT", variant = variant)

    // ==================== 归一化 ====================

    @Test
    fun unrecognizedFallsBackToCn() {
        assertEquals(WorkBuddyVariant.CN, WorkBuddyVariant.of(null))
        assertEquals(WorkBuddyVariant.CN, WorkBuddyVariant.of(""))
        assertEquals(WorkBuddyVariant.CN, WorkBuddyVariant.of("cn"))
        assertEquals(WorkBuddyVariant.CN, WorkBuddyVariant.of("CN"))
        assertEquals(WorkBuddyVariant.CN, WorkBuddyVariant.of("unknown"))
    }

    @Test
    fun aiAliasesAreRecognized() {
        assertEquals(WorkBuddyVariant.AI, WorkBuddyVariant.of("ai"))
        assertEquals(WorkBuddyVariant.AI, WorkBuddyVariant.of("AI"))
        assertEquals(WorkBuddyVariant.AI, WorkBuddyVariant.of("workbuddy-ai"))
        assertEquals(WorkBuddyVariant.AI, WorkBuddyVariant.of("intl"))
    }

    @Test
    fun accountWithoutVariantIsCn() {
        // 历史账号没有这个字段：读出来是 "cn"（仓储层兜底），归一化同样按国内版
        assertEquals(WorkBuddyVariant.CN, WorkBuddyVariant.ofAccount(account("cn")))
        assertEquals(WorkBuddyVariant.CN, account("cn").wbVariant)
        assertEquals(WorkBuddyVariant.AI, WorkBuddyVariant.ofAccount(account("ai")))
    }

    // ==================== 端点与能力 ====================

    @Test
    fun endpointsAreSplitByVariant() {
        assertEquals("https://www.codebuddy.cn", WorkBuddyVariant.CN.apiEndpoint)
        assertEquals("https://copilot.tencent.com", WorkBuddyVariant.CN.chatBase)
        assertEquals("https://www.workbuddy.ai", WorkBuddyVariant.AI.apiEndpoint)
        assertEquals("https://www.workbuddy.ai", WorkBuddyVariant.AI.chatBase)
    }

    @Test
    fun idsMatchTheWireProtocol() {
        // 电脑端 `variant.mjs` / `protocol.mjs` 里就是这个两个值，改了就对不上
        assertEquals("cn", WorkBuddyVariant.CN.id)
        assertEquals("ai", WorkBuddyVariant.AI.id)
        assertEquals("CLI", WorkBuddyVariant.CN.oauthPlatform)
        assertEquals("workbuddy-ai", WorkBuddyVariant.AI.oauthPlatform)
    }

    @Test
    fun checkinAndGrowthAreCnOnly() {
        assertTrue(WorkBuddyVariant.CN.supportsCheckin)
        assertTrue(WorkBuddyVariant.CN.supportsGrowth)
        assertFalse(WorkBuddyVariant.AI.supportsCheckin)
        assertFalse(WorkBuddyVariant.AI.supportsGrowth)
    }

    // ==================== 分组 ====================

    @Test
    fun orderedPutsCnFirst() {
        assertEquals(listOf(WorkBuddyVariant.CN, WorkBuddyVariant.AI), WorkBuddyVariant.ordered())
    }

    @Test
    fun groupDropsEmptyVariantsAndKeepsOrder() {
        val list = listOf(
            account("ai"),
            account("cn"),
            account("ai"),
        )
        val groups = WorkBuddyVariant.group(list)
        assertEquals(2, groups.size)
        assertEquals(WorkBuddyVariant.CN, groups[0].first)
        assertEquals(1, groups[0].second.size)
        assertEquals(WorkBuddyVariant.AI, groups[1].first)
        assertEquals(2, groups[1].second.size)
    }

    @Test
    fun groupOfSingleVariantHasOneSection() {
        val groups = WorkBuddyVariant.group(listOf(account("cn"), account("cn")))
        assertEquals(1, groups.size)
        assertEquals(2, groups[0].second.size)
    }
}
