package com.functy.fewards.core.lansync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 手机同步的 HTTP 客户端（a → b）。
 *
 * ## 超时为什么单独建一个 client
 *
 * 推送时电脑端可能要串行刷新 3–5 个账号（每个一次官方请求 + 400ms 间隔），
 * 共用 `FewardsApplication.okhttpClient` 的 30 秒读超时会在账号多时直接断掉。
 * 这里单独放宽到 90 秒，连接超时反而收紧到 10 秒——电脑不在线时应该立刻失败，
 * 而不是让用户对着转圈等半分钟。
 *
 * ## 错误分两类
 *
 * - [LanSyncException]：电脑端明确回了错误（配对码错、端口不通的业务提示），可直接展示；
 * - `IOException`：压根没连上（不在同一 Wi-Fi、IP 填错），由上层翻译成"检查网络"。
 */
class LanSyncClient(baseClient: OkHttpClient) {

    private val client: OkHttpClient = baseClient.newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    /** 电脑端明确返回的错误（带可直接展示的中文文案） */
    class LanSyncException(message: String, val code: Int = 0) : Exception(message)

    companion object {
        private val jsonMedia = "application/json; charset=utf-8".toMediaType()
    }

    /** 探测电脑端是否在线；不抛异常，失败一律 false */
    suspend fun ping(endpoint: LanSyncProtocol.Endpoint): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url("${endpoint.baseUrl}/api/lansync/ping")
                .get()
                .build()
            client.newCall(req).execute().use { resp -> resp.isSuccessful }
        }.getOrDefault(false)
    }

    /**
     * 推送账号凭据，并取回电脑端最终凭据（一次往返完成双向对齐）。
     *
     * @param force true = 以手机为准（电脑端仍会保护它当前登录的账号）
     */
    suspend fun push(
        endpoint: LanSyncProtocol.Endpoint,
        pairCode: String,
        accounts: List<LanSyncProtocol.Payload>,
        deviceName: String,
        force: Boolean,
    ): LanSyncProtocol.PushResponse = withContext(Dispatchers.IO) {
        val body = LanSyncProtocol.buildPushBody(accounts, deviceName, force)
        val req = Request.Builder()
            .url("${endpoint.baseUrl}/api/lansync/push")
            .post(body.toString().toRequestBody(jsonMedia))
            .header("Authorization", "Bearer $pairCode")
            .header("Accept", "application/json")
            .build()
        val (code, text) = execute(req)
        if (code !in 200..299) throw LanSyncException(LanSyncProtocol.errorMessage(text, code), code)
        val json = runCatching { JSONObject(text) }.getOrElse {
            throw LanSyncException("电脑端返回的内容无法解析，请确认两端版本一致")
        }
        LanSyncProtocol.parsePushResponse(json)
    }

    /**
     * 只读取电脑端现有凭据（**不写电脑端账号库**）。
     *
     * 用于「从电脑同步凭据」：手机端某个账号的 access token 过期了、电脑端那份还活着，
     * 就把电脑端的值读回来补上。老的 `/push` 也能拿到回传，但它会先做一次合并裁决，
     * 有把手机端的死凭据写进电脑端的可能；这里只读，语义明确。
     *
     * @param uids 只取这些账号（电脑端的 uid 或 id）；空列表表示全部
     * @throws LanSyncException 电脑端明确拒绝（配对码错 / 版本过旧 404）
     */
    suspend fun fetch(
        endpoint: LanSyncProtocol.Endpoint,
        pairCode: String,
        uids: List<String>,
    ): List<LanSyncProtocol.Remote> = withContext(Dispatchers.IO) {
        val body = LanSyncProtocol.buildFetchBody(uids)
        val req = Request.Builder()
            .url("${endpoint.baseUrl}/api/lansync/fetch")
            .post(body.toString().toRequestBody(jsonMedia))
            .header("Authorization", "Bearer $pairCode")
            .header("Accept", "application/json")
            .build()
        val (code, text) = execute(req)
        if (code !in 200..299) throw LanSyncException(LanSyncProtocol.errorMessage(text, code), code)
        val json = runCatching { JSONObject(text) }.getOrElse {
            throw LanSyncException("电脑端返回的内容无法解析，请确认两端版本一致")
        }
        LanSyncProtocol.parseFetchResponse(json)
    }

    /** 拉取"电脑刷新过、还没通知手机"的凭据 */
    suspend fun pending(
        endpoint: LanSyncProtocol.Endpoint,
        pairCode: String,
    ): List<LanSyncProtocol.PendingItem> = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("${endpoint.baseUrl}/api/lansync/pending")
            .get()
            .header("Authorization", "Bearer $pairCode")
            .header("Accept", "application/json")
            .build()
        val (code, text) = execute(req)
        if (code !in 200..299) throw LanSyncException(LanSyncProtocol.errorMessage(text, code), code)
        val json = runCatching { JSONObject(text) }.getOrElse {
            throw LanSyncException("电脑端返回的内容无法解析，请确认两端版本一致")
        }
        LanSyncProtocol.parsePending(json)
    }

    /** 确认已收到，电脑端据此出队（幂等，重复 ack 不报错） */
    suspend fun ack(
        endpoint: LanSyncProtocol.Endpoint,
        pairCode: String,
        ids: List<String>,
    ) = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext
        val body = JSONObject().put("ids", org.json.JSONArray(ids))
        val req = Request.Builder()
            .url("${endpoint.baseUrl}/api/lansync/ack")
            .post(body.toString().toRequestBody(jsonMedia))
            .header("Authorization", "Bearer $pairCode")
            .header("Accept", "application/json")
            .build()
        val (code, text) = execute(req)
        if (code !in 200..299) throw LanSyncException(LanSyncProtocol.errorMessage(text, code), code)
    }

    private fun execute(req: Request): Pair<Int, String> =
        client.newCall(req).execute().use { resp ->
            resp.code to (resp.body?.string() ?: "")
        }
}
