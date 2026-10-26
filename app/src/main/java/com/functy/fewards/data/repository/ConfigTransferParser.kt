package com.functy.fewards.data.repository

import org.json.JSONArray
import org.json.JSONObject

/**
 * 纯解析：不碰 SharedPreferences。
 *
 * 只认 WorkBuddy。米游社与 Bing 的功能已移除，旧备份里的 `mihoyos` / `bings`
 * 会被忽略（并在结果里提示一句），这样用户拿旧文件导入不会以为"丢数据了"。
 */
object ConfigTransferParser {
    const val FORMAT = "fewards-config"
    const val VERSION = 1

    data class WorkBuddyDraft(
        val id: String,
        val label: String,
        val token: String,
        val uid: String = "",
        val enterpriseId: String = "",
        val refreshToken: String = "",
        val expiresAt: Long = 0L,
    )

    data class Parsed(
        val workbuddies: List<WorkBuddyDraft> = emptyList(),
        /** 旧配置里被忽略的账号数（米游社 + Bing），仅用于提示 */
        val ignoredLegacy: Int = 0,
        val errors: List<String> = emptyList(),
        val recognized: Boolean = true,
    )

    fun parse(raw: String): Parsed {
        val text = raw.trim()
        if (text.isEmpty()) return Parsed(errors = listOf("内容为空"), recognized = false)
        if (text.startsWith("{")) {
            return runCatching { parseJsonObject(JSONObject(text), text) }
                .getOrElse { fallbackPlain(text, it.message) }
        }
        return fallbackPlain(text, null)
    }

    private fun parseJsonObject(root: JSONObject, original: String): Parsed {
        if (root.optString("_format") == FORMAT) return parseBundle(root)
        if (original.trim().startsWith("eyJ")) return parseBareJwt(original)
        // 旧版导出过裸 Cookie（米游社），现在没有对应功能了，给一句能看懂的提示
        if (original.contains("stoken") && original.contains("=")) {
            return Parsed(errors = listOf("这是米游社 Cookie，相关功能已移除"), recognized = false)
        }
        return Parsed(errors = listOf("无法识别的配置格式"), recognized = false)
    }

    private fun fallbackPlain(text: String, jsonError: String?): Parsed {
        if (text.startsWith("eyJ")) return parseBareJwt(text)
        if (text.contains("stoken") && text.contains("=")) {
            return Parsed(errors = listOf("这是米游社 Cookie，相关功能已移除"), recognized = false)
        }
        val reason = jsonError?.let { "解析失败: $it" } ?: "无法识别的配置格式"
        return Parsed(errors = listOf(reason), recognized = false)
    }

    private fun parseBundle(root: JSONObject): Parsed {
        val errors = mutableListOf<String>()
        val workbuddies = mutableListOf<WorkBuddyDraft>()

        val wbArr = root.optJSONArray("workbuddies") ?: JSONArray()
        for (i in 0 until wbArr.length()) {
            val o = wbArr.getJSONObject(i)
            val token = o.optString("token")
            if (token.isEmpty()) {
                errors.add("WorkBuddy 账号 #$i token 为空，跳过")
                continue
            }
            workbuddies.add(
                WorkBuddyDraft(
                    id = o.optString("id").ifEmpty { "wb_$i" },
                    label = o.optString("label").ifEmpty { "Work Buddy 账号" },
                    token = token,
                    uid = o.optString("uid"),
                    enterpriseId = o.optString("enterpriseId"),
                    refreshToken = o.optString("refreshToken"),
                    expiresAt = o.optLong("expiresAt"),
                )
            )
        }

        val legacy = (root.optJSONArray("mihoyos")?.length() ?: 0) +
            (root.optJSONArray("bings")?.length() ?: 0)
        if (legacy > 0) {
            errors.add("已忽略旧配置里的 $legacy 个米游社 / Bing 账号（相关功能已移除）")
        }
        return Parsed(workbuddies = workbuddies, ignoredLegacy = legacy, errors = errors)
    }

    private fun parseBareJwt(text: String): Parsed {
        val token = text.trim()
        return Parsed(
            workbuddies = listOf(
                WorkBuddyDraft(
                    id = "wb_import",
                    label = WorkBuddyLabelOrDefault(token),
                    token = token,
                )
            )
        )
    }

    private fun WorkBuddyLabelOrDefault(token: String): String =
        com.functy.fewards.core.workbuddy.WorkBuddyLabel.decode(token) ?: "Work Buddy 账号"
}
