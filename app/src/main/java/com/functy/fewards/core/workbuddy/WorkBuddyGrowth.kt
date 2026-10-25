package com.functy.fewards.core.workbuddy

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import com.functy.fewards.data.repository.AccountRepository
import java.util.Locale
import java.util.UUID

/**
 * WorkBuddy 成长中心自动化（国内版专有）。
 *
 * 移植自 workbuddy-console 的 `server/workbuddy/growth.mjs`，端点与判据逐条对齐：
 *
 * | 模块 | 端点 | 关键判据 |
 * | --- | --- | --- |
 * | Buddy 旅行 | GET `/buddy/travel/status`、POST `/claim`、GET `/config`、POST `/depart` | `arrived` 才能领；领成功才允许再出发 |
 * | 任务 | GET `/tasks`、POST `/tasks/accept`、POST `/tasks/{code}/claim` | 接单用复数 `task_codes`；只有 `completed` 才发奖 |
 * | 断登补签 | GET `/streak`、GET `/heatmap`、POST `/makeup-cards/use` | 只补「当月 + 今天之前 + score==0 + 未补过」，每轮最多 1 张 |
 * | 连登兑换 | GET `/redeem/summary`、POST `/redeem` | 403「天数不足」是常态，必须先于 401/403 中止判断 |
 * | 开盲盒 | GET `/lottery/chances`、POST `/lottery/draw` | 必须带 `client_token`，缺了直接 400 |
 * | Buddy 盲盒 | GET `/buddy/quota`、POST `/buddy/open` | `affordable` 与 `max_open_count` 取小 |
 *
 * ## 几条踩过的坑（改动前先读）
 *
 * - **写操作不重试**：claim / depart / redeem / draw / open / use 在服务端处理完成后才超时时，
 *   重试会重复提交。
 * - **兑换实发字段带 `_granted` 后缀**（credit_granted / energy_granted / cards_granted /
 *   chances_granted），直接读 `credit` 恒为空，会把所得漏计。
 * - **补登候选不能看 `makeup_dates`**：那是已补登历史，不是待办清单。
 * - **一段失败不影响其余**：各模块独立 try，401/403 才中止整轮。
 */
class WorkBuddyGrowth(private val client: OkHttpClient) {

    companion object {
        private const val ORIGIN = "https://www.codebuddy.cn"
        private const val BASE = "$ORIGIN/v2/activity/growth"

        /** 补登候选最早日期：成长中心上线日（与官方 H5 口径一致） */
        private const val LAUNCH_FLOOR = "2026-06-17"

        /** 每轮最多用掉一张补登卡；断登多天时分轮消化 */
        private const val MAKEUP_MAX_PER_RUN = 1

        private val DATE_RE = Regex("^\\d{4}-\\d{2}-\\d{2}$")

        private val jsonMedia = "application/json; charset=utf-8".toMediaType()

        /** 连登兑换三档：(档位标识, summary 状态字段, 展示名, 兜底天数) */
        private data class Tier(val tier: String, val statusKey: String, val label: String, val days: Int)

        private val TIERS = listOf(
            Tier("7d", "starter_status", "入门", 7),
            Tier("14d", "advanced_status", "进阶", 14),
            Tier("28d", "legendary_status", "巅峰", 28),
        )

        /** 挖不到实发字段时的官方文案（2026-09 活动版本） */
        private val REDEEM_FALLBACK = mapOf(
            "7d" to "+2 能量 +1 补登卡 +1 次抽奖",
            "14d" to "+50 积分 +3 能量 +1 补登卡 +1 次抽奖",
            "28d" to "+150 积分 +5 能量 +1 补登卡 +1 次抽奖",
        )

        private fun clientToken(): String = "u-${UUID.randomUUID()}"
    private fun dig(obj: JSONObject?, key: String): Any? {
        if (obj == null) return null
        if (obj.has(key) && !obj.isNull(key)) return obj.opt(key)
        for (k in listOf("data", "result", "resp", "response")) {
            val inner = obj.optJSONObject(k) ?: continue
            val r = dig(inner, key)
            if (r != null) return r
        }
        return null
    }

    private fun digObj(obj: JSONObject?, key: String): JSONObject? = dig(obj, key) as? JSONObject
    private fun digArr(obj: JSONObject?, key: String): JSONArray? = dig(obj, key) as? JSONArray

    private fun digStr(obj: JSONObject?, key: String): String? =
        dig(obj, key)?.let { if (it is String) it else it.toString() }

    /** 字符串数字安全转 int；转不动返回 default */
    private fun asInt(v: Any?, def: Int = 0): Int = when (v) {
        is Number -> v.toInt()
        is Boolean -> if (v) 1 else 0
        is String -> v.trim().toIntOrNull()
            ?: v.trim().toDoubleOrNull()?.toInt()
            ?: def
        else -> def
    }

    private fun digInt(obj: JSONObject?, key: String, def: Int = 0): Int = asInt(dig(obj, key), def)

    /** 优先取实发数值，挖不到才用 fallback（任务列表配置值 ≠ 实发） */
    private fun firstInt(body: JSONObject?, key: String, fallback: Any?): Int {
        val v = dig(body, key)
        return if (v != null) asInt(v) else asInt(fallback)
    }
    }

