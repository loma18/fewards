package com.functy.fewards.core.workbuddy

import com.functy.fewards.core.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * WorkBuddy（腾讯 CodeBuddy）扫码授权协议客户端。
 * 移植自 workbuddy-manager 的 server/services/tencent.py（其自述对齐 workbuddy2api cmd/login）。
 *
 * 三步：
 *  1. POST /v2/plugin/auth/state?platform=CLI  -> data{state, authUrl}
 *  2. GET  /v2/plugin/auth/token?state=…       -> code!=0 表示仍在等待；code==0 时 data 含驼峰 token
 *  3. GET  /v2/plugin/login/account?state=…    -> 需 Bearer accessToken，data{uid, enterpriseId, nickname}
 *
 * 所有腾讯接口统一信封 {code, msg, data}，HTTP 4xx 也可能是业务信封，因此判定以 code 为准。
 * 轮询期间的「未扫码」不是错误：实测未扫码时返回 code=11217（msg "login ing..."）。
 * 日志严禁打印 accessToken / refreshToken。
 *
 * ## 档位
 *
 * 两档的**域名与 platform 参数都不同**（国内版 `copilot.tencent.com` + `CLI`，
 * 国际版 `www.workbuddy.ai` + `workbuddy-ai`），而且 state 与档位是绑定的：
 * 用国内版申请的 state 去国际版换 token 只会一直"等待中"。所以 [Session] 里记下发起档位，
 * [poll] 全程沿用那个档位，**绝不跟随界面上当前选中的档位**。
 */
class WorkBuddyLogin(private val client: OkHttpClient) {

    companion object {
        /** 国内版扫码域；国际版见 [WorkBuddyVariant.chatBase] */
        const val API_BASE = "https://copilot.tencent.com"
        const val PATH_AUTH_STATE = "/v2/plugin/auth/state"
        const val PATH_AUTH_TOKEN = "/v2/plugin/auth/token"
        const val PATH_LOGIN_ACCOUNT = "/v2/plugin/login/account"

        /** 实测：未扫码时 /auth/token 返回的业务码。 */
        const val CODE_WAITING = 11217

        /** 本地会话有效期，与 workbuddy-manager 的 STATE_TTL 一致。 */
        const val STATE_TTL_MS = 300_000L

        /** 国内版的固定出站头（保留常量是为了契约测试；其它档位走 [headersOf]） */
        val HEADERS: Map<String, String> = headersOf(WorkBuddyVariant.CN)

        /** 出站头：只有 `Origin` / `Referer` 随档位变，其余与官方 CLI 指纹一致 */
        fun headersOf(variant: WorkBuddyVariant): Map<String, String> = mapOf(
            "Content-Type" to "application/json",
            "Accept" to "application/json, text/plain, */*",
            "X-Requested-With" to "XMLHttpRequest",
            "User-Agent" to "CLI/2.63.2 CodeBuddy/2.63.2",
            "Origin" to variant.apiEndpoint,
            "Referer" to variant.apiEndpoint + "/",
        )

        private val jsonMedia = "application/json; charset=utf-8".toMediaType()

        // ==================== 纯解析（JVM 单测钉这里） ====================

        /** 腾讯信封：优先取 body 里的 code，取不到时退回 HTTP 状态码。 */
        fun envelopeCode(httpStatus: Int, json: JSONObject): Int =
            if (json.has("code")) json.optInt("code", -1) else httpStatus

        /**
         * 解析用的成功判定：必须**显式**带 code==0。
         * 空对象 / 非 JSON / 缺 code 一律算失败——这类响应只会出现在网络或协议异常时，
         * 不能因为「没有 code 字段」就当成成功。
         */
        fun hasOkCode(json: JSONObject): Boolean = json.has("code") && json.optInt("code", -1) == 0

        fun envelopeData(json: JSONObject): JSONObject? = json.optJSONObject("data")

        /** 解析 /auth/state 的 data。缺 state 或 authUrl 视为失败。 */
        fun parseAuthState(json: JSONObject): Session? {
            if (!hasOkCode(json)) return null
            val data = envelopeData(json) ?: return null
            val state = data.optString("state").trim()
            val authUrl = data.optString("authUrl").trim()
            if (state.isEmpty() || authUrl.isEmpty()) return null
            return Session(state = state, authUrl = authUrl)
        }

        /** 解析 /auth/token 的 data。未扫码时 data 为空 -> null（调用方按等待处理）。 */
        fun parseAuthToken(json: JSONObject): TokenPayload? {
            if (!hasOkCode(json)) return null
            val data = envelopeData(json) ?: return null
            val accessToken = data.optString("accessToken").trim()
            if (accessToken.isEmpty()) return null
            return TokenPayload(
                accessToken = accessToken,
                refreshToken = data.optString("refreshToken").trim(),
                expiresIn = data.optInt("expiresIn", 3600).let { if (it > 0) it else 3600 },
                domain = data.optString("domain").trim(),
            )
        }

        /** 解析 /login/account 的 data。缺 uid 视为未就绪。 */
        fun parseLoginAccount(json: JSONObject): AccountInfo? {
            val data = envelopeData(json) ?: return null
            val uid = data.optString("uid").trim()
            if (uid.isEmpty()) return null
            return AccountInfo(
                uid = uid,
                nickname = data.optString("nickname").trim(),
                enterpriseId = data.optString("enterpriseId").trim(),
            )
        }

        /** 未扫码（含任何非 0 业务码）都按「继续等待」处理，与 workbuddy-manager 一致。 */
        fun isWaiting(code: Int): Boolean = code != 0

        /**
         * 响应里的 `domain` 是否与发起档位一致。
         *
         * 官方在 token / profile 两处都会回 `domain`；跨档登录（例如拿国内版 state 换国际版
         * token）会得到与档位不符的 domain，这种账号入库后客户端会一直处于"登录失效"状态。
         * 空 domain 视为匹配（官方未回传时放行，不能因此拒掉正常登录）。
         */
        fun matchesVariantDomain(variant: WorkBuddyVariant, domain: String): Boolean {
            val d = domain.trim().lowercase()
            if (d.isEmpty()) return true
            val ai = d == "workbuddy.ai" || d.endsWith(".workbuddy.ai")
            return if (variant == WorkBuddyVariant.AI) ai else !ai
        }
    }

