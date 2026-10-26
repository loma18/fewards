package com.functy.fewards.core.workbuddy

import com.functy.fewards.data.repository.AccountRepository
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 积分查询的判据测试。
 *
 * 这些规则照搬自 workbuddy-console 的 `credits.mjs`，每条都对应一次线上的错误展示，
 * 所以这里逐条钉死。HTTP 部分用 JDK 自带 HttpServer，不联网。
 *
 * 重点覆盖三处最容易写错的地方：
 * 1. **到期时间的两种口径**：`DeductionEndTime` 可能是「周期结束 + 整 30 天」的长期占位，
 *    判断必须 `>=`，否则整 30 天的值漏判，到期时间普遍多算一个月；
 * 2. **有额度优先**：同一个商品码下几十条历史，其中「未过期」的可能全部已用完，
 *    直接取最早会显示一个已经没额度的到期日；
 * 3. **缺 uid 不查**：没有 uid 的账号打接口没有意义，直接给可读错误。
 */
class WorkBuddyCreditsTest {

    private lateinit var server: HttpServer
    private var port = 0

    /** path → (status, body)；未注册的路径返回 404 */
    private val routes = mutableMapOf<String, Pair<Int, String>>()
    private val hits = mutableListOf<String>()

    private val client = OkHttpClient.Builder().build()