    /** 一次请求的结果；网络异常不抛，统一塞进 networkError 由上层归类 */
    private data class Resp(
        val status: Int,
        val json: JSONObject?,
        val networkError: String? = null,
    ) {
        val ok: Boolean get() = networkError == null && status in 200..299
        val unauthorized: Boolean get() = status == 401 || status == 403
        fun msg(): String =
            networkError ?: digStr(json, "msg") ?: digStr(json, "message") ?: "HTTP $status"
    }

    /** 一轮执行的结果 */
    data class GrowthResult(
        val ok: Boolean,
        /** 一句话总览，可直接展示 */
        val report: String,
        /** 逐条明细（领到了什么、哪里失败） */
        val parts: List<String>,
        val creditsGained: Int,
        val energy: Int?,
        val streakDays: Int?,
        val failures: Int,
        val hardFailures: Int,
        /** 既没领到东西也没出错（Buddy 在路上 / 名额用完） */
        val idle: Boolean,
        val unauthorized: Boolean = false,
    )

    // ==================== JSON 工具 ====================

    /** 在可能被 data/result/resp/response 包裹的响应里找字段 */

    // ==================== HTTP ====================

    private fun headers(account: AccountRepository.WorkBuddyAccount): Map<String, String> = buildMap {
        put("Authorization", "Bearer ${account.token}")
        put("Accept", "application/json")
        put("Content-Type", "application/json")
        put("X-Client-Platform", "web")
        put("Origin", ORIGIN)
        put("Referer", "$ORIGIN/profile/plans-usage")
        if (account.uid.isNotEmpty()) put("X-User-Id", account.uid)
        if (account.enterpriseId.isNotEmpty()) {
            put("X-Enterprise-Id", account.enterpriseId)
            put("X-Tenant-Id", account.enterpriseId)
        }
    }

    private suspend fun request(
        method: String,
        url: String,
        account: AccountRepository.WorkBuddyAccount,
        body: JSONObject? = null,
    ): Resp = withContext(Dispatchers.IO) {
        try {
            val builder = Request.Builder().url(url)
            headers(account).forEach { (k, v) -> builder.header(k, v) }
            if (method == "POST") {
                builder.post((body ?: JSONObject()).toString().toRequestBody(jsonMedia))
            } else {
                builder.get()
            }
            client.newCall(builder.build()).execute().use { resp ->
                val text = resp.body?.string() ?: ""
                Resp(resp.code, runCatching { JSONObject(text) }.getOrNull(), null)
            }
        } catch (e: Exception) {
            Resp(0, null, e.message ?: "网络不可达")
        }
    }

    private suspend fun get(url: String, account: AccountRepository.WorkBuddyAccount) =
        request("GET", url, account)

    private suspend fun post(url: String, account: AccountRepository.WorkBuddyAccount, body: JSONObject = JSONObject()) =
        request("POST", url, account, body)

    // ==================== 纯函数：补登候选 ====================

