package com.functy.fewards.core.workbuddy

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * WorkBuddy 凭据刷新（`/v2/plugin/auth/token/refresh`）。
 *
 * 移植自 workbuddy-console 的 `server/proxy/wb.mjs:refreshAccessToken`，请求头一字不改：
 * 官方网关会校验客户端指纹（`Origin` / `Referer` / `User-Agent` / `X-CodeBuddy-Request`），
 * 少一个就可能是 403 而不是"凭据无效"。
 *
 * ## 轮换语义（决定了整个同步方案）
 *
 * 刷新成功时服务端会**换发新的 refresh_token，并立刻作废旧值**。所以：
 * - 本地必须马上用新值覆盖；写失败要回滚，否则下次刷新拿着死 token 必然失败；
 * - 电脑端若也持有同一账号的副本，必须同步过去（否则它那份立刻失效）；
 * - 这就是"同一时刻只允许一端刷新"的根因。
 *
 * ## 错误话术
 *
 * `refresh token is invalid` 是官方最常见的失效态，直接抛英文原文用户没法处理。
 * 这里翻成「怎么回事 + 怎么办」，和电脑端保持一致的措辞。
 */
class WorkBuddyTokenRefresh(private val client: OkHttpClient) {

    companion object {
        const val API_BASE = "https://copilot.tencent.com"
        const val PATH_REFRESH = "/v2/plugin/auth/token/refresh"

        /**
         * 出站指纹头。版本号与电脑端 `wb.mjs` 保持一致——两边打的是同一个端点，
         * 用两套 UA 只会在官方做客户端校验时多出一种失败可能。
         */
        private const val CLIENT_VERSION = "5.5.4"
        private const val CLI_VERSION = "2.137.1"

        private val jsonMedia = "application/json; charset=utf-8".toMediaType()

        private fun userAgent() = "WorkBuddy/$CLIENT_VERSION WorkBuddy/$CLIENT_VERSION CLI/$CLI_VERSION"

        /** 解析刷新响应。返回 null 表示结构不认识（按失败处理） */
        fun parse(payload: JSONObject, nowMs: Long = System.currentTimeMillis()): Refreshed? {
            // 兼容 {code,msg,data} 信封与裸对象两种形态
            val data = payload.optJSONObject("data") ?: payload
            val access = data.optString("accessToken").ifEmpty { data.optString("access_token") }.trim()
            if (access.isEmpty()) return null
            val refresh = data.optString("refreshToken").ifEmpty { data.optString("refresh_token") }.trim()
            val expiresIn = data.optLong("expiresIn").takeIf { it > 0 }
                ?: data.optLong("expires_in").takeIf { it > 0 }
                ?: 0L
            val refreshExpiresIn = data.optLong("refreshExpiresIn").takeIf { it > 0 }
                ?: data.optLong("refresh_expires_in").takeIf { it > 0 }
                ?: 0L
            return Refreshed(
                accessToken = access,
                // 服务端不换发时沿用旧值（b 的注释里记着微软会这样，WorkBuddy 也可能）
                refreshToken = refresh,
                expiresAtMs = if (expiresIn > 0) nowMs + expiresIn * 1000 else 0L,
                refreshExpiresAtMs = if (refreshExpiresIn > 0) nowMs + refreshExpiresIn * 1000 else 0L,
            )
        }

        /** 把官方英文错误翻成可操作的中文 */
        fun humanizeError(status: Int, body: String): String {
            if (Regex("invalid[\\s_-]*(refresh|token)|invalid_grant", RegexOption.IGNORE_CASE).containsMatchIn(body)) {
                return "refresh_token 已失效：官方刷新会轮换 token，同一账号被别处刷新过后旧值就作废了。" +
                    "请重新扫码登录该账号，或在电脑端同步后再试"
            }
            return "刷新失败（HTTP $status）${body.take(120)}"
        }
    }

    data class Refreshed(
        val accessToken: String,
        /** 空串表示服务端没换发，调用方应沿用旧 refresh_token */
        val refreshToken: String,
        val expiresAtMs: Long,
        val refreshExpiresAtMs: Long,
    )

    /**
     * 用 refresh_token 换一份新凭据。
     *
     * @throws IllegalStateException 凭据失效 / 响应不可解析（消息可直接展示）
     */
    suspend fun refresh(refreshToken: String, uid: String = ""): Refreshed = withContext(Dispatchers.IO) {
        val token = refreshToken.trim()
        require(token.isNotEmpty()) { "该账号没有 refresh_token，需要重新扫码登录" }

        val builder = Request.Builder()
            .url(API_BASE + PATH_REFRESH)
            .post("{}".toRequestBody(jsonMedia))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Origin", "https://www.codebuddy.cn")
            .header("Referer", "https://www.codebuddy.cn/")
            .header("User-Agent", userAgent())
            .header("X-CodeBuddy-Request", "1")
            // refresh_token 只允许出现在这个头里，绝不进 chat 请求
            .header("X-Refresh-Token", token)
            .header("X-Auth-Refresh-Source", "plugin")
        if (uid.isNotEmpty()) builder.header("X-User-Id", uid)

        client.newCall(builder.build()).execute().use { resp ->
            val text = resp.body?.string() ?: ""
            if (!resp.isSuccessful) throw IllegalStateException(humanizeError(resp.code, text))
            val json = runCatching { JSONObject(text) }.getOrElse {
                throw IllegalStateException("刷新响应不是合法 JSON，请稍后重试")
            }
            parse(json) ?: throw IllegalStateException("刷新响应里没有 accessToken，需要重新扫码登录")
        }
    }
}