    @Before
    fun setUp() {
        routes.clear()
        hits.clear()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        port = server.address.port
        server.createContext("/") { ex: HttpExchange ->
            val path = ex.requestURI.path
            hits += path
            val (status, body) = routes[path] ?: (404 to """{"code":404,"msg":"not found"}""")
            val bytes = body.toByteArray(Charsets.UTF_8)
            ex.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
            ex.sendResponseHeaders(status, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    @After
    fun tearDown() {
        server.stop(0)
    }

    private fun credits() = WorkBuddyCredits(client, "http://127.0.0.1:$port")

    private fun account(uid: String = "u-1") = AccountRepository.WorkBuddyAccount(
        id = "wb_$uid",
        label = "测试账号",
        token = "AT",
        uid = uid,
        enterpriseId = "ent-1",
    )

    private fun fmt(ms: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(ms))

    private fun daysFromNow(days: Int): String = fmt(System.currentTimeMillis() + days * 86_400_000L)

    // ==================== 时间解析 ====================

    @Test
    fun `秒级时间戳按秒换算`() {
        assertEquals(1_761_260_000_000L, WorkBuddyCredits.parseTimestampMs(1_761_260_000L)!!)
    }

    @Test
    fun `毫秒时间戳原样保留`() {
        assertEquals(1_761_260_000_000L, WorkBuddyCredits.parseTimestampMs(1_761_260_000_000L)!!)
    }

    @Test
    fun `本地时间字符串按本地时区解析`() {
        val text = "2026-10-15 11:40:29"
        val cal = Calendar.getInstance().apply {
            set(2026, Calendar.OCTOBER, 15, 11, 40, 29)
            set(Calendar.MILLISECOND, 0)
        }
        assertEquals(cal.timeInMillis, WorkBuddyCredits.parseTimestampMs(text)!!)
    }

    @Test
    fun `无法解析的时间返回 null`() {
        assertNull(WorkBuddyCredits.parseTimestampMs(""))
        assertNull(WorkBuddyCredits.parseTimestampMs(null))
        assertNull(WorkBuddyCredits.parseTimestampMs("不是时间"))
    }

    // ==================== 到期时间裁决 ====================

    private fun rawWith(deduction: String?, cycle: String?): JSONObject = JSONObject().apply {
        if (deduction != null) put("DeductionEndTime", deduction)
        if (cycle != null) put("CycleEndTime", cycle)
    }

    @Test
    fun `扣除结束时间恰好比周期结束晚 30 天时改用周期结束`() {
        // 实测 code_007 就是这个形状：DeductionEndTime == CycleEndTime + 整 30 天。
        // 用 > 判断会漏掉，到期时间于是多算一个月。
        val cycle = daysFromNow(9)
        val deduction = daysFromNow(39)
        val expireAt = WorkBuddyCredits.resolveExpireAt(rawWith(deduction, cycle), System.currentTimeMillis())
        assertNotNull(expireAt)
        assertEquals(WorkBuddyCredits.parseTimestampMs(cycle)!!, expireAt!!)
    }

    @Test
    fun `扣除结束时间比周期结束早时用扣除结束`() {
        val cycle = daysFromNow(30)
        val deduction = daysFromNow(9)
        val expireAt = WorkBuddyCredits.resolveExpireAt(rawWith(deduction, cycle), System.currentTimeMillis())
        assertEquals(WorkBuddyCredits.parseTimestampMs(deduction)!!, expireAt!!)
    }

    @Test
    fun `只有周期结束时间时直接用它`() {
        val cycle = daysFromNow(12)
        val expireAt = WorkBuddyCredits.resolveExpireAt(rawWith(null, cycle), System.currentTimeMillis())
        assertEquals(WorkBuddyCredits.parseTimestampMs(cycle)!!, expireAt!!)
    }

    @Test
    fun `超过一年的到期时间视为长期有效`() {
        val far = daysFromNow(400)
        assertNull(WorkBuddyCredits.resolveExpireAt(rawWith(far, null), System.currentTimeMillis()))
    }

    @Test
    fun `没有时间字段时返回 null`() {
        assertNull(WorkBuddyCredits.resolveExpireAt(JSONObject(), System.currentTimeMillis()))
    }

    // ==================== 包名 ====================

    @Test
    fun `已知商品码用官方映射名`() {
        val raw = JSONObject().put("PackageCode", "TCACA_code_002_AkiJS3ZHF5")
        assertEquals("套餐基础积分", WorkBuddyCredits.packageName(raw))
    }

    @Test
    fun `未知商品码回落后端下发的包名`() {
        val raw = JSONObject()
            .put("PackageCode", "TCACA_code_999_unknown")
            .put("PackageName", "活动的积分包")
        assertEquals("活动的积分包", WorkBuddyCredits.packageName(raw))
    }

    @Test
    fun `包名和商品码都缺失时不抛异常`() {
        assertEquals("积分包", WorkBuddyCredits.packageName(JSONObject()))
    }

    // ==================== 余额 ====================

    private fun summaryBody(json: String) = json

    @Test
    fun `余额解析含到期补全与排序`() = runBlocking {
        val paidCode = "TCACA_code_002_AkiJS3ZHF5"
        val freeCode = "TCACA_code_007_nzdH5h4Nl0"
        routes["/billing/meter/get-user-resource-summary"] = 200 to summaryBody(
            """
            {"code":0,"data":{"Packages":[
              {"PackageCode":"$paidCode","CycleTotalCapacity":500,"CycleRemainCapacity":120,"CycleUsedCapacity":380},
              {"PackageCode":"$freeCode","CycleTotalCapacity":100,"CycleRemainCapacity":100}
            ]}}
            """.trimIndent()
        )
        // 付费包：一条「未过期但已用完」10 天后到期，一条「未过期且还有额度」20 天后到期
        // → 到期时间必须取 20 天那条，否则卡片显示的是已经没额度的日期
        val paidSoon = daysFromNow(10)
        val paidLater = daysFromNow(20)
        val freeSoon = daysFromNow(5)
        routes["/billing/meter/get-user-resource-paid-packages"] = 200 to summaryBody(
            """
            {"code":0,"data":{"Accounts":[
              {"PackageCode":"$paidCode","DeductionEndTime":"$paidSoon",
               "CycleCapacityRemain":0,"CycleCapacitySize":100,"CycleCapacityUsed":100},
              {"PackageCode":"$paidCode","DeductionEndTime":"$paidLater",
               "CycleCapacityRemain":80,"CycleCapacitySize":100}
            ]}}
            """.trimIndent()
        )
        routes["/billing/meter/get-user-resource-free-packages"] = 200 to summaryBody(
            """
            {"code":0,"data":{"Accounts":[
              {"PackageCode":"$freeCode","DeductionEndTime":"$freeSoon",
               "CycleCapacityRemain":100,"CycleCapacitySize":100}
            ]}}
            """.trimIndent()
        )

        val balance = credits().balance(account())

        assertEquals(600.0, balance.total, 0.01)
        assertEquals(220.0, balance.remaining, 0.01)
        // 总额 - 剩余 兜底：付费包没给全 used，free 包干脆没给
        assertEquals(380.0, balance.used, 0.01)
        assertEquals(2, balance.packageCount)

        // 两个包都有剩余且未过期 → 按到期时间升序，免费包（5 天）在前
        assertEquals(freeCode, balance.packages[0].packageCode)
        assertEquals(paidCode, balance.packages[1].packageCode)
        // 付费包的到期时间取「还有额度」的那条（20 天），而不是已用完的 10 天
        val expected = WorkBuddyCredits.parseTimestampMs(paidLater)!!
        assertEquals(expected, balance.packages[1].expireAt!!)
        assertTrue(!balance.packages[1].expired)
    }

    @Test
    fun `全部用完的包用最早未过期记录兜底`() = runBlocking {
        val code = "TCACA_code_008_cfWoLwvjU4"
        routes["/billing/meter/get-user-resource-summary"] = 200 to
            """{"code":0,"data":{"Packages":[{"PackageCode":"$code","CycleTotalCapacity":500,"CycleRemainCapacity":0,"CycleUsedCapacity":500}]}}"""
        val first = daysFromNow(3)
        routes["/billing/meter/get-user-resource-free-packages"] = 200 to
            """
            {"code":0,"data":{"Accounts":[
              {"PackageCode":"$code","DeductionEndTime":"$first","CycleCapacityRemain":0},
              {"PackageCode":"$code","DeductionEndTime":"${daysFromNow(8)}","CycleCapacityRemain":0}
            ]}}
            """.trimIndent()
        routes["/billing/meter/get-user-resource-paid-packages"] = 200 to """{"code":0,"data":{"Accounts":[]}}"""

        val balance = credits().balance(account())
        assertEquals(1, balance.packages.size)
        // 主档（有剩余）为空 → 回落到最早未过期，而不是"长期有效"
        assertEquals(WorkBuddyCredits.parseTimestampMs(first)!!, balance.packages[0].expireAt!!)
        assertEquals(0.0, balance.packages[0].remaining, 0.01)
    }

    @Test
    fun `明细接口失败不影响余额展示`() = runBlocking {
        routes["/billing/meter/get-user-resource-summary"] = 200 to
            """{"code":0,"data":{"Packages":[{"PackageCode":"TCACA_code_007_nzdH5h4Nl0","CycleTotalCapacity":10,"CycleRemainCapacity":7}]}}"""
        // 两个明细都 500：到期时间留空（长期有效），余额照常
        routes["/billing/meter/get-user-resource-paid-packages"] = 500 to """{"code":500,"msg":"boom"}"""
        routes["/billing/meter/get-user-resource-free-packages"] = 500 to """{"code":500,"msg":"boom"}"""

        val balance = credits().balance(account())
        assertEquals(10.0, balance.total, 0.01)
        assertEquals(7.0, balance.remaining, 0.01)
        assertNull(balance.packages[0].expireAt)
        assertTrue(!balance.packages[0].expired)
    }

    @Test
    fun `标量形状兜底`() = runBlocking {
        routes["/billing/meter/get-user-resource-summary"] = 200 to
            """{"code":0,"data":{"remaining":42,"total":100}}"""
        routes["/billing/meter/get-user-resource-paid-packages"] = 200 to """{"code":0,"data":{"Accounts":[]}}"""
        routes["/billing/meter/get-user-resource-free-packages"] = 200 to """{"code":0,"data":{"Accounts":[]}}"""

        val balance = credits().balance(account())
        assertEquals(100.0, balance.total, 0.01)
        assertEquals(42.0, balance.remaining, 0.01)
        assertEquals(58.0, balance.used, 0.01)
        assertEquals(0, balance.packageCount)
    }

    @Test
    fun `没有可识别字段时报错而不是显示 0`() = runBlocking {
        routes["/billing/meter/get-user-resource-summary"] = 200 to """{"code":0,"data":{"foo":1}}"""
        try {
            credits().balance(account())
            fail("应当抛出可读错误")
        } catch (e: Exception) {
            assertTrue(e.message!!.contains("余额"))
        }
    }

    @Test
    fun `401 标记为凭据失效`() = runBlocking {
        routes["/billing/meter/get-user-resource-summary"] = 401 to """{"code":401,"msg":"登录已过期"}"""
        try {
            credits().balance(account())
            fail("应当抛出凭据失效")
        } catch (e: WorkBuddyCredits.CreditException) {
            assertTrue(e.unauthorized)
            assertEquals("登录已过期", e.message)
        }
    }

    @Test
    fun `业务码非 0 时透出官方文案`() = runBlocking {
        routes["/billing/meter/get-user-resource-summary"] = 200 to """{"code":10086,"msg":"账号受限"}"""
        try {
            credits().balance(account())
            fail("应当抛出业务错误")
        } catch (e: WorkBuddyCredits.CreditException) {
            assertEquals("账号受限", e.message)
        }
    }

    @Test
    fun `缺 uid 不发起请求`() = runBlocking {
        try {
            credits().balance(account(uid = ""))
            fail("应当抛出可读错误")
        } catch (e: Exception) {
            assertTrue(e.message!!.contains("uid"))
        }
        assertTrue(hits.isEmpty())
    }

    // ==================== 逐笔明细 ====================

    @Test
    fun `明细按到期时间升序并标记已过期`() = runBlocking {
        val code = "TCACA_code_007_nzdH5h4Nl0"
        routes["/billing/meter/get-user-resource-paid-packages"] = 200 to """{"code":0,"data":{"Accounts":[]}}"""
        val later = daysFromNow(20)
        val past = daysFromNow(-2)
        routes["/billing/meter/get-user-resource-free-packages"] = 200 to
            """
            {"code":0,"data":{"Accounts":[
              {"PackageCode":"$code","DeductionEndTime":"$later","CycleCapacitySize":10,"CycleCapacityUsed":0},
              {"PackageCode":"$code","DeductionEndTime":"$past","CycleCapacitySize":10,"CycleCapacityUsed":10}
            ]}}
            """.trimIndent()

        val records = credits().records(account())
        assertEquals(2, records.size)
        // 已过期的（-2 天）排前面：到期时间升序
        assertTrue(records[0].expired)
        assertTrue(!records[1].expired)
        assertEquals(10.0, records[0].used, 0.01)
        assertEquals(0.0, records[0].remaining, 0.01)
        assertEquals(10.0, records[1].remaining, 0.01)
    }

    @Test
    fun `两侧明细都失败时抛出原因而不是空列表`() = runBlocking {
        routes["/billing/meter/get-user-resource-paid-packages"] = 500 to """{"code":500,"msg":"boom"}"""
        routes["/billing/meter/get-user-resource-free-packages"] = 500 to """{"code":500,"msg":"boom"}"""
        try {
            credits().records(account())
            fail("应当抛出可读错误")
        } catch (e: Exception) {
            assertTrue(e.message!!.contains("明细"))
        }
    }
}
