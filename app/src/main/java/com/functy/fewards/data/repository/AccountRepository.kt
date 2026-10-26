package com.functy.fewards.data.repository

import android.content.Context
import androidx.core.content.edit
import com.functy.fewards.fewardsApp
import org.json.JSONArray
import org.json.JSONObject

/**
 * 账号与登录态仓储。所有 token 仅存本机 SharedPreferences（sec 前缀），绝不外传。
 * 键：sec.wb.accounts（JSON 数组）。
 *
 * 历史版本还存过 `sec.mhy.accounts` / `sec.bing.accounts`（米游社、Bing），
 * 这两块功能已移除，残留键不再读取，也不会被清掉——用户若需要手工回收空间自行删除即可。
 */
class AccountRepository {

    private val prefs by lazy {
        fewardsApp.getSharedPreferences("secure_store", Context.MODE_PRIVATE)
    }

    // ==================== WorkBuddy ====================

    /**
     * WorkBuddy 账号。扫码授权会一并带出 uid / enterpriseId / refreshToken / expiresAt；
     * 手动粘贴 token 时这些字段可能为空，引擎只依赖 [token]。
     *
     * 时间单位**不统一**，这是历史遗留、也是同步时最容易踩的坑：
     * - [expiresAt] 是**秒**（扫码时 `currentTimeMillis()/1000 + expiresIn`）；
     * - [obtainedAt] / [refreshedAt] 是**毫秒**，用于判断"这份凭据有多新"。
     */
    data class WorkBuddyAccount(
        val id: String,
        val label: String,
        val token: String,
        val avatarUrl: String = "",
        val uid: String = "",
        val enterpriseId: String = "",
        val refreshToken: String = "",
        /** 秒 */
        val expiresAt: Long = 0L,
        /** 毫秒：这份凭据是什么时候拿到的（扫码 / 导入 / 刷新）。0 = 未知（老数据） */
        val obtainedAt: Long = 0L,
        /** 秒：refresh_token 的过期时间，电脑端回传；0 = 未知 */
        val refreshExpiresAt: Long = 0L,
        /** 电脑端维护的同步版本；a 只记录用于展示与回传 */
        val syncVersion: Long = 0L,
    )

    fun workBuddyAccounts(): List<WorkBuddyAccount> {
        val raw = prefs.getString("sec.wb.accounts", null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                WorkBuddyAccount(
                    id = o.optString("id"),
                    label = o.optString("label"),
                    token = o.optString("token"),
                    avatarUrl = o.optString("avatarUrl"),
                    uid = o.optString("uid"),
                    enterpriseId = o.optString("enterpriseId"),
                    refreshToken = o.optString("refreshToken"),
                    expiresAt = o.optLong("expiresAt"),
                    obtainedAt = o.optLong("obtainedAt"),
                    refreshExpiresAt = o.optLong("refreshExpiresAt"),
                    syncVersion = o.optLong("syncVersion"),
                )
            }
        }.getOrDefault(emptyList())
    }

    /**
     * 写入 / 更新一个 WorkBuddy 账号（多账号：同一账号重复授权 = 更新，而不是多出一条）。
     * 去重顺序：
     *  1. 相同的 uid（扫码授权带出的权威 id）；
     *  2. 老账号（uid 为空）按 label 命中——扫码前导入的 token 没有 uid，否则会留下重复条目；
     *  3. 相同的 id。
     * 命中时原地替换，保留列表顺序。
     */
    fun addWorkBuddyAccount(account: WorkBuddyAccount) {
        val list = workBuddyAccounts().toMutableList()
        val index = list.indexOfFirst { existing ->
            existing.id == account.id ||
                (account.uid.isNotEmpty() && existing.uid == account.uid) ||
                (account.uid.isNotEmpty() && existing.uid.isEmpty() &&
                    existing.label.isNotEmpty() && existing.label == account.label)
        }
        if (index >= 0) list[index] = account else list.add(account)
        saveWorkBuddy(list)
    }

    fun removeWorkBuddyAccount(id: String) {
        saveWorkBuddy(workBuddyAccounts().filter { it.id != id })
    }

    /**
     * 局部更新凭据（刷新 / 同步回写专用）。
     *
     * 只动凭据相关字段，**保留** label / avatarUrl / enterpriseId ——
     * 电脑端回传的是瘦数据，不能让它把本机头像和备注抹掉。
     *
     * 空值不覆盖：电脑端对加密信封账号会回传空 token，直接写会把有效凭据清空。
     *
     * @return true 表示找到并写入了；false 表示本机没有这个账号
     */
    fun updateWorkBuddyCredential(
        uid: String,
        accessToken: String,
        refreshToken: String,
        /** 秒 */
        expiresAt: Long,
        /** 秒 */
        refreshExpiresAt: Long = 0L,
        /** 毫秒 */
        obtainedAt: Long = System.currentTimeMillis(),
        syncVersion: Long = 0L,
    ): Boolean {
        if (uid.isEmpty()) return false
        val list = workBuddyAccounts().toMutableList()
        val index = list.indexOfFirst { it.uid == uid || it.id == uid }
        if (index < 0) return false
        val old = list[index]
        list[index] = old.copy(
            token = accessToken.ifEmpty { old.token },
            refreshToken = refreshToken.ifEmpty { old.refreshToken },
            expiresAt = if (expiresAt > 0) expiresAt else old.expiresAt,
            refreshExpiresAt = if (refreshExpiresAt > 0) refreshExpiresAt else old.refreshExpiresAt,
            obtainedAt = if (obtainedAt > 0) obtainedAt else old.obtainedAt,
            syncVersion = if (syncVersion > 0) syncVersion else old.syncVersion,
        )
        saveWorkBuddy(list)
        return true
    }

    private fun saveWorkBuddy(list: List<WorkBuddyAccount>) {
        val arr = JSONArray()
        list.forEach { a ->
            arr.put(
                JSONObject()
                    .put("id", a.id)
                    .put("label", a.label)
                    .put("token", a.token)
                    .put("avatarUrl", a.avatarUrl)
                    .put("uid", a.uid)
                    .put("enterpriseId", a.enterpriseId)
                    .put("refreshToken", a.refreshToken)
                    .put("expiresAt", a.expiresAt)
                    .put("obtainedAt", a.obtainedAt)
                    .put("refreshExpiresAt", a.refreshExpiresAt)
                    .put("syncVersion", a.syncVersion)
            )
        }
        prefs.edit { putString("sec.wb.accounts", arr.toString()) }
    }

    fun workBuddyConfigured(): Boolean = workBuddyAccounts().isNotEmpty()

    // ==================== 本地完成标记 ====================

    private fun doneKey(task: String) = "done.$task"

    fun todayStamp(): String = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
        .format(java.util.Date())

    fun isDoneToday(task: String): Boolean = prefs.getString(doneKey(task), null) == todayStamp()

    fun markDoneToday(task: String) {
        prefs.edit { putString(doneKey(task), todayStamp()) }
    }

    fun clearDone(task: String) {
        prefs.edit { remove(doneKey(task)) }
    }
}
