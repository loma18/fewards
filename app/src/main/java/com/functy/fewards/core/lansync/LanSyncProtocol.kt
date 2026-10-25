package com.functy.fewards.core.lansync

import org.json.JSONArray
import org.json.JSONObject

/**
 * 手机同步协议（与 workbuddy-console 的 `server/lansync/protocol.mjs` 一一对应）。
 *
 * ## 单位约定（最容易写错的地方）
 *
 * | 字段 | 单位 | 说明 |
 * | --- | --- | --- |
 * | `expiresAt` | **秒** | fewards 本地一直用秒（`currentTimeMillis()/1000`） |
 * | `obtainedAt` | **毫秒** | 取到这份凭据的时刻，用于和电脑端 `refreshedAt` 比新旧 |
 * | `refreshExpiresAt` | **秒** | 电脑端回传，a 只用于展示 |
 * | `version` | 整数 | 电脑端维护的单调版本；a 暂不维护，恒发 0 |
 *
 * 秒 / 毫秒差 1000 倍：写错不会崩，只会让账号"刚同步完就显示过期"。
 *
 * ## 安全
 *
 * 只允许私有网段地址：拿到的 token 是长期凭据，绝不能因为用户手滑填成公网 IP 就发出去。
 */
object LanSyncProtocol {

    /** 协议版本，与电脑端 PROTOCOL_VERSION 对齐；不一致时由电脑端拒绝并给提示 */
    const val VERSION = 1

    /** 默认端口，与电脑端 settings.lanSync.port 默认值一致 */
    const val DEFAULT_PORT = 57900

    data class Endpoint(val host: String, val port: Int) {
        val baseUrl: String get() = "http://$host:$port"
        val display: String get() = "$host:$port"
    }

    /** 推送给电脑端的账号（a 的形态） */
    data class Payload(
        val uid: String,
        val label: String,
        val enterpriseId: String,
        val accessToken: String,
        val refreshToken: String,
        /** 秒 */
        val expiresAt: Long,
        /** 毫秒；0 = 未知（老数据），电脑端会走保守分支 */
        val obtainedAt: Long,
    )

    /** 电脑端回传的账号（b 的形态，字段名已换成 a 的习惯） */
    data class Remote(
        val uid: String,
        val label: String,
        val token: String,
        val refreshToken: String,
        /** 秒 */
        val expiresAt: Long,
        /** 秒 */
        val refreshExpiresAt: Long,
        /** 电脑端正在使用该账号：a 不应覆盖它，也不应刷新它 */
        val isCurrent: Boolean,
        val version: Long,
    )

    /** 单条裁决结果 */
    data class Result(
        val uid: String,
        val action: String,
        val reason: String,
        val message: String,
        val risk: String,
        val account: Remote?,
    )

    data class PushResponse(
        val results: List<Result>,
        val serverTime: Long,
    )

    /** 待回传队列条目 */
    data class PendingItem(
        val id: String,
        val uid: String,
        val token: String,
        val refreshToken: String,
        val expiresAt: Long,
        val refreshExpiresAt: Long,
    )

    /** 地址不合法 / 不是内网 —— 都属于"不该发请求"的情况，用统一异常表达 */
    class BadEndpoint(message: String) : IllegalArgumentException(message)

    /**
     * 解析用户输入的地址。
     *
     * 支持 `192.168.1.5:57900`、`192.168.1.5`、`http://192.168.1.5:57900/`。
     * **不解析域名**：手机要连的是电脑的局域网 IP，允许域名等于把凭据交给任意主机。
     */
    fun parseEndpoint(input: String): Endpoint {
        var raw = input.trim()
        if (raw.isEmpty()) throw BadEndpoint("请先填写电脑的地址")
        raw = raw.removePrefix("http://").removePrefix("https://").trimEnd('/')
        // IPv6 暂不支持（电脑端也只监听 IPv4），出现多个冒号直接判非法
        val parts = raw.split(":")
        if (parts.size > 2) throw BadEndpoint("地址格式不正确，应形如 192.168.1.5:57900")
        val host = parts[0].trim()
        if (host.isEmpty()) throw BadEndpoint("地址缺少 IP")
        if (!isPrivateHost(host)) {
            throw BadEndpoint("只能填局域网地址（192.168.x.x / 10.x.x.x / 172.16-31.x.x），不能填公网 IP 或域名")
        }
        val port = if (parts.size == 2 && parts[1].isNotBlank()) {
            parts[1].trim().toIntOrNull() ?: throw BadEndpoint("端口不是有效数字")
        } else {
            DEFAULT_PORT
        }
        if (port !in 1..65535) throw BadEndpoint("端口需在 1–65535 之间")
        return Endpoint(host, port)
    }