    /** 一次扫码会话。**必须记住发起档位**，轮询全程沿用它。 */
    data class Session(
        val state: String,
        val authUrl: String,
        val startedAt: Long = System.currentTimeMillis(),
        val variant: String = "cn",
    )

    data class TokenPayload(
        val accessToken: String,
        val refreshToken: String,
        val expiresIn: Int,
        val domain: String,
    )

    data class AccountInfo(val uid: String, val nickname: String, val enterpriseId: String)

    data class LoginResult(
        val uid: String,
        val nickname: String,
        val enterpriseId: String,
        val accessToken: String,
        val refreshToken: String,
        val expiresAt: Long,
        val domain: String,
        /** 本次登录所属档位（`cn` / `ai`） */
        val variant: String = "cn",
    )

    sealed class Status {
        /** 仍在等待扫码 / 已扫未确认。 */
        data object Pending : Status()

        /** 本地会话超时（腾讯侧 state 过期）。 */
        data object Expired : Status()

        data class Ready(val result: LoginResult) : Status()
    }

    // ==================== HTTP ====================

    private suspend fun post(
        path: String,
        query: Map<String, String> = emptyMap(),
        variant: WorkBuddyVariant = WorkBuddyVariant.CN,
    ): JSONObject =
        withContext(Dispatchers.IO) {
            val req = Request.Builder()
                .url(variant.chatBase + path + buildQuery(query))
                .post("{}".toRequestBody(jsonMedia))
                .apply { headersOf(variant).forEach { (k, v) -> header(k, v) } }
                .build()
            client.newCall(req).execute().use { resp ->
                val text = resp.body.string()
                runCatching { JSONObject(text) }.getOrElse { JSONObject() }
            }
        }

    private suspend fun get(
        path: String,
        query: Map<String, String>,
        bearer: String? = null,
        variant: WorkBuddyVariant = WorkBuddyVariant.CN,
    ): JSONObject =
        withContext(Dispatchers.IO) {
            val builder = Request.Builder().url(variant.chatBase + path + buildQuery(query))
            headersOf(variant).forEach { (k, v) -> builder.header(k, v) }
            if (!bearer.isNullOrEmpty()) builder.header("Authorization", "Bearer $bearer")
            client.newCall(builder.build()).execute().use { resp ->
                val text = resp.body.string()
                runCatching { JSONObject(text) }.getOrElse { JSONObject() }
            }
        }

