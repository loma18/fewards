package com.functy.fewards.ui.viewmodel

import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.functy.fewards.core.lansync.LanSyncClient
import com.functy.fewards.core.lansync.LanSyncProtocol
import com.functy.fewards.core.workbuddy.WorkBuddyGrowth
import com.functy.fewards.core.workbuddy.WorkBuddyTokenRefresh
import com.functy.fewards.data.repository.AccountRepository
import com.functy.fewards.data.repository.SettingsRepository
import com.functy.fewards.data.repository.SettingsRepositoryImpl
import com.functy.fewards.fewardsApp
import com.functy.fewards.ui.screen.settings.SettingsUiState
import com.functy.fewards.ui.theme.ColorMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException

class SettingsViewModel(
    private val repo: SettingsRepository = SettingsRepositoryImpl(),
    private val accounts: AccountRepository = AccountRepository(),
    private val syncClient: LanSyncClient = LanSyncClient(fewardsApp.okhttpClient),
    private val refresher: WorkBuddyTokenRefresh = WorkBuddyTokenRefresh(fewardsApp.okhttpClient),
    private val growth: WorkBuddyGrowth = WorkBuddyGrowth(fewardsApp.okhttpClient),
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val wbList = accounts.workBuddyAccounts()
            val allIds = wbList.map { it.id }.toSet()
            _uiState.update { state ->
                /**
                 * 勾选集合的收敛规则：
                 * - 首次加载：全选；
                 * - 之后**新出现的账号自动勾选**（新绑的账号理应默认参与）；
                 * - 用户主动取消的保持取消；
                 * - 已删除的账号从集合里移除。
                 *
                 * 早先只做 `selected ∩ allIds`，导致"先在设置页待过、再去账号页绑号"时，
                 * 新账号永远是未勾选状态——列表在，但点「刷新选中账号」一个都选不上。
                 */
                val prevIds = state.wbAccounts.map { it.id }.toSet()
                val newlyAdded = allIds - prevIds
                val selected = if (!state.wbSyncSelectionReady) {
                    allIds
                } else {
                    (state.wbSyncSelected + newlyAdded) intersect allIds
                }
                state.copy(
                    wbSyncSelectionReady = true,
                    themeMode = repo.themeMode,
                    miuixMonet = repo.miuixMonet,
                    keyColor = repo.keyColor,
                    predictiveBackAnimation = repo.predictiveBackAnimation,
                    enableBlur = repo.enableBlur,
                    enableFloatingBottomBar = repo.enableFloatingBottomBar,
                    enableFloatingBottomBarBlur = repo.enableFloatingBottomBarBlur,
                    pageScale = repo.pageScale,
                    // WorkBuddy
                    wbMasterEnabled = repo.wbMasterEnabled,
                    wbGrowthEnabled = repo.wbGrowthEnabled,
                    wbAutoRefresh = repo.wbAutoRefresh,
                    wbAccounts = wbList,
                    wbSyncSelected = selected,
                    wbSyncEndpoint = repo.wbSyncEndpoint,
                    wbSyncPairCode = repo.wbSyncPairCode,
                    wbSyncForcePush = repo.wbSyncForcePush,
                    wbSyncLastAt = repo.wbSyncLastAt,
                    // 通知与完成总览
                    taskNotification = repo.taskNotification,
                    overviewAutoDismiss = repo.overviewAutoDismiss,
                    overviewHoldSeconds = repo.overviewHoldSeconds,
                )
            }
        }
    }

    // ==================== 界面 ====================

    fun setThemeMode(mode: Int) {
        val effectiveMode = if (_uiState.value.miuixMonet) {
            mode + 3
        } else {
            mode
        }
        repo.themeMode = effectiveMode
        _uiState.update { it.copy(themeMode = effectiveMode) }
    }

    fun setColorMode(mode: ColorMode) {
        repo.themeMode = mode.value
        _uiState.update { it.copy(themeMode = mode.value) }
    }

    fun setMiuixMonet(enabled: Boolean) {
        val currentThemeMode = repo.themeMode
        val colorMode = ColorMode.fromValue(currentThemeMode)
        val newThemeMode = if (enabled) {
            if (!colorMode.isMonet) colorMode.toMonetMode() else currentThemeMode
        } else {
            if (colorMode.isMonet) colorMode.toNonMonetMode() else currentThemeMode
        }
        repo.miuixMonet = enabled
        repo.themeMode = newThemeMode
        _uiState.update { it.copy(miuixMonet = enabled, themeMode = newThemeMode) }
    }

    fun setKeyColor(color: Int) {
        repo.keyColor = color
        _uiState.update { it.copy(keyColor = color) }
    }

    fun setColorStyle(style: String) {
        repo.colorStyle = style
        _uiState.update { it.copy(colorStyle = style) }
    }

    fun setColorSpec(spec: String) {
        repo.colorSpec = spec
        _uiState.update { it.copy(colorSpec = spec) }
    }

    fun setPredictiveBackAnimation(index: Int) {
        repo.predictiveBackAnimation = index
        _uiState.update { it.copy(predictiveBackAnimation = index) }
    }

    fun setEnableBlur(enabled: Boolean) {
        repo.enableBlur = enabled
        _uiState.update { it.copy(enableBlur = enabled) }
    }

    fun setEnableFloatingBottomBar(enabled: Boolean) {
        repo.enableFloatingBottomBar = enabled
        _uiState.update { it.copy(enableFloatingBottomBar = enabled) }
    }

    fun setEnableFloatingBottomBarBlur(enabled: Boolean) {
        repo.enableFloatingBottomBarBlur = enabled
        _uiState.update { it.copy(enableFloatingBottomBarBlur = enabled) }
    }

    fun setPageScale(scale: Float) {
        repo.pageScale = scale
        _uiState.update { it.copy(pageScale = scale) }
    }

    // ==================== WorkBuddy ====================

    fun setWbMasterEnabled(enabled: Boolean) {
        repo.wbMasterEnabled = enabled
        _uiState.update { it.copy(wbMasterEnabled = enabled) }
    }

    fun setWbGrowthEnabled(enabled: Boolean) {
        repo.wbGrowthEnabled = enabled
        _uiState.update { it.copy(wbGrowthEnabled = enabled) }
    }

    fun setWbAutoRefresh(enabled: Boolean) {
        repo.wbAutoRefresh = enabled
        _uiState.update { it.copy(wbAutoRefresh = enabled) }
    }

    /**
     * 一键领取：立即对全部账号跑一遍成长中心。
     *
     * 每日签到里也会自动跑（受 [SettingsUiState.wbGrowthEnabled] 控制）；
     * 这个入口供用户白天随时补领——任务完成后、盲盒次数到账后都可以再收一轮。
     * 各模块独立失败，一个账号出错不影响其余账号。
     */
    fun claimGrowth() {
        if (_uiState.value.wbGrowthBusy) return
        viewModelScope.launch {
            val list = accounts.workBuddyAccounts()
            if (list.isEmpty()) {
                _uiState.update { it.copy(wbGrowthReport = "还没有 WorkBuddy 账号，先到账号页扫码或导入") }
                return@launch
            }
            _uiState.update {
                it.copy(wbGrowthBusy = true, wbGrowthReport = "正在领取 ${list.size} 个账号的成长奖励…")
            }
            val lines = ArrayList<String>()
            var credits = 0
            var hardFailures = 0
            for ((index, acc) in list.withIndex()) {
                val label = acc.label.ifEmpty { acc.uid.take(8).ifEmpty { "账号 ${index + 1}" } }
                val r = runCatching { growth.run(acc) }.getOrNull()
                if (r == null) {
                    lines.add("$label：执行异常")
                    hardFailures++
                } else {
                    credits += r.creditsGained
                    if (r.hardFailures > 0) hardFailures++
                    lines.add("$label：${r.report}")
                }
                _uiState.update { it.copy(wbGrowthReport = lines.joinToString("\n")) }
                kotlinx.coroutines.delay(400)
            }
            val summary = buildString {
                append("一键领取完成：${list.size} 个账号")
                if (credits > 0) append("，共 +$credits 积分")
                if (hardFailures > 0) append("，$hardFailures 个存在异常")
            }
            _uiState.update {
                it.copy(wbGrowthBusy = false, wbGrowthReport = summary + "\n" + lines.joinToString("\n"))
            }
        }
    }

    /**
     * 检查并刷新凭据。
     *
     * 只刷「临期」账号：access 剩余 < 24 小时，或 refresh 剩余 < 72 小时。
     * 有效期充足的保持不动——刷新是轮换式的，多刷一次就会顶掉电脑端手里那份。
     */
    fun checkAndRefreshCredentials() {
        if (_uiState.value.wbCredBusy) return
        viewModelScope.launch {
            val list = accounts.workBuddyAccounts()
            if (list.isEmpty()) {
                _uiState.update { it.copy(wbCredReport = "还没有 WorkBuddy 账号") }
                return@launch
            }
            _uiState.update { it.copy(wbCredBusy = true, wbCredReport = "正在检查 ${list.size} 个账号…") }
            val lines = ArrayList<String>()
            var refreshed = 0
            var skipped = 0
            var failed = 0
            val now = System.currentTimeMillis()

            for ((index, acc) in list.withIndex()) {
                val label = acc.label.ifEmpty { acc.uid.take(8).ifEmpty { "账号 ${index + 1}" } }
                // expiresAt / refreshExpiresAt 都是「秒」
                val accessHours = if (acc.expiresAt > 0) (acc.expiresAt * 1000 - now) / 3_600_000.0 else null
                val refreshHours = if (acc.refreshExpiresAt > 0) (acc.refreshExpiresAt * 1000 - now) / 3_600_000.0 else null
                val due = (accessHours != null && accessHours < 24) || (refreshHours != null && refreshHours < 72)

                if (!due) {
                    skipped++
                    lines.add("$label：${fmtRemaining(accessHours)}，无需刷新")
                    _uiState.update { it.copy(wbCredReport = lines.joinToString("\n")) }
                    continue
                }
                if (acc.refreshToken.isBlank()) {
                    failed++
                    lines.add("$label：已临期但没有 refresh_token，需重新扫码")
                    _uiState.update { it.copy(wbCredReport = lines.joinToString("\n")) }
                    continue
                }
                val r = runCatching { refresher.refresh(acc.refreshToken, acc.uid) }
                if (r.isSuccess) {
                    val t = r.getOrThrow()
                    accounts.updateWorkBuddyCredential(
                        uid = acc.uid.ifEmpty { acc.id },
                        accessToken = t.accessToken,
                        refreshToken = t.refreshToken.ifEmpty { acc.refreshToken },
                        expiresAt = if (t.expiresAtMs > 0) t.expiresAtMs / 1000 else acc.expiresAt,
                        refreshExpiresAt =
                            if (t.refreshExpiresAtMs > 0) t.refreshExpiresAtMs / 1000 else acc.refreshExpiresAt,
                        obtainedAt = System.currentTimeMillis(),
                    )
                    refreshed++
                    lines.add("$label：已刷新")
                } else {
                    failed++
                    lines.add("$label：刷新失败（${r.exceptionOrNull()?.message ?: "未知错误"}）")
                }
                _uiState.update { it.copy(wbCredReport = lines.joinToString("\n")) }
                kotlinx.coroutines.delay(350)
            }

            val summary = buildString {
                append("检查完成：刷新 $refreshed 个，无需处理 $skipped 个")
                if (failed > 0) append("，失败 $failed 个")
            }
            _uiState.update {
                it.copy(wbCredBusy = false, wbCredReport = summary + "\n" + lines.joinToString("\n"))
            }
            refresh()
        }
    }

    /** 剩余有效期 → 人话；null 表示账号里没有这个字段（判断不了，不是已过期） */
    private fun fmtRemaining(hours: Double?): String = when {
        hours == null -> "有效期未知"
        hours <= 0 -> "已过期"
        hours < 48 -> "剩余 ${String.format(java.util.Locale.US, "%.1f", hours)} 小时"
        else -> "剩余 ${String.format(java.util.Locale.US, "%.1f", hours / 24)} 天"
    }

    // ==================== WorkBuddy 设备同步 ====================

    fun setWbSyncEndpoint(value: String) {
        repo.wbSyncEndpoint = value
        _uiState.update { it.copy(wbSyncEndpoint = value) }
    }

    fun setWbSyncPairCode(value: String) {
        repo.wbSyncPairCode = value
        _uiState.update { it.copy(wbSyncPairCode = value) }
    }

    fun setWbSyncForcePush(enabled: Boolean) {
        repo.wbSyncForcePush = enabled
        _uiState.update { it.copy(wbSyncForcePush = enabled) }
    }

    fun toggleWbSyncAccount(id: String, checked: Boolean) {
        _uiState.update { state ->
            state.copy(wbSyncSelected = if (checked) state.wbSyncSelected + id else state.wbSyncSelected - id)
        }
    }

    fun selectAllWbSync(checked: Boolean) {
        _uiState.update { state ->
            state.copy(
                wbSyncSelected = if (checked) state.wbAccounts.map { it.id }.toSet() else emptySet(),
                wbSyncSelectionReady = true,
            )
        }
    }

    /**
     * 一键同步选中账号：**刷新 → 推送 → 取回**。
     *
     * 为什么合并成一个按钮：refresh_token 是轮换式的，刷新成功后电脑端手里的旧值立刻作废。
     * 之前「刷新」和「推送」是两个按钮，刷完忘了推，电脑端那个账号就静默失效了。
     * 现在一次点击走完整条链路，中间不留"刷了但没推"的窗口。
     *
     * 没配电脑地址 / 配对码时退化成"只刷新本地"，并在结果里说明原因。
     */
    fun syncSelectedAccounts() {
        if (_uiState.value.wbSyncBusy) return
        viewModelScope.launch {
            val outcome = refreshSelectedInternal() ?: return@launch
            val prefix = buildString {
                append("已刷新 ${outcome.ok} 个")
                if (outcome.failed > 0) append("（失败 ${outcome.failed}）")
                append("；")
            }
            pushInternal(prefix)
        }
    }

    /** 一次刷新的结果 */
    private data class RefreshOutcome(val ok: Int, val failed: Int)

    /**
     * 刷新选中账号（内部实现）。
     *
     * @return null 表示前置校验没过（没勾选 / 没有账号），提示已写入状态
     */
    private suspend fun refreshSelectedInternal(): RefreshOutcome? {
        val state = _uiState.value
        val targets = state.wbAccounts.filter { it.id in state.wbSyncSelected }
        if (targets.isEmpty()) {
            _uiState.update {
                it.copy(
                    wbSyncStatus = if (state.wbAccounts.isEmpty()) {
                        "还没有 WorkBuddy 账号，先到账号页扫码或导入"
                    } else {
                        "请先勾选要同步的账号（当前 0/${state.wbAccounts.size}）"
                    },
                )
            }
            return null
        }

        _uiState.update {
            it.copy(
                wbSyncBusy = true,
                wbSyncStatus = "正在刷新 ${targets.size} 个账号…",
                wbSyncResults = emptyMap(),
            )
        }
        // 尽力先取回电脑端刷新过的凭据，避免拿旧 token 去刷（失败不影响刷新）
        runCatching { pullPendingInternal() }

        val results = LinkedHashMap<String, String>()
        var ok = 0
        var failed = 0
        for (acc in targets) {
            // 每个账号都重新读盘：上一个账号刷新可能改了列表
            val current = accounts.workBuddyAccounts().firstOrNull { it.id == acc.id } ?: acc
            val message = try {
                if (current.refreshToken.isBlank()) {
                    failed++
                    "没有 refresh_token，需重新扫码"
                } else {
                    val r = refresher.refresh(current.refreshToken, current.uid)
                    val newRefresh = r.refreshToken.ifEmpty { current.refreshToken }
                    val expSec = if (r.expiresAtMs > 0) r.expiresAtMs / 1000 else current.expiresAt
                    val refreshExpSec =
                        if (r.refreshExpiresAtMs > 0) r.refreshExpiresAtMs / 1000 else current.refreshExpiresAt
                    val written = accounts.updateWorkBuddyCredential(
                        uid = current.uid.ifEmpty { current.id },
                        accessToken = r.accessToken,
                        refreshToken = newRefresh,
                        expiresAt = expSec,
                        refreshExpiresAt = refreshExpSec,
                        obtainedAt = System.currentTimeMillis(),
                    )
                    if (written) {
                        ok++
                        "已刷新"
                    } else {
                        failed++
                        "本机已找不到该账号"
                    }
                }
            } catch (e: Exception) {
                failed++
                e.message ?: "刷新失败"
            }
            results[acc.id] = message
            _uiState.update {
                it.copy(
                    wbSyncResults = results.toMap(),
                    wbSyncStatus = "刷新中 ${results.size}/${targets.size}…",
                )
            }
            // 与电脑端一致的小间隔：连续打官方刷新接口容易触发风控
            kotlinx.coroutines.delay(350)
        }
        _uiState.update { it.copy(wbSyncResults = results) }
        return RefreshOutcome(ok, failed)
    }

    /**
     * 推送到电脑端并回写电脑端返回的最新凭据（内部实现）。
     *
     * @param prefix 结果前缀，合并模式下用来带上"已刷新 N 个"
     * @return 是否成功；失败时状态里已有可读原因
     */
    private suspend fun pushInternal(prefix: String = ""): Boolean {
        val state = _uiState.value
        val endpoint = try {
            LanSyncProtocol.parseEndpoint(state.wbSyncEndpoint)
        } catch (e: Exception) {
            _uiState.update {
                it.copy(
                    wbSyncBusy = false,
                    wbSyncStatus = prefix + (e.message ?: "电脑地址不正确") + "，仅刷新了本地",
                )
            }
            return false
        }
        if (state.wbSyncPairCode.isBlank()) {
            _uiState.update { it.copy(wbSyncBusy = false, wbSyncStatus = prefix + "未填配对码，仅刷新了本地") }
            return false
        }
        // 只推勾选的账号：未勾选的不该被这次操作带着一起改
        val list = accounts.workBuddyAccounts().filter { it.id in state.wbSyncSelected }
        if (list.isEmpty()) {
            _uiState.update { it.copy(wbSyncBusy = false, wbSyncStatus = prefix + "没有勾选可推送的账号") }
            return false
        }

        _uiState.update { it.copy(wbSyncBusy = true, wbSyncStatus = prefix + "正在推送到 ${endpoint.display}…") }
        return try {
            val payloads = list.map {
                LanSyncProtocol.Payload(
                    uid = it.uid.ifEmpty { it.id },
                    label = it.label,
                    enterpriseId = it.enterpriseId,
                    accessToken = it.token,
                    refreshToken = it.refreshToken,
                    expiresAt = it.expiresAt,
                    obtainedAt = it.obtainedAt,
                )
            }
            val resp = syncClient.push(
                endpoint = endpoint,
                pairCode = state.wbSyncPairCode,
                accounts = payloads,
                deviceName = deviceName(),
                force = state.wbSyncForcePush,
            )

            var written = 0
            val perAccount = LinkedHashMap<String, String>()
            resp.results.forEach { r ->
                val acc = r.account
                if (acc == null || (acc.token.isEmpty() && acc.refreshToken.isEmpty())) {
                    perAccount[r.uid] = describe(r.action)
                    return@forEach
                }
                val ok = accounts.updateWorkBuddyCredential(
                    uid = acc.uid.ifEmpty { r.uid },
                    accessToken = acc.token,
                    refreshToken = acc.refreshToken,
                    expiresAt = acc.expiresAt,
                    refreshExpiresAt = acc.refreshExpiresAt,
                    obtainedAt = if (r.action == "keep-local" || r.action == "same") 0L else System.currentTimeMillis(),
                    syncVersion = acc.version,
                )
                if (ok) written++
                perAccount[acc.uid.ifEmpty { r.uid }] = buildString {
                    append(describe(r.action))
                    if (r.message.isNotBlank()) append("：").append(r.message)
                    if (r.risk.isNotBlank()) append("（").append(r.risk).append("）")
                }
            }

            // 推送过程中电脑端可能刚刷新过（入队），再拉一次把最新值取回
            val pulled = runCatching { pullPendingInternal() }.getOrDefault(0)

            val now = System.currentTimeMillis()
            repo.wbSyncLastAt = now
            _uiState.update {
                it.copy(
                    wbSyncBusy = false,
                    wbSyncLastAt = now,
                    wbSyncResults = perAccount,
                    wbSyncStatus = prefix + "已同步 $written 个" +
                        if (pulled > 0) "，并取回 $pulled 个电脑端刷新结果" else "",
                )
            }
            refresh()
            true
        } catch (e: IOException) {
            _uiState.update {
                it.copy(
                    wbSyncBusy = false,
                    wbSyncStatus = prefix + "连不上电脑（${e.message}）：确认同一 Wi-Fi、地址端口正确、电脑端已开启同步",
                )
            }
            false
        } catch (e: Exception) {
            _uiState.update {
                it.copy(wbSyncBusy = false, wbSyncStatus = prefix + (e.message ?: "推送失败"))
            }
            false
        }
    }

    /**
     * 拉取电脑端"刷新过但还没通知手机"的凭据并写回本地。
     * 电脑端已确认出队（ack 失败也不影响本地已写入）。
     *
     * @return 实际写回的账号数
     */
    private suspend fun pullPendingInternal(): Int {
        val state = _uiState.value
        val endpoint = runCatching { LanSyncProtocol.parseEndpoint(state.wbSyncEndpoint) }.getOrNull() ?: return 0
        if (state.wbSyncPairCode.isBlank()) return 0
        val items = syncClient.pending(endpoint, state.wbSyncPairCode)
        if (items.isEmpty()) return 0
        var applied = 0
        for (item in items) {
            // 电脑端对加密信封账号会回传空 token，跳过即可（不能拿空值覆盖本地）
            if (item.token.isEmpty() && item.refreshToken.isEmpty()) continue
            val ok = accounts.updateWorkBuddyCredential(
                uid = item.uid,
                accessToken = item.token,
                refreshToken = item.refreshToken,
                expiresAt = item.expiresAt,
                refreshExpiresAt = item.refreshExpiresAt,
                obtainedAt = System.currentTimeMillis(),
            )
            if (ok) applied++
        }
        runCatching { syncClient.ack(endpoint, state.wbSyncPairCode, items.map { it.id }) }
        return applied
    }

    private fun deviceName(): String = runCatching { Build.MODEL.ifBlank { "android" } }.getOrDefault("android")

    /** 把电脑端的 action 翻成中文，避免界面上出现一串英文枚举 */
    private fun describe(action: String): String = when (action) {
        "create" -> "电脑端已新建"
        "adopt" -> "电脑端已采纳手机凭据"
        "keep-local" -> "保留电脑端凭据"
        "same" -> "两端一致"
        "skipped-current" -> "电脑端正在使用，未改动"
        "skipped-encrypted" -> "电脑端是加密凭据，无法回传"
        "skipped-version" -> "电脑端凭据更新，已忽略旧值"
        "skipped-dead" -> "凭据已失效"
        "skipped-duplicate" -> "重复账号，已跳过"
        else -> action
    }

    // ==================== 通知与完成总览 ====================

    fun setTaskNotification(enabled: Boolean) {
        repo.taskNotification = enabled
        _uiState.update { it.copy(taskNotification = enabled) }
    }

    fun setOverviewAutoDismiss(enabled: Boolean) {
        repo.overviewAutoDismiss = enabled
        _uiState.update { it.copy(overviewAutoDismiss = enabled) }
    }

    fun setOverviewHoldSeconds(seconds: Float) {
        repo.overviewHoldSeconds = seconds.coerceIn(0.05f, 30f)
        _uiState.update { it.copy(overviewHoldSeconds = repo.overviewHoldSeconds) }
    }
}
