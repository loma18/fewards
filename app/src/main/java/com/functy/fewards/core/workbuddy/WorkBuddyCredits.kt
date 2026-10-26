package com.functy.fewards.core.workbuddy

import com.functy.fewards.data.repository.AccountRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * WorkBuddy 积分查询（余额 + 积分包到期 + 逐笔明细）。
 *
 * 移植自 workbuddy-console 的 `server/workbuddy/credits.mjs`，判据逐条对齐：
 *
 * | 接口 | 用途 |
 * | --- | --- |
 * | `/billing/meter/get-user-resource-summary` | **权威数额**：每包周期总额/剩余/已用，但不含任何到期字段 |
 * | `/billing/meter/get-user-resource-paid-packages` | 付费包明细，只用来补**到期时间** |
 * | `/billing/meter/get-user-resource-free-packages` | 免费包明细，同上 |
 *
 * ## 几条踩过的坑（改动前先读）
 *
 * - **明细不参与求和**：明细是逐笔成交记录，同一商品码有几十上百条历史，直接相加会和
 *   汇总的周期口径重复计算。汇总的 remaining 才是当前真实余额。
 * - **到期时间取「还有额度的那批里最早会过期的一笔」**：只筛"未过期"不够——实测
 *   平台奖励积分 63 笔未过期记录全部已用完，真正有额度的只有 1 笔（2027/4/9）。
 *   取最早那笔已用完的 10/17 会让卡片显示误导性的到期日。
 * - **`DeductionEndTime` 可能是「周期结束 + 整 30 天」的长期占位**，判断必须用 `>=`
 *   而不是 `>`，否则整整 30 天的占位值漏判，到期时间普遍多算一个月。
 * - **不能带 `SlicePeriodStartTime`**：带当天切片会漏掉月度套餐包的记录，
 *   导致「套餐基础积分」显示成长期有效。
 */