    private fun buildQuery(query: Map<String, String>): String {
        if (query.isEmpty()) return ""
        return query.entries.joinToString("&", prefix = "?") { (k, v) ->
            "${java.net.URLEncoder.encode(k, "UTF-8")}=${java.net.URLEncoder.encode(v, "UTF-8")}"
        }
    }

    // ==================== 流程 ====================

    /**
     * 第一步：申请授权链接。返回的 authUrl 即二维码内容。
     *
     * @param variant 档位（`cn` / `ai`）：决定域名与 platform 参数，并写进 [Session] 供后续轮询沿用
     */
    suspend fun startLogin(variant: String = "cn"): Session {
        val v = WorkBuddyVariant.of(variant)
        val json = post(PATH_AUTH_STATE, mapOf("platform" to v.oauthPlatform), v)
        val session = parseAuthState(json)
        if (session == null) {
            val code = if (json.has("code")) json.optInt("code", -1) else -1
            AppLog.w("WB", "获取授权链接失败 code=$code variant=${v.id}")
            throw RuntimeException("获取${v.label}授权链接失败 code=$code")
        }
        AppLog.i("WB", "已生成 WorkBuddy ${v.label}授权二维码，等待扫码")
        return session.copy(variant = v.id)
    }

    /** 第二步 + 第三步：查询一次扫码结果，成功时顺带取回账号信息。 */
    suspend fun poll(session: Session): Status {
        if (System.currentTimeMillis() - session.startedAt > STATE_TTL_MS) {
            AppLog.w("WB", "二维码已过期（本地会话超时）")
            return Status.Expired
        }
        // 档位必须取自会话：用户可能在等待期间切了档位选择，那个选择与这张码无关
        val v = WorkBuddyVariant.of(session.variant)
        val tokenJson = get(PATH_AUTH_TOKEN, mapOf("state" to session.state), variant = v)
        val code = envelopeCode(0, tokenJson)
        if (isWaiting(code)) return Status.Pending
        val payload = parseAuthToken(tokenJson) ?: return Status.Pending
        // 跨档的 token 入库后客户端会一直"登录失效"，宁可在这一步就拒掉
        if (!matchesVariantDomain(v, payload.domain)) {
            throw RuntimeException("登录响应的 domain（${payload.domain}）与${v.label}不符，已拒绝入库")
        }

        val accountJson = get(
            PATH_LOGIN_ACCOUNT,
            mapOf("state" to session.state),
            bearer = payload.accessToken,
            variant = v,
        )
        val info = parseLoginAccount(accountJson) ?: return Status.Pending

        AppLog.i("WB", "扫码授权成功（${v.label}），账号 ${info.nickname.ifEmpty { info.uid.take(8) }}")
        return Status.Ready(
            LoginResult(
                uid = info.uid,
                nickname = info.nickname.ifEmpty { "Work Buddy 账号" },
                enterpriseId = info.enterpriseId,
                accessToken = payload.accessToken,
                refreshToken = payload.refreshToken,
                expiresAt = System.currentTimeMillis() / 1000 + payload.expiresIn,
                domain = payload.domain,
                variant = v.id,
            )
        )
    }

    /**
     * 轮询直到确认 / 过期 / 超时。默认 2 秒一次，与 workbuddy-manager 前端一致。
     * 腾讯协议在「已扫未确认」和「未扫码」都返回非 0 码，因此没有单独的「已扫码」回调，
     * UI 只需区分「等待中 / 成功 / 过期」。
     */
    suspend fun awaitLogin(
        session: Session,
        timeoutMs: Long = STATE_TTL_MS,
        pollIntervalMs: Long = 2_000,
    ): LoginResult {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            when (val st = poll(session)) {
                is Status.Ready -> return st.result
                is Status.Expired -> throw RuntimeException("二维码已过期")
                is Status.Pending -> delay(pollIntervalMs)
            }
        }
        throw RuntimeException("二维码已过期")
    }
}
