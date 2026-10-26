package com.functy.fewards.core.lansync

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 手机同步协议。
 *
 * 这里守的是"写错了界面上看不出来、但会让同步静默失效"的几条：
 *
 * 1. **地址只允许私网**：token 是长期凭据，手滑填成公网 IP 就把账号送出去了；
 * 2. **秒 / 毫秒**：a 的 expiresAt 是秒，电脑端要的 obtainedAt 是毫秒，差 1000 倍；
 * 3. **回传字段名**：电脑端用 snake_case，a 用 camelCase，解析错就永远拿不到新 token。
 */
class LanSyncProtocolTest {

    // ==================== 地址解析 ====================

    @Test
    fun parsesHostAndPort() {
        val e = LanSyncProtocol.parseEndpoint("192.168.1.5:57900")
        assertEquals("192.168.1.5", e.host)
        assertEquals(57900, e.port)
        assertEquals("http://192.168.1.5:57900", e.baseUrl)
    }

    @Test
    fun defaultsPortWhenMissing() {
        assertEquals(LanSyncProtocol.DEFAULT_PORT, LanSyncProtocol.parseEndpoint("192.168.1.5").port)
    }

    @Test
    fun toleratesSchemeAndTrailingSlash() {
        val e = LanSyncProtocol.parseEndpoint("  http://10.0.0.8:58000/  ")
        assertEquals("10.0.0.8", e.host)
        assertEquals(58000, e.port)
    }

    @Test
    fun rejectsPublicAddress() {
        // 8.8.8.8 是公网 DNS：绝不允许把凭据发出去
        val err = runCatching { LanSyncProtocol.parseEndpoint("8.8.8.8:57900") }.exceptionOrNull()
        assertTrue(err is LanSyncProtocol.BadEndpoint)
    }

    @Test
    fun rejectsDomainName() {
        // 域名会触发 DNS 解析，解析成功恰恰说明目标是公网主机
        val err = runCatching { LanSyncProtocol.parseEndpoint("example.com:57900") }.exceptionOrNull()
        assertTrue(err is LanSyncProtocol.BadEndpoint)
    }

    @Test
    fun rejectsBadPort() {
        assertTrue(runCatching { LanSyncProtocol.parseEndpoint("192.168.1.5:0") }.exceptionOrNull() is LanSyncProtocol.BadEndpoint)
        assertTrue(runCatching { LanSyncProtocol.parseEndpoint("192.168.1.5:70000") }.exceptionOrNull() is LanSyncProtocol.BadEndpoint)
        assertTrue(runCatching { LanSyncProtocol.parseEndpoint("192.168.1.5:abc") }.exceptionOrNull() is LanSyncProtocol.BadEndpoint)
    }

    @Test
    fun rejectsEmpty() {
        assertTrue(runCatching { LanSyncProtocol.parseEndpoint("   ") }.exceptionOrNull() is LanSyncProtocol.BadEndpoint)
    }

    @Test
    fun rejectsIpv6ForNow() {
        // 电脑端只监听 IPv4；这里明确拒绝，而不是解析出一个连不上的地址
        assertTrue(runCatching { LanSyncProtocol.parseEndpoint("fe80::1:57900") }.exceptionOrNull() is LanSyncProtocol.BadEndpoint)
    }

    @Test
    fun privateRangesAreAccepted() {
        listOf("127.0.0.1", "10.1.2.3", "192.168.0.1", "172.16.0.1", "172.31.255.254", "169.254.3.4", "localhost")
            .forEach { assertTrue("应允许 $it", LanSyncProtocol.isPrivateHost(it)) }
    }

    @Test
    fun publicRangesAreRejected() {
        listOf("8.8.8.8", "1.1.1.1", "172.15.0.1", "172.32.0.1", "192.169.0.1", "11.0.0.1", "256.1.1.1", "abc")
            .forEach { assertFalse("应拒绝 $it", LanSyncProtocol.isPrivateHost(it)) }
    }

    // ==================== 请求体 ====================

    @Test
    fun pushBodyUsesWireFieldNamesAndUnits() {
        val body = LanSyncProtocol.buildPushBody(
            accounts = listOf(
                LanSyncProtocol.Payload(
                    uid = "u1",
                    label = "我的号",
                    enterpriseId = "ent-1",
                    accessToken = "AT",
                    refreshToken = "RT",
                    expiresAt = 1_761_000_000,      // 秒
                    obtainedAt = 1_761_000_000_000, // 毫秒
                    refreshExpiresAt = 1_761_500_000, // 秒
                )
            ),
            deviceName = "Pixel",
            force = true,
        )
        assertEquals(1, body.getInt("version"))
        assertEquals("force", body.getString("mode"))
        assertEquals("Pixel", body.getJSONObject("device").getString("name"))
        val acc = body.getJSONArray("accounts").getJSONObject(0)
        assertEquals("u1", acc.getString("uid"))
        assertEquals("AT", acc.getString("accessToken"))
        assertEquals("RT", acc.getString("refreshToken"))
        // 秒：不能被乘成毫秒，否则电脑端会判定"10 万年后才过期"
        assertEquals(1_761_000_000L, acc.getLong("expiresAt"))
        assertEquals(1_761_000_000_000L, acc.getLong("obtainedAt"))
        // refresh 的有效期必须跟着推送走：漏了它，电脑端只能按"未知"落库
        assertEquals(1_761_500_000L, acc.getLong("refreshExpiresAt"))
        assertEquals("cn", acc.getString("variant"))
    }