    /**
     * 从活跃日历选断登日期。
     *
     * 口径：当月 + 今天之前 + 活动上线之后 + `score == 0` + 不在已补登历史里。
     * 用服务端 `today.date` 而不是本机日期，避免时区把月初算错。
     * 结构异常直接抛错——不能靠猜测消耗补登卡。
     * 返回 ISO 日期，最近的在前。
     */
    fun makeupCandidates(streakBody: JSONObject?, heatmapBody: JSONObject?): List<String> {
        fun parse(value: String?, what: String): String {
            if (value == null || !DATE_RE.matches(value)) throw IllegalStateException("$what 格式无效")
            return value
        }

        val todayObj = digObj(heatmapBody, "today") ?: throw IllegalStateException("活跃日历缺少服务端日期")
        val today = parse(todayObj.optString("date"), "服务端日期")
        val launch = parse(digStr(streakBody, "launch_date") ?: LAUNCH_FLOOR, "活动上线日期")
        val monthStart = today.substring(0, 7) + "-01"
        // 三者取最大（字典序即日期序）
        val start = listOf(monthStart, launch, LAUNCH_FLOOR).sorted().last()

        val streak = digObj(streakBody, "streak") ?: throw IllegalStateException("连登状态格式无效")
        val madeUpRaw = streak.optJSONArray("makeup_dates") ?: JSONArray()
        val madeUp = HashSet<String>()
        for (i in 0 until madeUpRaw.length()) {
            madeUp.add(parse(madeUpRaw.optString(i), "已补登日期"))
        }

        val cells = digArr(heatmapBody, "cells") ?: throw IllegalStateException("活跃日历缺少日期列表")
        val out = ArrayList<String>()
        for (i in 0 until cells.length()) {
            val cell = cells.optJSONObject(i) ?: throw IllegalStateException("活跃日历记录格式无效")
            val day = parse(cell.optString("date"), "活跃日历日期")
            if (!cell.has("score")) throw IllegalStateException("活跃日历分数无效")
            val score = cell.optInt("score", -1)
            if (score < 0) throw IllegalStateException("活跃日历分数无效")
            if (day >= start && day < today && score == 0 && day !in madeUp) out.add(day)
        }
        return out.sortedDescending()
    }

    /** 余额兼容 `{"makeup_cards":{"balance":2}}` 与 `{"makeup_cards":2}` 两种形状 */
    private fun cardBalance(body: JSONObject?, fallback: Int): Int {
        val v = dig(body, "makeup_cards") ?: return fallback
        return if (v is JSONObject) asInt(v.opt("balance"), fallback) else asInt(v, fallback)
    }

    private fun fmtEta(arriveAt: Any?, serverNow: Any?): String {
        val a = asInt(arriveAt, -1)
        val n = asInt(serverNow, -1)
        if (a < 0 || n < 0) return ""
        val left = a - n
        if (left <= 0) return "，已到达待领取"
        val minutes = left / 60
        return if (minutes < 60) {
            "，约 ${maxOf(1, minutes)} 分钟后回"
        } else {
            // 固定 Locale.US：中文环境下小数分隔符仍是 "."，避免出现 "1，5 小时"
            "，约 ${String.format(Locale.US, "%.1f", left / 3600.0)} 小时后回"
        }
    }

    private fun redeemDesc(body: JSONObject?, tier: String): String {
        val bits = ArrayList<String>()
        digInt(body, "credit_granted").takeIf { it > 0 }?.let { bits.add("+$it 积分") }
        digInt(body, "energy_granted").takeIf { it > 0 }?.let { bits.add("+$it 能量") }
        digInt(body, "cards_granted").takeIf { it > 0 }?.let { bits.add("+$it 补登卡") }
        digInt(body, "chances_granted").takeIf { it > 0 }?.let { bits.add("+$it 次抽奖") }
        val text = if (bits.isNotEmpty()) bits.joinToString(" ") else REDEEM_FALLBACK[tier] ?: "奖励已到账"
        return "（$text）"
    }