    /**
     * 是否为可接受的地址：私有网段或本机回环。
     *
     * 纯字符串判断，不做 DNS 解析——解析会阻塞，而且解析成功恰恰说明是个域名，本就该拒。
     */
    fun isPrivateHost(host: String): Boolean {
        if (host == "localhost") return true
        val octets = host.split(".")
        if (octets.size != 4) return false
        val nums = octets.map { it.toIntOrNull() ?: return false }
        if (nums.any { it !in 0..255 }) return false
        val (a, b) = nums
        return when {
            a == 127 -> true
            a == 10 -> true
            a == 192 && b == 168 -> true
            a == 172 && b in 16..31 -> true
            // 链路本地：手机热点直连偶尔会分到这个段
            a == 169 && b == 254 -> true
            else -> false
        }
    }

    /** 构造推送请求体 */
    fun buildPushBody(accounts: List<Payload>, deviceName: String, force: Boolean): JSONObject {
        val arr = JSONArray()
        accounts.forEach { a ->
            arr.put(
                JSONObject()
                    .put("uid", a.uid)
                    .put("label", a.label)
                    .put("enterpriseId", a.enterpriseId)
                    .put("accessToken", a.accessToken)
                    .put("refreshToken", a.refreshToken)
                    .put("expiresAt", a.expiresAt)
                    .put("obtainedAt", a.obtainedAt)
                    .put("version", 0)
                    .put("variant", "cn")
            )
        }
        return JSONObject()
            .put("version", VERSION)
            .put("device", JSONObject().put("name", deviceName).put("platform", "android"))
            .put("mode", if (force) "force" else "merge")
            .put("accounts", arr)
    }

    /** 解析推送响应；结构不认识时抛 [IllegalStateException]，由上层转成可读错误 */
    fun parsePushResponse(json: JSONObject): PushResponse {
        val arr = json.optJSONArray("results") ?: JSONArray()
        val list = ArrayList<Result>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            list.add(
                Result(
                    uid = o.optString("uid"),
                    action = o.optString("action"),
                    reason = o.optString("reason"),
                    message = o.optString("message"),
                    risk = o.optString("risk"),
                    account = o.optJSONObject("account")?.let { parseRemote(it) },
                )
            )
        }
        return PushResponse(list, json.optLong("serverTime"))
    }

    private fun parseRemote(o: JSONObject): Remote = Remote(
        uid = o.optString("uid"),
        label = o.optString("label"),
        token = o.optString("token"),
        refreshToken = o.optString("refreshToken"),
        expiresAt = o.optLong("expiresAt"),
        refreshExpiresAt = o.optLong("refreshExpiresAt"),
        isCurrent = o.optBoolean("isCurrent", false),
        version = o.optLong("version"),
    )

    /** 解析待同步队列 */
    fun parsePending(json: JSONObject): List<PendingItem> {
        val arr = json.optJSONArray("items") ?: JSONArray()
        val list = ArrayList<PendingItem>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id")
            if (id.isEmpty()) continue
            list.add(
                PendingItem(
                    id = id,
                    uid = o.optString("uid"),
                    token = o.optString("token"),
                    refreshToken = o.optString("refreshToken"),
                    expiresAt = o.optLong("expiresAt"),
                    refreshExpiresAt = o.optLong("refreshExpiresAt"),
                )
            )
        }
        return list
    }

    /** 从错误响应体里取出可展示的中文文案 */
    fun errorMessage(body: String, httpStatus: Int): String {
        val text = runCatching { JSONObject(body).optString("error") }.getOrDefault("")
        if (text.isNotEmpty()) return text
        return when (httpStatus) {
            401 -> "配对码不正确"
            429 -> "请求过于频繁，请稍后再试"
            413 -> "账号太多，请求体超出上限"
            else -> "同步失败（HTTP $httpStatus）"
        }
    }
}