    @Test
    fun mergeModeByDefault() {
        val body = LanSyncProtocol.buildPushBody(emptyList(), "Pixel", force = false)
        assertEquals("merge", body.getString("mode"))
        assertEquals(0, body.getJSONArray("accounts").length())
    }

    // ==================== 响应解析 ====================

    @Test
    fun parsesPushResults() {
        val json = JSONObject(
            """
            {
              "ok": true,
              "protocol": 1,
              "serverTime": 1761000000000,
              "results": [
                {
                  "uid": "u1",
                  "action": "adopt",
                  "reason": "remote-newer",
                  "message": "",
                  "risk": "",
                  "account": {
                    "uid": "u1",
                    "label": "电脑上的名字",
                    "token": "AT_NEW",
                    "refreshToken": "RT_NEW",
                    "expiresAt": 1761003600,
                    "refreshExpiresAt": 1763595600,
                    "isCurrent": false,
                    "version": 7
                  }
                }
              ]
            }
            """.trimIndent()
        )
        val resp = LanSyncProtocol.parsePushResponse(json)
        assertEquals(1, resp.results.size)
        val r = resp.results[0]
        assertEquals("adopt", r.action)
        assertNotNull(r.account)
        val acc = requireNotNull(r.account)
        assertEquals("AT_NEW", acc.token)
        assertEquals("RT_NEW", acc.refreshToken)
        // 电脑端回传的是秒，a 直接用
        assertEquals(1_761_003_600L, acc.expiresAt)
        assertEquals(7L, acc.version)
        assertFalse(acc.isCurrent)
    }

    @Test
    fun parsesResultWithoutAccount() {
        // skipped-dead 时电脑端不回账号，解析不能崩
        val json = JSONObject(
            """{"results":[{"uid":"u9","action":"skipped-dead","reason":"remote-expired","message":"","risk":""}]}"""
        )
        val resp = LanSyncProtocol.parsePushResponse(json)
        assertEquals("skipped-dead", resp.results[0].action)
        assertNull(resp.results[0].account)
    }

    @Test
    fun toleratesMissingResults() {
        val resp = LanSyncProtocol.parsePushResponse(JSONObject("""{"ok":true}"""))
        assertTrue(resp.results.isEmpty())
    }

    @Test
    fun parsesPendingItems() {
        val json = JSONObject(
            """
            {
              "ok": true,
              "count": 2,
              "items": [
                {"id":"a","uid":"u1","token":"AT1","refreshToken":"RT1","expiresAt":1761003600,"refreshExpiresAt":1763595600},
                {"id":"","uid":"u2","token":"AT2","refreshToken":"RT2"},
                {"id":"b","uid":"u3","token":"","refreshToken":"RT3"}
              ]
            }
            """.trimIndent()
        )
        val items = LanSyncProtocol.parsePending(json)
        // 没有 id 的条目无法 ack，必须丢弃
        assertEquals(2, items.size)
        assertEquals("u1", items[0].uid)
        assertEquals("RT1", items[0].refreshToken)
        assertEquals(1_761_003_600L, items[0].expiresAt)
        assertEquals("u3", items[1].uid)
    }

    // ==================== 只读取凭据 ====================

    @Test
    fun fetchBodyCarriesUids() {
        val body = LanSyncProtocol.buildFetchBody(listOf("u1", "u2"))
        assertEquals(1, body.getInt("version"))
        val uids = body.getJSONArray("uids")
        assertEquals(2, uids.length())
        assertEquals("u1", uids.getString(0))
    }

    @Test
    fun parsesFetchAccounts() {
        // 与 push 回传同一个结构，额外带 encrypted / refreshedAt：
        // 手机端要靠 encrypted 区分"电脑端没有明文"和"电脑端没有这个账号"
        val json = JSONObject(
            """
            {
              "ok": true,
              "count": 2,
              "accounts": [
                {
                  "uid": "u1",
                  "label": "电脑上的名字",
                  "token": "AT_PC",
                  "refreshToken": "RT_PC",
                  "expiresAt": 1761003600,
                  "refreshExpiresAt": 1763595600,
                  "refreshedAt": 1761000000000,
                  "isCurrent": true,
                  "version": 5,
                  "encrypted": false
                },
                {
                  "uid": "u2",
                  "label": "加密号",
                  "token": "",
                  "refreshToken": "",
                  "expiresAt": 0,
                  "refreshExpiresAt": 0,
                  "isCurrent": false,
                  "version": 0,
                  "encrypted": true
                }
              ]
            }
            """.trimIndent()
        )
        val list = LanSyncProtocol.parseFetchResponse(json)
        assertEquals(2, list.size)
        assertEquals("AT_PC", list[0].token)
        assertEquals(1_761_003_600L, list[0].expiresAt)
        assertTrue(list[0].isCurrent)
        assertEquals(1_761_000_000_000L, list[0].refreshedAt)
        assertFalse(list[0].encrypted)
        // 加密信封：token 是空串，必须靠 encrypted 认出来
        assertTrue(list[1].encrypted)
        assertTrue(list[1].token.isEmpty())
    }

