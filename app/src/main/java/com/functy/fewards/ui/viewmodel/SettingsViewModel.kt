package com.functy.fewards.ui.viewmodel

import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.functy.fewards.core.lansync.LanSyncClient
import com.functy.fewards.core.lansync.LanSyncProtocol
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
                // 首次加载默认全选；之后只做"删掉已不存在账号"的收敛，
                // 这样用户主动取消勾选（包括全不选）不会被下一次 refresh 覆盖
                val selected = if (!state.wbSyncSelectionReady) allIds else state.wbSyncSelected intersect allIds
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
                    // 米游社
                    mhyMasterEnabled = repo.mhyMasterEnabled,
                    mhyGameSign = repo.mhyGameSign,
                    mhyBbsSign = repo.mhyBbsSign,
                    mhyRead = repo.mhyRead,
                    mhyLike = repo.mhyLike,
                    mhyCancelLike = repo.mhyCancelLike,
                    mhyShare = repo.mhyShare,
                    mhyCaptchaPolicy = repo.mhyCaptchaPolicy,
                    mhyCaptchaApiUrl = repo.mhyCaptchaApiUrl,
                    // WorkBuddy
                    wbMasterEnabled = repo.wbMasterEnabled,
                    wbAccounts = wbList,
                    wbSyncSelected = selected,
                    wbSyncEndpoint = repo.wbSyncEndpoint,
                    wbSyncPairCode = repo.wbSyncPairCode,
                    wbSyncForcePush = repo.wbSyncForcePush,
                    wbSyncLastAt = repo.wbSyncLastAt,
                    // Bing
                    bingMasterEnabled = repo.bingMasterEnabled,
                    bingAppCheckIn = repo.bingAppCheckIn,
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

    // ==================== 米游社 ====================

    fun setMhyMasterEnabled(enabled: Boolean) {
        repo.mhyMasterEnabled = enabled
        _uiState.update { it.copy(mhyMasterEnabled = enabled) }
    }

    fun setMhyGameSign(enabled: Boolean) {
        repo.mhyGameSign = enabled
        _uiState.update { it.copy(mhyGameSign = enabled) }
    }

    fun setMhyBbsSign(enabled: Boolean) {
        repo.mhyBbsSign = enabled
        _uiState.update { it.copy(mhyBbsSign = enabled) }
    }

    fun setMhyRead(enabled: Boolean) {
        repo.mhyRead = enabled
        _uiState.update { it.copy(mhyRead = enabled) }
    }

    fun setMhyLike(enabled: Boolean) {
        repo.mhyLike = enabled
        _uiState.update { it.copy(mhyLike = enabled) }
    }

    fun setMhyCancelLike(enabled: Boolean) {
        repo.mhyCancelLike = enabled
        _uiState.update { it.copy(mhyCancelLike = enabled) }
    }

    fun setMhyShare(enabled: Boolean) {
        repo.mhyShare = enabled
        _uiState.update { it.copy(mhyShare = enabled) }
    }

    fun setMhyCaptchaPolicy(policy: Int) {
        repo.mhyCaptchaPolicy = policy
        _uiState.update { it.copy(mhyCaptchaPolicy = policy) }
    }

    fun setMhyCaptchaApiUrl(url: String) {
        repo.mhyCaptchaApiUrl = url
        _uiState.update { it.copy(mhyCaptchaApiUrl = url) }
    }

    // ==================== WorkBuddy ====================

    fun setWbMasterEnabled(enabled: Boolean) {
        repo.wbMasterEnabled = enabled
        _uiState.update { it.copy(wbMasterEnabled = enabled) }
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
     * 刷新选中的账号。
     *
     * 刷新会**轮换 refresh_token**：成功后旧值立刻作废，电脑端若持有同一账号的副本会失效。
     * 所以刷新前先拉一次电脑端的待回传队列（确保拿到的是最新凭据），刷新后提示用户尽快推送。
     */
    fun refreshWbAccounts() {
        val state = _uiState.value
        if (state.wbSyncBusy) return
        val targets = state.wbAccounts.filter { it.id in state.wbSyncSelected }
        if (targets.isEmpty()) {
            _uiState.update { it.copy(wbSyncStatus = "请先勾选要刷新的账号") }
            return
        }
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    wbSyncBusy = true,
                    wbSyncStatus = "正在刷新 ${targets.size} 个账号…",
                    wbSyncResults = emptyMap(),
                )
            }
            // 尽力先对齐电脑端：失败不影响刷新（可能用户本来就没配同步）
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
            _uiState.update {
                it.copy(
                    wbSyncBusy = false,
                    wbSyncResults = results,
                    wbSyncStatus = buildString {
                        append("刷新完成：成功 $ok")
                        if (failed > 0) append("，失败 $failed")
                        if (ok > 0 && repo.wbSyncEndpoint.isNotBlank()) append("。已轮换 refresh_token，请尽快推送，否则电脑端该账号会失效")
                    },
                )
            }
            refresh()
        }
    }

    /**
     * 推送当前凭据到电脑端，并把电脑端回传的最新凭据写回本地。
     *
     * 一次往返完成双向对齐：推送 → 电脑端裁决/刷新 → 回传 → 本地覆盖。
     */
    fun pushToDesktop() {
        val state = _uiState.value
        if (state.wbSyncBusy) return
        val endpoint = try {
            LanSyncProtocol.parseEndpoint(state.wbSyncEndpoint)
        } catch (e: Exception) {
            _uiState.update { it.copy(wbSyncStatus = e.message ?: "地址不正确") }
            return
        }
        if (state.wbSyncPairCode.isBlank()) {
            _uiState.update { it.copy(wbSyncStatus = "请先填写电脑端设置页显示的配对码") }
            return
        }
        val list = accounts.workBuddyAccounts()
        if (list.isEmpty()) {
            _uiState.update { it.copy(wbSyncStatus = "本机还没有 WorkBuddy 账号") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(wbSyncBusy = true, wbSyncStatus = "正在推送到 ${endpoint.display}…") }
            try {
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
                        wbSyncStatus = "同步完成：回写 $written 个" +
                            if (pulled > 0) "，取回 $pulled 个电脑端刷新结果" else "",
                    )
                }
                refresh()
            } catch (e: IOException) {
                _uiState.update {
                    it.copy(
                        wbSyncBusy = false,
                        wbSyncStatus = "连不上电脑（${e.message}）：确认手机与电脑在同一 Wi-Fi，地址端口正确，且电脑端已开启同步",
                    )
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(wbSyncBusy = false, wbSyncStatus = e.message ?: "同步失败") }
            }
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

    // ==================== Bing ====================

    fun setBingMasterEnabled(enabled: Boolean) {
        repo.bingMasterEnabled = enabled
        _uiState.update { it.copy(bingMasterEnabled = enabled) }
    }

    fun setBingAppCheckIn(enabled: Boolean) {
        repo.bingAppCheckIn = enabled
        _uiState.update { it.copy(bingAppCheckIn = enabled) }
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
