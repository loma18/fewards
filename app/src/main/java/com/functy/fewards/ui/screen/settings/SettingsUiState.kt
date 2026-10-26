package com.functy.fewards.ui.screen.settings

import androidx.compose.runtime.Immutable
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamiccolor.ColorSpec

@Immutable
data class SettingsUiState(
    val themeMode: Int = 0,
    val miuixMonet: Boolean = false,
    val keyColor: Int = 0,
    val colorStyle: String = PaletteStyle.TonalSpot.name,
    val colorSpec: String = ColorSpec.SpecVersion.SPEC_2025.name,
    val predictiveBackAnimation: Int = 2,
    val enableBlur: Boolean = true,
    val enableFloatingBottomBar: Boolean = false,
    val enableFloatingBottomBarBlur: Boolean = false,
    val pageScale: Float = 1.0f,

    // WorkBuddy
    val wbMasterEnabled: Boolean = true,
    /** 签到后自动跑成长中心（旅行 / 任务 / 补登 / 兑换 / 盲盒） */
    val wbGrowthEnabled: Boolean = true,
    val wbGrowthBusy: Boolean = false,
    /** 最近一次一键领取的汇总（逐条明细也拼在里面） */
    val wbGrowthReport: String = "",
    /** 凭据临期自动刷新 */
    val wbAutoRefresh: Boolean = true,
    val wbCredBusy: Boolean = false,
    val wbCredReport: String = "",

    // WorkBuddy 设备同步（与电脑端 workbuddy-console 配对）
    /** 电脑地址，形如 `192.168.1.5:57900` */
    val wbSyncEndpoint: String = "",
    val wbSyncPairCode: String = "",
    /** true = 以手机为准覆盖电脑端；默认 false 走"谁新信谁" */
    val wbSyncForcePush: Boolean = false,
    val wbSyncLastAt: Long = 0L,
    val wbSyncBusy: Boolean = false,
    /** 一句话状态：正在做什么 / 失败原因 / 结果汇总 */
    val wbSyncStatus: String = "",
    /** 账号 id → 该账号最近一次刷新或同步的结果 */
    val wbSyncResults: Map<String, String> = emptyMap(),
    /** 参与刷新的账号 id（默认全选；账号增删后由 ViewModel 补齐） */
    val wbSyncSelected: Set<String> = emptySet(),
    /** 勾选集合是否初始化过：false 时 refresh 会执行"默认全选" */
    val wbSyncSelectionReady: Boolean = false,
    /** WorkBuddy 账号列表（设置页要展示勾选清单） */
    val wbAccounts: List<com.functy.fewards.data.repository.AccountRepository.WorkBuddyAccount> = emptyList(),

    // 通知与完成总览
    val taskNotification: Boolean = true,
    val overviewAutoDismiss: Boolean = true,
    val overviewHoldSeconds: Float = 0.5f,
)

@Immutable
data class SettingsScreenActions(
    val onOpenTheme: () -> Unit,
    // WorkBuddy
    val onSetWbMaster: (Boolean) -> Unit,
    /** 签到后自动跑成长中心 */
    val onSetWbGrowth: (Boolean) -> Unit,
    /** 手动「一键领取」：立即跑全部成长中心动作 */
    val onClaimGrowth: () -> Unit,
    /** 凭据临期自动刷新开关 */
    val onSetWbAutoRefresh: (Boolean) -> Unit,
    /** 「检查并刷新」：对临期账号执行刷新 */
    val onCheckRefreshCredentials: () -> Unit,
    // WorkBuddy 设备同步
    val onSetWbSyncEndpoint: (String) -> Unit,
    val onSetWbSyncPairCode: (String) -> Unit,
    val onSetWbSyncForcePush: (Boolean) -> Unit,
    val onToggleWbSyncAccount: (String, Boolean) -> Unit,
    val onSelectAllWbSync: (Boolean) -> Unit,
    /** 一键同步选中账号：刷新 → 推送 → 取回（刷新会轮换 refresh_token） */
    val onSyncWbAccounts: () -> Unit,
    // 通知与完成总览
    val onSetTaskNotification: (Boolean) -> Unit,
    val onSetOverviewAutoDismiss: (Boolean) -> Unit,
    val onSetOverviewHoldSeconds: (Float) -> Unit,
    // 其他
    val onOpenAbout: () -> Unit,
)

internal val OVERVIEW_HOLD_PRESETS = listOf(0.1f, 0.3f, 0.5f, 1.0f)

internal fun formatHoldSeconds(seconds: Float): String {
    val value = if (seconds == seconds.toLong().toFloat()) {
        seconds.toLong().toString()
    } else {
        seconds.toString().trimEnd('0').trimEnd('.')
    }
    return value
}