    @Test
    fun toleratesMissingFetchAccounts() {
        assertTrue(LanSyncProtocol.parseFetchResponse(JSONObject("""{"ok":true}""")).isEmpty())
    }

    // ==================== 档位（国内版 / 国际版） ====================

    @Test
    fun normalizeVariantOnlyKeepsTwoValues() {
        // 未知 / 缺失一律按国内版：历史数据没有这个字段，打错域名比"不认识"更糟
        assertEquals("cn", LanSyncProtocol.normalizeVariant(null))
        assertEquals("cn", LanSyncProtocol.normalizeVariant(""))
        assertEquals("cn", LanSyncProtocol.normalizeVariant("cn"))
        assertEquals("cn", LanSyncProtocol.normalizeVariant("CN"))
        assertEquals("cn", LanSyncProtocol.normalizeVariant("whatever"))
        assertEquals("ai", LanSyncProtocol.normalizeVariant("ai"))
        assertEquals("ai", LanSyncProtocol.normalizeVariant("AI"))
        assertEquals("ai", LanSyncProtocol.normalizeVariant("workbuddy-ai"))
        assertEquals("ai", LanSyncProtocol.normalizeVariant("intl"))
    }

    @Test
    fun pushBodyCarriesPerAccountVariant() {
        // 早先这里写死 "cn"：手机推过去的国际版账号在电脑端被当成国内版，切号时打错域名 401
        val body = LanSyncProtocol.buildPushBody(
            accounts = listOf(
                LanSyncProtocol.Payload(
                    uid = "u-cn", label = "国内号", enterpriseId = "", accessToken = "AT",
                    refreshToken = "RT", expiresAt = 0L, obtainedAt = 0L,
                ),
                LanSyncProtocol.Payload(
                    uid = "u-ai", label = "国际号", enterpriseId = "", accessToken = "AT2",
                    refreshToken = "RT2", expiresAt = 0L, obtainedAt = 0L, variant = "ai",
                ),
            ),
            deviceName = "Pixel",
            force = false,
        )
        val arr = body.getJSONArray("accounts")
        assertEquals("cn", arr.getJSONObject(0).getString("variant"))
        assertEquals("ai", arr.getJSONObject(1).getString("variant"))
    }

    @Test
    fun parsesFetchVariant() {
        val json = JSONObject(
            """
            {
              "ok": true,
              "accounts": [
                {"uid":"u1","variant":"ai","token":"AT","refreshToken":"RT"},
                {"uid":"u2","token":"AT","refreshToken":"RT"},
                {"uid":"u3","variant":"workbuddy-ai","token":"AT","refreshToken":"RT"}
              ]
            }
            """.trimIndent()
        )
        val list = LanSyncProtocol.parseFetchResponse(json)
        assertEquals("ai", list[0].variant)
        // 缺字段按国内版（老控制台 / 历史数据），不能因为读不到就丢掉账号
        assertEquals("cn", list[1].variant)
        assertEquals("ai", list[2].variant)
    }

    @Test
    fun parsesPushResultVariant() {
        val json = JSONObject(
            """
            {"results":[{"uid":"u1","action":"adopt","reason":"","message":"","risk":"",
              "account":{"uid":"u1","token":"AT","refreshToken":"RT","variant":"ai"}}]}
            """.trimIndent()
        )
        assertEquals("ai", LanSyncProtocol.parsePushResponse(json).results[0].account?.variant)
    }

    // ==================== 错误文案 ====================

    @Test
    fun errorMessagePrefersServerText() {
        assertEquals("配对码不正确", LanSyncProtocol.errorMessage("""{"error":"配对码不正确"}""", 401))
    }

    @Test
    fun errorMessageFallsBackByStatus() {
        assertEquals("配对码不正确", LanSyncProtocol.errorMessage("not json", 401))
        assertEquals("请求过于频繁，请稍后再试", LanSyncProtocol.errorMessage("", 429))
        assertEquals("账号太多，请求体超出上限", LanSyncProtocol.errorMessage("", 413))
        // 旧版控制台没有 /fetch：必须提示"升级电脑端"，而不是让用户以为配对码填错了
        assertTrue(LanSyncProtocol.errorMessage("", 404).contains("过旧"))
        assertTrue(LanSyncProtocol.errorMessage("", 500).contains("500"))
    }
}