    /** 抽奖失败只是"没有次数"——常态，不算故障 */
    private fun isNoChance(msg: String?): Boolean {
        val m = msg?.lowercase() ?: return false
        if (m.isBlank()) return false
        if (m.contains("insufficient") || m.contains("not enough")) {
            return m.contains("chance") || m.contains("balance")
        }
        return m.contains("no chance")
    }

    private fun isUnknownTier(status: Int, body: JSONObject?): Boolean {
        if (status != 400) return false
        val m = digStr(body, "msg")?.lowercase() ?: return false
        return m.contains("tier") && (m.contains("unknown") || m.contains("unsupported") || m.contains("invalid"))
    }

    private fun isTierLocked(status: Int, body: JSONObject?): Boolean =
        status == 403 && (digStr(body, "msg")?.contains("不足") == true)

    private fun isHard(status: Int, networkError: String?) = networkError != null || status >= 500

    // ==================== 主流程 ====================

    /**
     * 对单个账号跑完全部六个模块。
     *
     * `ok=false` 只在中止级问题（无凭据 / 认证拒绝 / 首个请求网络不可达）时返回；
     * 单个模块失败记在 [Result.parts] 与 [Result.failures] 里。
     */
    suspend fun run(account: AccountRepository.WorkBuddyAccount): GrowthResult {
        if (account.token.isBlank()) {
            return GrowthResult(false, "该账号没有可用的 access_token", emptyList(), 0, null, null, 0, 0, false)
        }

        val parts = ArrayList<String>()
        var credits = 0
        var failures = 0
        var hard = 0
        var successes = 0

        fun abort(status: Int): GrowthResult = GrowthResult(
            ok = false,
            report = "成长中心中止：登录态被拒绝（HTTP $status），请刷新凭据或重新扫码",
            parts = parts.toList(),
            creditsGained = credits,
            energy = null,
            streakDays = null,
            failures = failures + 1,
            hardFailures = hard + 1,
            idle = false,
            unauthorized = true,
        )

        // ---- 1. Buddy 旅行：领礼物 + 派出发 ----
        try {
            val s = get("$BASE/buddy/travel/status", account)
            if (s.networkError != null) {
                // 第一个请求就不通：立刻收手，别把后面五个接口全撞一遍
                return GrowthResult(
                    false, "网络不可达，成长中心跳过（${s.networkError}）",
                    parts.toList(), 0, null, null, 1, 1, false
                )
            }
            if (s.unauthorized) return abort(s.status)

            var state = digStr(s.json, "state") ?: ""
            val dailyLimit = dig(s.json, "daily_limit_reached") == true
            if (state == "arrived") {
                val recordId = dig(s.json, "record_id")
                // org.json 的 put 不接受 null：缺字段时传空对象，由服务端按业务规则拒绝
                val claimBody = JSONObject().also { if (recordId != null) it.put("record_id", recordId) }
                val c = post("$BASE/buddy/travel/claim", account, claimBody)
                if (c.unauthorized) return abort(c.status)
                val reward = dig(c.json, "reward_credit")
                if (c.ok && reward != null) {
                    val got = asInt(reward)
                    credits += got
                    parts.add("领旅行礼物 +$got 积分")
                    successes++
                    // 只有领取成功后才允许再出发，否则会覆盖未领的奖励
                    state = "idle"
                } else {
                    parts.add("领旅行礼物失败：${c.msg()}")
                    failures++
                    if (isHard(c.status, c.networkError)) hard++
                }
            }
            if (state == "idle" && dailyLimit) {
                parts.add("今日旅行名额已用完")
            } else if (state == "idle") {
                val c = get("$BASE/buddy/travel/config", account)
                if (c.unauthorized) return abort(c.status)
                val locs = digArr(c.json, "locations")
                val first = locs?.optJSONObject(0)
                if (first != null) {
                    val locId = first.opt("id")
                    val departBody = JSONObject().also { if (locId != null) it.put("location_id", locId) }
                    val d = post("$BASE/buddy/travel/depart", account, departBody)
                    if (d.unauthorized) return abort(d.status)
                    if (d.ok) {
                        val locName = digObj(d.json, "location")?.optString("name")?.ifEmpty { "?" } ?: "?"
                        val dur = dig(d.json, "duration_hours")
                            ?: digObj(d.json, "location")?.opt("duration_hours")
                            ?: "?"
                        parts.add("派 Buddy 去$locName（$dur 小时后回）")
                        successes++
                    } else {
                        parts.add("派 Buddy 失败：${d.msg()}")
                        failures++
                        if (isHard(d.status, d.networkError)) hard++
                    }
                }
            } else if (state == "traveling") {
                val locName = digObj(s.json, "location")?.optString("name")?.ifEmpty { "?" } ?: "?"
                parts.add("Buddy 旅行中（$locName${fmtEta(dig(s.json, "arrive_at"), dig(s.json, "server_now"))}）")
            }
        } catch (e: Exception) {
            parts.add("旅行模块异常（${e.message ?: e}）")
            failures++
            hard++
        }

        // ---- 2. 任务领奖（放在抽奖前：任务送的抽奖机会后面马上能用）----
        try {
            val t = get("$BASE/tasks", account)
            if (t.unauthorized) return abort(t.status)
            if (t.ok) {
                val tasks = digArr(t.json, "tasks") ?: JSONArray()
                val titles = HashMap<String, String>()
                val pending = ArrayList<String>()
                val completed = ArrayList<JSONObject>()
                for (i in 0 until tasks.length()) {
                    val task = tasks.optJSONObject(i) ?: continue
                    val code = task.optString("task_code")
                    if (code.isEmpty()) continue
                    titles[code] = task.optString("title").ifEmpty { code }
                    val locked = task.optBoolean("locked", false)
                    val acceptStatus = task.optString("accept_status")
                    if (!locked && acceptStatus == "not_accepted") pending.add(code)
                    if (!locked && acceptStatus == "completed") completed.add(task)
                }

                // 接单：接口只认复数 task_codes，单数一律 400；分批避免 body 过大
                pending.chunked(20).forEach { batch ->
                    val a = post("$BASE/tasks/accept", account, JSONObject().put("task_codes", JSONArray(batch)))
                    if (a.unauthorized) return abort(a.status)
                    val results = digArr(a.json, "results")
                    if (results != null) {
                        for (i in 0 until results.length()) {
                            val r = results.optJSONObject(i) ?: continue
                            val code = r.optString("task_code")
                            val title = titles[code] ?: code
                            if (r.optString("status") == "error") {
                                parts.add("领取任务「$title」失败：${r.optString("message").ifEmpty { a.msg() }}")
                                failures++
                                if (isHard(a.status, a.networkError)) hard++
                            } else {
                                parts.add("领取任务「$title」（进度开始计）")
                                successes++
                            }
                        }
                    } else if (a.ok) {
                        batch.forEach { parts.add("领取任务「${titles[it] ?: it}」（进度开始计）") }
                        successes += batch.size
                    } else {
                        parts.add("接单失败：${a.msg()}")
                        failures++
                        if (isHard(a.status, a.networkError)) hard++
                    }
                }

                // 领奖：只有 completed 才发奖
                completed.forEach { task ->
                    val code = task.optString("task_code")
                    val title = titles[code] ?: code
                    try {
                        val c = post("$BASE/tasks/${java.net.URLEncoder.encode(code, "UTF-8")}/claim", account)
                        if (c.unauthorized) return abort(c.status)
                        if (c.ok && dig(c.json, "already_claimed") != true) {
                            val rc = firstInt(c.json, "credit", task.opt("reward_credit"))
                            val re = firstInt(c.json, "energy", task.opt("reward_energy"))
                            credits += rc
                            parts.add("领任务奖「$title」+$rc 积分 +$re 能量")
                            successes++
                        } else if (c.ok) {
                            parts.add("任务奖「$title」已领过")
                        } else {
                            parts.add("领任务奖「$title」失败：${c.msg()}")
                            failures++
                            if (isHard(c.status, c.networkError)) hard++
                        }
                    } catch (e: Exception) {
                        parts.add("领任务奖「$title」异常（${e.message ?: e}）")
                        failures++
                        hard++
                    }
                }
            } else {
                parts.add("查任务列表失败：${t.msg()}")
            }
        } catch (e: Exception) {
            parts.add("任务模块异常（${e.message ?: e}）")
            failures++
            hard++
        }

        // ---- 3. 补登卡：断登自动补一张，保住连登（必须在连登兑换之前）----
        var streakBody: JSONObject? = null
        var streakStale = false
        try {
            val m = get("$BASE/streak", account)
            if (m.unauthorized) return abort(m.status)
            if (m.ok) {
                streakBody = m.json
                var cards = cardBalance(m.json, 0)
                if (cards > 0) {
                    val h = get("$BASE/heatmap", account)
                    if (h.unauthorized) return abort(h.status)
                    if (h.ok) {
                        val dates = makeupCandidates(m.json, h.json)
                        val take = dates.take(minOf(cards, MAKEUP_MAX_PER_RUN))
                        var madeUp = 0
                        for (d in take) {
                            val u = post("$BASE/makeup-cards/use", account, JSONObject().put("target_date", d))
                            if (u.unauthorized) return abort(u.status)
                            if (u.ok) {
                                cards--
                                madeUp++
                                streakStale = true
                                // 优先报服务端给的余额，本地递减只是兜底
                                val left = cardBalance(u.json, cards)
                                parts.add("补登 $d（剩 $left 张卡）")
                                cards = maxOf(0, left)
                                successes++
                            } else {
                                val msg = digStr(u.json, "msg") ?: ""
                                if (u.status == 400 && msg.trim().lowercase() == "date is not broken, no makeup needed") {
                                    // 查询后被另一处补上：正常竞争，不刷失败日志
                                    parts.add("$d 已活跃或已补登，无需再次补登")
                                    break
                                }
                                parts.add("补登 $d 失败：${u.msg()}")
                                failures++
                                hard++
                                break
                            }
                        }
                        if (madeUp > 0 && dates.size > madeUp && cards > 0) {
                            parts.add("另有 ${dates.size - madeUp} 天可补、剩 $cards 张卡，下轮继续")
                        }
                    } else {
                        parts.add("查补登日历失败：${h.msg()}")
                    }
                }
            } else {
                parts.add("查连登状态失败：${m.msg()}")
            }
        } catch (e: Exception) {
            parts.add("补登模块异常（${e.message ?: e}）")
            failures++
            hard++
        }

        // ---- 4. 连登奖励兑换（入门 / 进阶 / 巅峰）----
        try {
            val r = get("$BASE/redeem/summary", account)
            if (r.unauthorized) return abort(r.status)
            if (r.ok) {
                for (tier in TIERS) {
                    val status = digStr(r.json, tier.statusKey)
                    // 字段缺失同样跳过：接口改版时不该对三档无脑 POST
                    if (status == null || status == "claimed" || status == "locked") continue
                    var c = post(
                        "$BASE/redeem", account,
                        JSONObject().put("tier", tier.tier).put("client_token", clientToken())
                    )
                    // 档位标识被判未知时退回天数再试一次（参数校验阶段拒绝，重试不会重复领取）
                    if (isUnknownTier(c.status, c.json)) {
                        c = post(
                            "$BASE/redeem", account,
                            JSONObject().put("tier", tier.days).put("client_token", clientToken())
                        )
                    }
                    // 403「连登天数不足」是常态，必须先于通用 401/403 中止判断
                    if (isTierLocked(c.status, c.json)) {
                        parts.add("连登兑换「${tier.label}」未解锁（连登天数不足）")
                        continue
                    }
                    if (c.unauthorized) return abort(c.status)
                    if (c.ok) {
                        credits += digInt(c.json, "credit_granted")
                        parts.add("连登兑换「${tier.label}」${redeemDesc(c.json, tier.tier)}")
                        successes++
                    } else {
                        parts.add("连登兑换「${tier.label}」失败：${c.msg()}")
                        failures++
                        if (isHard(c.status, c.networkError)) hard++
                    }
                }
            } else {
                parts.add("查连登兑换失败：${r.msg()}")
            }
        } catch (e: Exception) {
            parts.add("连登兑换模块异常（${e.message ?: e}）")
            failures++
            hard++
        }

        // ---- 5. 开盲盒（draw 必须带 client_token，缺了 400；写路径不可逆，只开一次）----
        try {
            val l = get("$BASE/lottery/chances", account)
            if (l.unauthorized) return abort(l.status)
            val chances = if (l.ok) digInt(l.json, "balance") else 0
            if (chances > 0) {
                val d = post("$BASE/lottery/draw", account, JSONObject().put("client_token", clientToken()))
                if (d.unauthorized) return abort(d.status)
                if (d.ok) {
                    var prize = digStr(d.json, "prize_name") ?: digStr(d.json, "prize") ?: "未知"
                    if (dig(d.json, "need_address") == true || dig(d.json, "require_address") == true) {
                        prize += "（实物奖，需到成长中心填写收件信息）"
                    }
                    parts.add("开盲盒获得：$prize")
                    successes++
                    if (chances > 1) parts.add("还剩 ${chances - 1} 次抽奖机会，下轮继续")
                } else {
                    val msg = digStr(d.json, "msg")
                    if (isNoChance(msg)) {
                        parts.add("开盲盒：${msg ?: "无抽奖机会"}")
                    } else {
                        parts.add("开盲盒失败：${d.msg()}")
                        failures++
                        if (isHard(d.status, d.networkError)) hard++
                    }
                }
            }
        } catch (e: Exception) {
            parts.add("盲盒模块异常（${e.message ?: e}）")
            failures++
            hard++
        }

        // ---- 6. Buddy 盲盒（能量攒够就开，能量没有别的消耗出口）----
        try {
            val q = get("$BASE/buddy/quota", account)
            if (q.unauthorized) return abort(q.status)
            if (q.ok) {
                val affordable = digInt(q.json, "affordable")
                val maxOpen = digInt(q.json, "max_open_count", 1).coerceAtLeast(1)
                if (affordable > 0) {
                    val count = minOf(affordable, maxOpen)
                    val o = post(
                        "$BASE/buddy/open", account,
                        JSONObject().put("count", count).put("client_token", clientToken())
                    )
                    if (o.unauthorized) return abort(o.status)
                    if (o.ok) {
                        val name = digStr(o.json, "buddy") ?: digStr(o.json, "name") ?: "新 Buddy"
                        parts.add("开 Buddy 盲盒 ×$count（$name）")
                        successes++
                    } else {
                        parts.add("开 Buddy 盲盒失败：${o.msg()}")
                        failures++
                        if (isHard(o.status, o.networkError)) hard++
                    }
                }
            }
        } catch (e: Exception) {
            parts.add("Buddy 盲盒模块异常（${e.message ?: e}）")
            failures++
            hard++
        }

        // ---- 7. 能量 / 连签（纯展示，取不到不计失败）----
        var energy: Int? = null
        var streakDays: Int? = null
        try {
            val e = get("$BASE/energy", account)
            if (e.ok) energy = digInt(e.json, "balance")
        } catch (_: Exception) {
        }
        try {
            if (streakBody != null && !streakStale) {
                streakDays = digObj(streakBody, "streak")?.optInt("days")
            } else {
                val s2 = get("$BASE/streak", account)
                if (s2.ok) streakDays = digObj(s2.json, "streak")?.optInt("days")
            }
        } catch (_: Exception) {
        }

        val tail = ArrayList<String>()
        energy?.let { tail.add("能量 $it") }
        streakDays?.let { tail.add("连签 $it 天") }
        if (credits > 0) tail.add("本次 +共 $credits 积分")

        val report = buildString {
            append(
                when {
                    parts.isNotEmpty() -> parts.joinToString("；")
                    failures > 0 -> "成长中心各步骤均失败"
                    else -> "成长中心无可领取项"
                }
            )
            if (tail.isNotEmpty()) append("（${tail.joinToString("，")}）")
        }

        return GrowthResult(
            ok = hard == 0,
            report = report,
            parts = parts.toList(),
            creditsGained = credits,
            energy = energy,
            streakDays = streakDays,
            failures = failures,
            hardFailures = hard,
            idle = successes == 0 && failures == 0,
        )
    }
}