class WorkBuddyCredits(
    private val client: OkHttpClient,
    /** 官方 API 根。默认国内版；测试注入本地地址，生产不要改 */
    private val origin: String = DEFAULT_ORIGIN,
) {

    companion object {
        const val DEFAULT_ORIGIN = "https://www.codebuddy.cn"
        private const val PATH_SUMMARY = "/billing/meter/get-user-resource-summary"
        private const val PATH_PAID = "/billing/meter/get-user-resource-paid-packages"
        private const val PATH_FREE = "/billing/meter/get-user-resource-free-packages"

        /** 临近到期窗口：7 天内标黄 */
        private const val EXPIRING_SOON_DAYS = 7

        /** 距现在超过该天数视为长期有效（官方占位值） */
        private const val FAR_FUTURE_EXPIRY_DAYS = 365

        /** `DeductionEndTime` 比 `CycleEndTime` 晚这么多天时改用后者（视为长期占位） */
        private const val EXPIRY_CYCLE_OVERRIDE_DAYS = 30

        /** 单页 200 条，与官方客户端一致 */
        private const val PAGE_SIZE = 200

        /** 单账号明细上限，防止裂变包账号把列表撑到几千行 */
        private const val DETAILS_MAX_RECORDS = 500

        private const val DAY_MS = 24L * 3600 * 1000

        /** 官方把"凭据失效"写在 msg 里（而非 HTTP 状态）时也要能识别 */
        private val CREDENTIAL_ERROR_RE = Regex("401|unauthorized|未授权|登录|token", RegexOption.IGNORE_CASE)

        private val jsonMedia = "application/json; charset=utf-8".toMediaType()

        /**
         * 商品码 → 官方包名。
         * 来源：官方客户端 asar 内的 package-name-resolver.ts + 实机截图比对（2026-10）。
         * 未登记的商品码回落 `PackageName` → `PackageCode`，不会漏展示。
         */
        private val PACKAGE_NAMES = mapOf(
            "TCACA_code_001_PqouKr6QWV" to "CodeBuddy 个人体验版",
            "TCACA_code_002_AkiJS3ZHF5" to "套餐基础积分",
            "TCACA_code_003_FAnt7lcmRT" to "CodeBuddy 个人标准版",
            "TCACA_code_005_maRGyrHhw1" to "套餐基础积分",
            "TCACA_code_006_DbXS0lrypC" to "CodeBuddy 个人体验版",
            "TCACA_code_007_nzdH5h4Nl0" to "平台奖励积分",
            "TCACA_code_008_cfWoLwvjU4" to "套餐基础积分",
            "TCACA_code_009_0XmEQc2xOf" to "购买积分",
            "TCACA_code_023_4xbGhMrE6q" to "套餐基础积分",
            "TCACA_code_026_BaESVICNoi" to "套餐基础积分",
            "TCACA_code_027_0FCGVA6vSa" to "套餐基础积分",
            "TCACA_code_028_NtpWi0jzXs" to "平台奖励积分",
            "TCACA_code_029_6wCGEWquYy" to "平台奖励积分",
            "TCACA_code_030_BjSt89qTvr" to "平台奖励积分",
            "TCACA_code_035_ArVxJcGDsm" to "套餐基础积分",
            "TCACA_code_036_lupO5WgNdG" to "平台奖励积分",
            "TCACA_code_037_WxOD3MpI2o" to "平台奖励积分",
            "TCACA_code_038_OhvqZtiPKr" to "购买积分",
            "TCACA_code_039_KRcQj7wUat" to "套餐基础积分",
            "TCACA_code_040_mi9rCYg46x" to "套餐基础积分",
        )

        /** 商品码清单与官方客户端 PAID/FREE_PACKAGE_CODES 取并集；多带不存在的码无副作用 */
        private val PAID_CODES = listOf(
            "TCACA_code_002_AkiJS3ZHF5",
            "TCACA_code_023_4xbGhMrE6q",
            "TCACA_code_026_BaESVICNoi",
            "TCACA_code_027_0FCGVA6vSa",
            "TCACA_code_009_0XmEQc2xOf",
            "TCACA_code_038_OhvqZtiPKr",
            "TCACA_code_003_FAnt7lcmRT",
            "TCACA_code_036_lupO5WgNdG",
        )

        private val FREE_CODES = listOf(
            "TCACA_code_008_cfWoLwvjU4",
            "TCACA_code_007_nzdH5h4Nl0",
            "TCACA_code_028_NtpWi0jzXs",
            "TCACA_code_029_6wCGEWquYy",
            "TCACA_code_030_BjSt89qTvr",
            "TCACA_code_001_PqouKr6QWV",
            "TCACA_code_006_DbXS0lrypC",
            "TCACA_code_035_ArVxJcGDsm",
            "TCACA_code_037_WxOD3MpI2o",
            "TCACA_code_039_KRcQj7wUat",
            "TCACA_code_040_mi9rCYg46x",
        )

        /** 官方包名：商品码命中映射用映射名，否则回落 PackageName → PackageCode */
        internal fun packageName(raw: JSONObject?): String {
            val code = firstValue(raw, "PackageCode", "packageCode")?.toString()?.trim().orEmpty()
            val name = firstValue(raw, "PackageName", "packageName")?.toString()?.trim().orEmpty()
            PACKAGE_NAMES[code]?.let { return it }
            return name.ifEmpty { code.ifEmpty { "积分包" } }
        }

        /**
         * 解析单个资源包的到期时刻（毫秒）。
         *
         * 优先 `DeductionEndTime` / `ExpiredTime`；仅当 `CycleEndTime` 比其早 **≥30 天**时
         * 改用周期结束时间（前者是长期占位）。最终值距现在超过 365 天视为长期有效。
         */
        internal fun resolveExpireAt(raw: JSONObject?, now: Long): Long? {
            val deductionEnd = parseTimestampMs(
                firstValue(raw, "DeductionEndTime", "deductionEndTime", "ExpiredTime", "expiredTime")
            )
            val cycleEnd = parseTimestampMs(firstValue(raw, "CycleEndTime", "cycleEndTime"))
            val overrideMs = EXPIRY_CYCLE_OVERRIDE_DAYS * DAY_MS
            val expireAt = when {
                // ⚠️ 必须是 >=：实测 DeductionEndTime 恰好等于 CycleEndTime + 整 30 天
                deductionEnd != null && cycleEnd != null && deductionEnd - cycleEnd >= overrideMs -> cycleEnd
                deductionEnd != null -> deductionEnd
                else -> cycleEnd
            } ?: return null
            return if (expireAt - now <= FAR_FUTURE_EXPIRY_DAYS * DAY_MS) expireAt else null
        }

        /**
         * 时间字段 → 毫秒时间戳。兼容毫秒/秒级数字、"YYYY-MM-DD HH:mm:ss"（本地时间）、
         * 以及可被官方解析的其他字符串。无法解析返回 null。
         */
        internal fun parseTimestampMs(value: Any?): Long? {
            when (value) {
                null -> return null
                is Number -> {
                    val n = value.toDouble()
                    if (!n.isFinite()) return null
                    val l = n.toLong()
                    return if (l < 1e11) l * 1000 else l
                }
            }
            val text = value.toString().trim()
            if (text.isEmpty()) return null
            text.toDoubleOrNull()?.let { n ->
                if (!n.isFinite()) return null
                val l = n.toLong()
                return if (l < 1e11) l * 1000 else l
            }
            // 明细接口的到期字段是本地时间字符串，不能交给模糊解析赌时区
            val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
            fmt.isLenient = false
            val local = runCatching { fmt.parse(text)?.time }.getOrNull()
            if (local != null) return local
            return runCatching { java.time.OffsetDateTime.parse(text).toInstant().toEpochMilli() }.getOrNull()
        }

        /** 取第一个有值的字段（官方大小写变体都要兼容） */
        internal fun firstValue(raw: JSONObject?, vararg keys: String): Any? {
            if (raw == null) return null
            for (k in keys) {
                if (!raw.has(k) || raw.isNull(k)) continue
                val v = raw.opt(k) ?: continue
                if (v is String && v.trim().isEmpty()) continue
                return v
            }
            return null
        }

        internal fun num(v: Any?): Double = when (v) {
            is Number -> v.toDouble()
            is String -> v.trim().toDoubleOrNull() ?: 0.0
            else -> 0.0
        }

        internal fun round2(n: Double): Double = Math.round(num(n) * 100) / 100.0

        /** 千分位 + 最多两位小数 */
        fun format(v: Double): String {
            val rounded = round2(v)
            return if (rounded == Math.floor(rounded)) {
                String.format(Locale.US, "%,.0f", rounded)
            } else {
                String.format(Locale.US, "%,.2f", rounded)
            }
        }
    }

    /** 汇总里的一个积分包 */
    data class CreditPackage(
        val packageCode: String,
        val name: String,
        val total: Double,
        val remaining: Double,
        val used: Double,
        /** 到期时刻（毫秒）；null = 长期有效 */
        val expireAt: Long?,
        val expired: Boolean,
        val expiringSoon: Boolean,
    )

    /** 账号余额汇总 */
    data class Balance(
        val total: Double,
        val remaining: Double,
        val used: Double,
        val packageCount: Int,
        val unit: String,
        val packages: List<CreditPackage>,
        val updatedAt: Long,
    )

    /** 逐笔明细里的一条发放记录 */
    data class CreditRecord(
        val name: String,
        val packageCode: String,
        /** 到期时刻（毫秒）；null = 长期有效 */
        val expireAt: Long?,
        val total: Double,
        val used: Double,
        val remaining: Double,
        val expired: Boolean,
    )

    private fun authHeaders(account: AccountRepository.WorkBuddyAccount): Map<String, String> {
        val headers = mutableMapOf(
            "Authorization" to "Bearer ${account.token.trim()}",
            "Accept" to "application/json",
            "Content-Type" to "application/json",
            "X-Client-Platform" to "web",
            "Origin" to origin,
            "Referer" to "$origin/profile/plans-usage",
        )
        if (account.uid.isNotEmpty()) headers["X-User-Id"] = account.uid
        if (account.enterpriseId.isNotEmpty()) {
            headers["X-Enterprise-Id"] = account.enterpriseId
            headers["X-Tenant-Id"] = account.enterpriseId
        }
        return headers
    }

    /** 官方统一响应包 `{ code, data, msg }`；code 非 0 视为业务失败 */
    private fun unwrap(json: JSONObject): JSONObject {
        val code = json.optInt("code", json.optInt("Code", 0))
        if (code != 0) {
            val msg = json.optString("msg").ifEmpty {
                json.optString("message").ifEmpty {
                    json.optString("Message").ifEmpty { "错误码 $code" }
                }
            }
            throw CreditException(msg, unauthorized = code == 401 || CREDENTIAL_ERROR_RE.containsMatchIn(msg))
        }
        return json.optJSONObject("data") ?: json.optJSONObject("Data") ?: json
    }

    private fun post(
        account: AccountRepository.WorkBuddyAccount,
        path: String,
        body: JSONObject,
    ): JSONObject = postUrl(account, origin + path, body)

    private fun postUrl(
        account: AccountRepository.WorkBuddyAccount,
        url: String,
        body: JSONObject,
    ): JSONObject {
        val builder = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody(jsonMedia))
        authHeaders(account).forEach { (k, v) -> builder.header(k, v) }
        client.newCall(builder.build()).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            val json = runCatching { JSONObject(text) }.getOrNull()
                ?: throw CreditException(
                    "接口返回非 JSON（HTTP ${resp.code}）",
                    // 401/403 且响应不是 JSON 时，同样要能识别成"凭据失效"
                    unauthorized = resp.code == 401 || resp.code == 403,
                )
            if (!resp.isSuccessful && (resp.code == 401 || resp.code == 403)) {
                val msg = json.optString("msg").ifEmpty {
                    json.optString("message").ifEmpty { json.optString("Message").ifEmpty { "HTTP ${resp.code}" } }
                }
                throw CreditException(msg, unauthorized = true)
            }
            return json
        }
    }

    // ==================== 余额 ====================

    /** 查询单个账号的积分余额（含各包到期时间）。 */
    suspend fun balance(account: AccountRepository.WorkBuddyAccount): Balance = withContext(Dispatchers.IO) {
        if (account.uid.isEmpty()) throw CreditException("该账号缺少 uid，无法查询积分")
        val data = unwrap(post(account, PATH_SUMMARY, JSONObject()))
        val packages = data.optJSONArray("Packages") ?: data.optJSONArray("packages")
        val now = System.currentTimeMillis()

        if (packages != null && packages.length() > 0) {
            var total = 0.0
            var remaining = 0.0
            var used = 0.0
            val details = ArrayList<CreditPackage>(packages.length())
            for (i in 0 until packages.length()) {
                val p = packages.optJSONObject(i) ?: continue
                val t = num(firstValue(p, "CycleTotalCapacity", "totalCapacity"))
                val r = num(firstValue(p, "CycleRemainCapacity", "remainCapacity"))
                val u = num(firstValue(p, "CycleUsedCapacity", "usedCapacity"))
                total += t
                remaining += r
                used += u
                details += CreditPackage(
                    packageCode = firstValue(p, "PackageCode", "packageCode")?.toString().orEmpty(),
                    name = packageName(p),
                    total = round2(t),
                    remaining = round2(r),
                    used = round2(u),
                    expireAt = null, // 下面用明细接口补
                    expired = false,
                    expiringSoon = false,
                )
            }
            // 剩余 + 已用 与总额对不上时（部分包没有 used 字段），以总额减剩余兜底
            if (used <= 0 && total > remaining) used = total - remaining

            // 明细接口补到期时间；失败则所有包保持"长期有效"，不影响余额展示
            val expiryMap = runCatching { expiryMap(account) }.getOrDefault(emptyMap())
            for ((index, d) in details.withIndex()) {
                val expireAt = expiryMap[d.packageCode]
                details[index] = d.copy(
                    expireAt = expireAt,
                    expired = expireAt != null && expireAt <= now,
                    expiringSoon = expireAt != null && expireAt > now &&
                        expireAt - now <= EXPIRING_SOON_DAYS * DAY_MS,
                )
            }

            // 有剩余且未到期的排前面，同序按到期时间升序
            details.sortWith(
                compareBy(
                    { if (it.remaining > 0 && !it.expired) 0 else 1 },
                    { it.expireAt ?: Long.MAX_VALUE },
                )
            )

            return@withContext Balance(
                total = round2(total),
                remaining = round2(remaining),
                used = round2(used),
                packageCount = packages.length(),
                unit = packages.optJSONObject(0)?.optString("CapacityUnit")?.ifEmpty { "credits" } ?: "credits",
                packages = details,
                updatedAt = now,
            )
        }

        // 标量形状兜底
        fun pick(vararg keys: String): Double? {
            for (k in keys) {
                if (!data.has(k) || data.isNull(k)) continue
                val raw = data.opt(k) ?: continue
                if (raw is String && raw.isEmpty()) continue
                val n = num(raw)
                if (n.isFinite()) return n
            }
            return null
        }
        val remaining = pick("remaining", "remain", "remainCredit", "available", "availableCredit")
        val total = pick("total", "totalCredit", "quota")
        if (remaining == null && total == null) throw CreditException("接口返回中没有可识别的余额字段")
        val rem = remaining ?: 0.0
        val tot = total ?: 0.0
        return@withContext Balance(
            total = round2(tot),
            remaining = round2(rem),
            used = round2(if (tot > 0) maxOf(0.0, tot - rem) else 0.0),
            packageCount = 0,
            unit = "credits",
            packages = emptyList(),
            updatedAt = now,
        )
    }

    /**
     * 拉取付费 / 免费两类积分包明细，返回「商品码 → 该码下最早的有效到期时刻」。
     *
     * 对汇总里的一个包来说，「到期时间」应当是**还有额度的那批里最早会过期的一笔**。
     * 主档只认「未过期且还有剩余」；该包全部用完时（如 0/500 的套餐基础积分）
     * 兜底回落到「最早一笔未过期」，让卡片仍有个可参考的周期刷新时间。
     */
    private suspend fun expiryMap(account: AccountRepository.WorkBuddyAccount): Map<String, Long> =
        coroutineScope {
            val paid = async { runCatching { post(account, PATH_PAID, packageBody(PAID_CODES)) } }
            val free = async { runCatching { post(account, PATH_FREE, packageBody(FREE_CODES)) } }
            val now = System.currentTimeMillis()
            val primary = HashMap<String, Long>()
            val fallback = HashMap<String, Long>()

            fun consider(list: JSONArray?) {
                if (list == null) return
                for (i in 0 until list.length()) {
                    val raw = list.optJSONObject(i) ?: continue
                    val code = firstValue(raw, "PackageCode", "packageCode")?.toString().orEmpty()
                    if (code.isEmpty()) continue
                    val expireAt = resolveExpireAt(raw, now) ?: continue
                    if (expireAt <= now) continue // 已过期 / 长期有效不参与
                    val prevFallback = fallback[code]
                    if (prevFallback == null || expireAt < prevFallback) fallback[code] = expireAt
                    // 剩余额度：周期口径优先，单笔口径兜底（两者都缺时回落为 0）
                    val remain = num(
                        firstValue(
                            raw,
                            "CycleCapacityRemain", "cycleCapacityRemain",
                            "CapacityRemain", "capacityRemain",
                        )
                    )
                    if (remain > 0) {
                        val prev = primary[code]
                        if (prev == null || expireAt < prev) primary[code] = expireAt
                    }
                }
            }

            for (res in listOf(paid.await(), free.await())) {
                val json = res.getOrNull() ?: continue
                val data = runCatching { unwrap(json) }.getOrNull() ?: continue
                consider(data.optJSONArray("Accounts") ?: data.optJSONArray("accounts"))
            }
            for ((code, expireAt) in fallback) {
                if (!primary.containsKey(code)) primary[code] = expireAt
            }
            primary
        }

    private fun packageBody(codes: List<String>): JSONObject = JSONObject()
        .put("PageNumber", 1)
        .put("PageSize", PAGE_SIZE)
        .put("Status", JSONArray().put(0).put(3))
        .put("PackageCodes", JSONArray(codes))
        .put("NeedRenewInfo", true)

    // ==================== 逐笔明细 ====================

    /**
     * 单账号逐笔积分明细（官方客户端「积分明细」弹窗的数据源）。
     *
     * 与 [balance] 的区别：汇总只给「每类包一个总数」，明细返回每一笔发放记录
     * （各自的到期时间与用量）。这里**不过滤**已用完/已过期的记录——「已过期」分组依赖它们。
     */
    suspend fun records(account: AccountRepository.WorkBuddyAccount): List<CreditRecord> =
        withContext(Dispatchers.IO) {
            if (account.uid.isEmpty()) throw CreditException("该账号缺少 uid，无法查询积分明细")
            val now = System.currentTimeMillis()
            coroutineScope {
                val paid = async { runCatching { post(account, PATH_PAID, packageBody(PAID_CODES)) } }
                val free = async { runCatching { post(account, PATH_FREE, packageBody(FREE_CODES)) } }
                val out = ArrayList<CreditRecord>()
                var failures = 0
                for (res in listOf(paid.await(), free.await())) {
                    val json = res.getOrNull()
                    if (json == null) {
                        failures++
                        continue
                    }
                    // 单侧失败不影响另一侧
                    val data = runCatching { unwrap(json) }.getOrElse { failures++; null } ?: continue
                    val list = data.optJSONArray("Accounts") ?: data.optJSONArray("accounts") ?: continue
                    for (i in 0 until list.length()) {
                        val raw = list.optJSONObject(i) ?: continue
                        // 周期口径优先（裂变包按周期切片），单笔口径兜底
                        val cycleTotal = num(firstValue(raw, "CycleCapacitySize", "cycleCapacitySize"))
                        val cycleUsed = num(firstValue(raw, "CycleCapacityUsed", "cycleCapacityUsed"))
                        val flatTotal = num(firstValue(raw, "CapacitySize", "capacitySize"))
                        val flatUsed = num(firstValue(raw, "CapacityUsed", "capacityUsed"))
                        val total = if (cycleTotal > 0) cycleTotal else flatTotal
                        val used = if (cycleUsed > 0) cycleUsed else flatUsed
                        val expireAt = resolveExpireAt(raw, now)
                        out += CreditRecord(
                            name = packageName(raw),
                            packageCode = firstValue(raw, "PackageCode", "packageCode")?.toString().orEmpty(),
                            expireAt = expireAt,
                            total = round2(total),
                            used = round2(used),
                            remaining = round2(maxOf(0.0, total - used)),
                            expired = expireAt != null && expireAt <= now,
                        )
                    }
                }
                if (out.isEmpty() && failures == 2) {
                    // 两侧都失败：抛出明确原因，而不是给用户一个"空明细"
                    throw CreditException("明细接口查询失败")
                }
                out.sortWith(compareBy { it.expireAt ?: Long.MAX_VALUE })
                return@coroutineScope out.take(DETAILS_MAX_RECORDS)
            }
        }

    /** 查询失败。[unauthorized] 为真表示服务端明确拒绝了这个 token（凭据失效，只能重新登录） */
    class CreditException(message: String, val unauthorized: Boolean = false) : Exception(message)
}
