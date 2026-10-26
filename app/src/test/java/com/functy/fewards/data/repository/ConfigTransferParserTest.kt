package com.functy.fewards.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 配置解析：只认 WorkBuddy。
 *
 * 米游社 / Bing 移除后，旧备份里的 `mihoyos` / `bings` 必须被**忽略而不是报错**——
 * 用户拿去年的备份导入时，不该看到一串失败，也不该以为数据丢了。
 */
class ConfigTransferParserTest {

    @Test
    fun parsesWorkBuddyFromBundle() {
        val json = """
            {
              "_format": "fewards-config",
              "version": 1,
              "workbuddies": [{"token":"eyJhbGciOiJub25lIn0.e30.x","label":"WB","uid":"u1"}]
            }
        """.trimIndent()
        val parsed = ConfigTransferParser.parse(json)
        assertEquals(1, parsed.workbuddies.size)
        assertEquals("WB", parsed.workbuddies[0].label)
        assertEquals("u1", parsed.workbuddies[0].uid)
        assertEquals(0, parsed.ignoredLegacy)
        assertTrue(parsed.errors.isEmpty())
    }

    @Test
    fun legacySectionsAreIgnoredWithNotice() {
        // 旧备份同时有米游社和 Bing：不导入，但要说明一句
        val json = """
            {
              "_format": "fewards-config",
              "version": 1,
              "mihoyos": [{"stoken":"v2_a","stuid":"1"}],
              "bings": [{"refreshToken":"RT-abcdefghijklmnop"}],
              "workbuddies": [{"token":"eyJhbGciOiJub25lIn0.e30.x"}]
            }
        """.trimIndent()
        val parsed = ConfigTransferParser.parse(json)
        assertEquals(1, parsed.workbuddies.size)
        assertEquals(2, parsed.ignoredLegacy)
        assertEquals(1, parsed.errors.size)
        assertTrue(parsed.errors[0].contains("已忽略"))
    }

    @Test
    fun skipsWorkBuddyEntryWithoutToken() {
        val json = """{"_format":"fewards-config","workbuddies":[{"label":"没有凭据"}]}"""
        val parsed = ConfigTransferParser.parse(json)
        assertTrue(parsed.workbuddies.isEmpty())
        assertEquals(1, parsed.errors.size)
    }

    @Test
    fun bundleWithoutWorkBuddiesIsStillFine() {
        val json = """{"_format":"fewards-config","version":1,"mihoyos":[],"workbuddies":[]}"""
        val parsed = ConfigTransferParser.parse(json)
        assertTrue(parsed.workbuddies.isEmpty())
        assertTrue(parsed.errors.isEmpty())
    }

    @Test
    fun bareJwtStartsWithEyJ() {
        val parsed = ConfigTransferParser.parse("eyJhbGciOiJub25lIn0.e30.sig")
        assertEquals(1, parsed.workbuddies.size)
        assertEquals("wb_import", parsed.workbuddies[0].id)
    }

    @Test
    fun legacyMihoyoCookieIsRejectedWithClearReason() {
        // 裸 Cookie 曾用于导入米游社；现在没有对应功能，必须给可读原因而不是"格式错误"
        val parsed = ConfigTransferParser.parse("stoken=v2_abc; mid=xyz; account_id=10001")
        assertFalse(parsed.recognized)
        assertTrue(parsed.workbuddies.isEmpty())
        assertTrue(parsed.errors[0].contains("米游社"))
    }

    @Test
    fun garbageIsUnrecognized() {
        val parsed = ConfigTransferParser.parse("hello world")
        assertFalse(parsed.recognized)
        assertTrue(parsed.workbuddies.isEmpty())
    }

    @Test
    fun emptyIsUnrecognized() {
        val parsed = ConfigTransferParser.parse("   ")
        assertFalse(parsed.recognized)
    }
}
