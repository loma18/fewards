package com.functy.fewards.core.lansync

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress

/**
 * 同步客户端行为测试：起一个本地 HTTP 服务，验证真实发出的请求。
 *
 * 协议单测只能证明"JSON 拼对了"，证明不了：
 * - 路径是不是 `/api/lansync/push`（少个斜杠就 404）；
 * - `Authorization` 有没有带 `Bearer ` 前缀（电脑端会直接 401）；
 * - 非 2xx 时会不会把电脑端的中文文案原样抛出来（否则用户只看到 "HTTP 401"）。
 *
 * 这里用 JDK 自带的 HttpServer，不引额外依赖、不联网。
 */
class LanSyncClientTest {

    private lateinit var server: HttpServer
    private var port = 0
    private val captured = mutableListOf<Captured>()

    private data class Captured(val method: String, val auth: String?, val path: String, val body: String)

    @Before
    fun setUp() {
        captured.clear()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        port = server.address.port
        server.start()
    }

    @After
    fun tearDown() {
        server.stop(0)
    }

    /** 注册一个固定响应；同时把收到的请求记下来供断言 */
    private fun respond(path: String, status: Int, body: String) {
        server.createContext(path) { ex: HttpExchange ->
            val raw = ex.requestBody.readBytes().toString(Charsets.UTF_8)
            captured += Captured(
                method = ex.requestMethod,
                auth = ex.requestHeaders.getFirst("Authorization"),
                path = ex.requestURI.path,
                body = raw,
            )
            val bytes = body.toByteArray(Charsets.UTF_8)
            ex.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
            ex.sendResponseHeaders(status, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
    }

    private fun client() = LanSyncClient(OkHttpClient())
    private fun endpoint() = LanSyncProtocol.Endpoint("127.0.0.1", port)

    // ==================== 推送 ====================

    @Test
    fun pushSendsBearerTokenAndParsesResult() = runBlocking {
        respond(
            "/api/lansync/push",
            200,
            """{"ok":true,"results":[{"uid":"u1","action":"create","reason":"not-found",
               "message":"","risk":"","account":{"uid":"u1","label":"号","token":"AT",
               "refreshToken":"RT","expiresAt":1761003600,"refreshExpiresAt":1763595600,
               "isCurrent":false,"version":1}}]}""",
        )

        val resp = client().push(
            endpoint = endpoint(),
            pairCode = "12345678",
            accounts = listOf(
                LanSyncProtocol.Payload(
                    uid = "u1",
                    label = "号",
                    enterpriseId = "",
                    accessToken = "AT_PHONE",
                    refreshToken = "RT_PHONE",
                    expiresAt = 1_761_000_000,
                    obtainedAt = 1_761_000_000_000,
                )
            ),
            deviceName = "Pixel-Test",
            force = false,
        )

        // 请求侧
        assertEquals(1, captured.size)
        val req = captured[0]
        assertEquals("POST", req.method)
        assertEquals("/api/lansync/push", req.path)
        assertEquals("Bearer 12345678", req.auth)
        val sent = JSONObject(req.body)
        assertEquals(1, sent.getInt("version"))
        assertEquals("merge", sent.getString("mode"))
        assertEquals("Pixel-Test", sent.getJSONObject("device").getString("name"))
        val acc = sent.getJSONArray("accounts").getJSONObject(0)
        assertEquals("AT_PHONE", acc.getString("accessToken"))
        assertEquals(1_761_000_000L, acc.getLong("expiresAt"))
        assertEquals(1_761_000_000_000L, acc.getLong("obtainedAt"))

        // 响应侧
        assertEquals(1, resp.results.size)
        assertEquals("create", resp.results[0].action)
        assertEquals("AT", resp.results[0].account?.token)
    }

    @Test
    fun pushForceSendsForceMode() = runBlocking {
        respond("/api/lansync/push", 200, """{"ok":true,"results":[]}""")
        client().push(
            endpoint(), "12345678",
            listOf(LanSyncProtocol.Payload("u1", "号", "", "AT", "RT", 1_761_000_000, 1_761_000_000_000)),
            "Pixel", force = true,
        )
        assertEquals("force", JSONObject(captured[0].body).getString("mode"))
    }

    @Test
    fun pushSurfacesServerErrorMessage() = runBlocking {
        respond("/api/lansync/push", 401, """{"error":"配对码不正确"}""")
        val err = runCatching {
            client().push(
                endpoint(), "00000000",
                listOf(LanSyncProtocol.Payload("u1", "号", "", "AT", "RT", 1_761_000_000, 1_761_000_000_000)),
                "Pixel", false,
            )
        }.exceptionOrNull()

        assertTrue(err is LanSyncClient.LanSyncException)
        // 用户看到的必须是电脑端给的文案，而不是 "HTTP 401"
        assertEquals("配对码不正确", err?.message)
    }

    @Test
    fun pushFallsBackToStatusTextWhenBodyIsNotJson() = runBlocking {
        respond("/api/lansync/push", 413, "too large")
        val err = runCatching {
            client().push(
                endpoint(), "12345678",
                listOf(LanSyncProtocol.Payload("u1", "号", "", "AT", "RT", 1_761_000_000, 1_761_000_000_000)),
                "Pixel", false,
            )
        }.exceptionOrNull()
        assertEquals("账号太多，请求体超出上限", err?.message)
    }

    // ==================== 只读取凭据 ====================

    @Test
    fun fetchPostsUidsAndParsesAccounts() = runBlocking {
        respond(
            "/api/lansync/fetch",
            200,
            """{"ok":true,"count":1,"accounts":[{"uid":"u1","label":"号","token":"AT_PC",
               "refreshToken":"RT_PC","expiresAt":1761003600,"refreshExpiresAt":1763595600,
               "refreshedAt":1761000000000,"isCurrent":true,"version":4,"encrypted":false}]}""",
        )

        val accounts = client().fetch(endpoint(), "12345678", listOf("u1", "u2"))

        assertEquals("POST", captured[0].method)
        assertEquals("/api/lansync/fetch", captured[0].path)
        assertEquals("Bearer 12345678", captured[0].auth)
        val sent = JSONObject(captured[0].body)
        assertEquals(2, sent.getJSONArray("uids").length())
        assertEquals("u2", sent.getJSONArray("uids").getString(1))

        assertEquals(1, accounts.size)
        assertEquals("AT_PC", accounts[0].token)
        assertEquals(1_761_003_600L, accounts[0].expiresAt)
    }

    @Test
    fun fetchKeepsStatusCodeSoCallerCanDetectOldConsole() = runBlocking {
        // 旧版控制台没有 /fetch：调用方要能凭 404 退化成"只读推送"，不能只拿到一句文案
        respond("/api/lansync/fetch", 404, "<html>Cannot POST /api/lansync/fetch</html>")
        val err = runCatching { client().fetch(endpoint(), "12345678", listOf("u1")) }.exceptionOrNull()
        assertTrue(err is LanSyncClient.LanSyncException)
        assertEquals(404, (err as LanSyncClient.LanSyncException).code)
    }

    // ==================== 拉取 / 确认 ====================

    @Test
    fun pendingParsesItemsAndSendsAuth() = runBlocking {
        respond(
            "/api/lansync/pending",
            200,
            """{"ok":true,"count":1,"items":[{"id":"p1","uid":"u1","token":"AT",
               "refreshToken":"RT","expiresAt":1761003600,"refreshExpiresAt":1763595600}]}""",
        )
        val items = client().pending(endpoint(), "12345678")
        assertEquals(1, items.size)
        assertEquals("p1", items[0].id)
        assertEquals("Bearer 12345678", captured[0].auth)
        assertEquals("GET", captured[0].method)
    }

    @Test
    fun ackPostsIdsAndSkipsEmpty() = runBlocking {
        respond("/api/lansync/ack", 200, """{"ok":true,"removed":1,"remaining":0}""")
        val c = client()
        c.ack(endpoint(), "12345678", emptyList())
        // 空列表不应该发请求：省一次无意义的往返
        assertTrue(captured.isEmpty())

        c.ack(endpoint(), "12345678", listOf("p1", "p2"))
        assertEquals(1, captured.size)
        val sent = JSONObject(captured[0].body)
        assertEquals(2, sent.getJSONArray("ids").length())
    }

    @Test
    fun pingReturnsFalseWhenServerDown() = runBlocking {
        // 指向一个刚关掉的端口：connect 失败应返回 false 而不是抛异常
        val deadPort = port
        server.stop(0)
        assertFalse(client().ping(LanSyncProtocol.Endpoint("127.0.0.1", deadPort)))
    }
}
