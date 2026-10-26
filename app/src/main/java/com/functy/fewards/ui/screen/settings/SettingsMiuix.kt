package com.functy.fewards.ui.screen.settings

import androidx.compose.foundation.clickable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Gamepad
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.rounded.HourglassBottom
import androidx.compose.material.icons.rounded.Notifications
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.functy.fewards.core.workbuddy.WorkBuddyVariant
import com.functy.fewards.fewardsApp
import com.functy.fewards.work.TaskNotifier
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Recommend
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.ThumbUp
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.WorkspacePremium
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.functy.fewards.R
import com.functy.fewards.ui.component.miuix.MultilineInputField
import com.functy.fewards.ui.theme.LocalEnableBlur
import com.functy.fewards.ui.util.BlurredBar
import com.functy.fewards.ui.util.rememberBlurBackdrop
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.Alignment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.OverlaySpinnerPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/**
 * 设置页（Miuix）：大标题「设置」，从上到下为界面组、WorkBuddy 板块、通知与完成总览。
 *
 * 导入 / 导出配置与「关于」两处已按产品要求移除：配置迁移改为「从电脑同步凭据」，
 * 版本信息留在 README 与 Release 页。不要再加回来。
 */
@Composable
fun SettingPagerMiuix(
    uiState: SettingsUiState,
    actions: SettingsScreenActions,
    bottomInnerPadding: Dp,
) {
    val scrollBehavior = MiuixScrollBehavior()
    val enableBlur = LocalEnableBlur.current
    val backdrop = rememberBlurBackdrop(enableBlur)
    val blurActive = backdrop != null
    val barColor = if (blurActive) Color.Transparent else colorScheme.surface
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val view = LocalView.current
    val dismissInput = {
        focusManager.clearFocus(force = true)
        keyboardController?.hide()
        view.clearFocus()
    }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) {
        dismissInput()
    }

    Scaffold(
        topBar = {
            BlurredBar(backdrop) {
                TopAppBar(
                    color = barColor,
                    title = stringResource(R.string.settings),
                    scrollBehavior = scrollBehavior
                )
            }
        },
        contentWindowInsets = WindowInsets.systemBars.add(WindowInsets.displayCutout).only(WindowInsetsSides.Horizontal),
    ) { innerPadding ->
        Box(modifier = if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxHeight()
                    .scrollEndHaptic()
                    .overScrollVertical()
                    .nestedScroll(scrollBehavior.nestedScrollConnection)
                    .pointerInput(Unit) {
                        detectTapGestures(onTap = { dismissInput() })
                    }
                    .padding(horizontal = 12.dp),
                contentPadding = innerPadding,
                overscrollEffect = null,
            ) {
                item {
                    // ==================== 界面 ====================
                    Card(
                        modifier = Modifier
                            .padding(top = 12.dp)
                            .fillMaxWidth(),
                    ) {
                        ArrowPreference(
                            title = stringResource(id = R.string.settings_theme),
                            summary = stringResource(id = R.string.settings_theme_summary),
                            startAction = {
                                Icon(
                                    Icons.Rounded.Palette,
                                    modifier = Modifier.padding(end = 6.dp),
                                    contentDescription = stringResource(id = R.string.settings_theme),
                                    tint = colorScheme.onBackground
                                )
                            },
                            onClick = {
                                dismissInput()
                                actions.onOpenTheme()
                            }
                        )
                    }

                    // ==================== WorkBuddy ====================
                    Card(
                        modifier = Modifier
                            .padding(top = 12.dp)
                            .fillMaxWidth(),
                    ) {
                        SwitchPreference(
                            title = stringResource(id = R.string.settings_wb_master),
                            summary = stringResource(id = R.string.settings_wb_master_summary),
                            startAction = {
                                Icon(
                                    Icons.Rounded.Shield,
                                    modifier = Modifier.padding(end = 6.dp),
                                    contentDescription = stringResource(id = R.string.settings_wb_master),
                                    tint = colorScheme.onBackground
                                )
                            },
                            checked = uiState.wbMasterEnabled,
                            onCheckedChange = actions.onSetWbMaster
                        )

                        // ===== 签到与成长中心 =====
                        SwitchPreference(
                            title = "签到后自动领取成长奖励",
                            summary = "旅行礼物 / 派 Buddy / 任务 / 断登补签 / 连登兑换 / 开盲盒 / Buddy 盲盒",
                            checked = uiState.wbGrowthEnabled,
                            onCheckedChange = actions.onSetWbGrowth,
                        )
                        TextButton(
                            text = if (uiState.wbGrowthBusy) "领取中…" else "一键领取",
                            onClick = actions.onClaimGrowth,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                            colors = ButtonDefaults.textButtonColorsPrimary(),
                        )
                        if (uiState.wbGrowthReport.isNotEmpty()) {
                            Text(
                                text = uiState.wbGrowthReport,
                                fontSize = 11.sp,
                                color = colorScheme.onSurfaceVariantSummary,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
                            )
                        }

                        // ===== 凭据维护 =====
                        SwitchPreference(
                            title = "凭据临期自动刷新",
                            summary = "access 剩余不足 24 小时、或 refresh 不足 72 小时时自动刷新；默认开启",
                            checked = uiState.wbAutoRefresh,
                            onCheckedChange = actions.onSetWbAutoRefresh,
                        )
                        if (uiState.wbAccounts.isNotEmpty()) {
                            // access / refresh 是两条独立的时间线：前者过期只需换一份，
                            // 后者过期才是真的没救，所以分开显示，不合并成一句
                            uiState.wbAccounts.forEach { acc ->
                                Column(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 3.dp),
                                ) {
                                    Text(
                                        text = acc.label.ifEmpty {
                                            if (acc.uid.isNotEmpty()) "uid=${acc.uid.take(10)}" else "未命名账号"
                                        },
                                        fontSize = 12.sp,
                                        color = colorScheme.onSurfaceVariantSummary,
                                    )
                                    Text(
                                        text = "access " + wbRemaining(acc.expiresAt) +
                                            " · refresh " + wbRemaining(acc.refreshExpiresAt),
                                        fontSize = 11.sp,
                                        color = colorScheme.onSurfaceVariantSummary,
                                    )
                                }
                            }
                        }
                        TextButton(
                            text = if (uiState.wbCredBusy) "检查中…" else "检查并刷新",
                            onClick = actions.onCheckRefreshCredentials,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                            colors = ButtonDefaults.textButtonColorsPrimary(),
                        )
                        if (uiState.wbCredReport.isNotEmpty()) {
                            Text(
                                text = uiState.wbCredReport,
                                fontSize = 11.sp,
                                color = colorScheme.onSurfaceVariantSummary,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
                            )
                        }

                        WbSyncSection(uiState, actions)
                    }

                    // ==================== 通知与完成总览 ====================
                    Card(
                        modifier = Modifier
                            .padding(top = 12.dp)
                            .fillMaxWidth(),
                    ) {
                        SwitchPreference(
                            title = stringResource(id = R.string.settings_notification),
                            summary = stringResource(id = R.string.settings_notification_summary),
                            startAction = {
                                Icon(
                                    Icons.Rounded.Notifications,
                                    modifier = Modifier.padding(end = 6.dp),
                                    contentDescription = stringResource(id = R.string.settings_notification),
                                    tint = colorScheme.onBackground
                                )
                            },
                            checked = uiState.taskNotification,
                            onCheckedChange = actions.onSetTaskNotification
                        )
                        val context = LocalContext.current
                        var granted = remember {
                            mutableStateOf(
                                ContextCompat.checkSelfPermission(fewardsApp, Manifest.permission.POST_NOTIFICATIONS) ==
                                    PackageManager.PERMISSION_GRANTED
                            )
                        }
                        val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
                        androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
                            val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                                if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                                    granted.value = android.os.Build.VERSION.SDK_INT < 33 ||
                                        ContextCompat.checkSelfPermission(
                                            fewardsApp, Manifest.permission.POST_NOTIFICATIONS
                                        ) == PackageManager.PERMISSION_GRANTED
                                }
                            }
                            lifecycleOwner.lifecycle.addObserver(observer)
                            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                        }
                        val launcher = rememberLauncherForActivityResult(
                            ActivityResultContracts.RequestPermission()
                        ) { result -> granted.value = result }
                        if (uiState.taskNotification && !granted.value && android.os.Build.VERSION.SDK_INT >= 33) {
                            ArrowPreference(
                                title = stringResource(id = R.string.settings_request_notification),
                                summary = stringResource(R.string.settings_notification_denied),
                                startAction = {
                                    Icon(
                                        Icons.Rounded.Notifications,
                                        modifier = Modifier.padding(end = 6.dp),
                                        contentDescription = stringResource(id = R.string.settings_request_notification),
                                        tint = colorScheme.onBackground
                                    )
                                },
                                onClick = {
                                    launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                },
                            )
                        }
                        SwitchPreference(
                            title = stringResource(id = R.string.settings_overview_auto),
                            summary = stringResource(id = R.string.settings_overview_auto_summary),
                            startAction = {
                                Icon(
                                    Icons.Rounded.HourglassBottom,
                                    modifier = Modifier.padding(end = 6.dp),
                                    contentDescription = stringResource(id = R.string.settings_overview),
                                    tint = colorScheme.onBackground
                                )
                            },
                            checked = uiState.overviewAutoDismiss,
                            onCheckedChange = actions.onSetOverviewAutoDismiss
                        )
                        var showCustomHold by rememberSaveable { mutableStateOf(false) }
                        val holdLabels = OVERVIEW_HOLD_PRESETS.map { seconds ->
                            stringResource(R.string.settings_overview_hold_seconds, formatHoldSeconds(seconds))
                        } + stringResource(R.string.settings_overview_hold_custom)
                        val holdSelected = OVERVIEW_HOLD_PRESETS.indexOfFirst { it == uiState.overviewHoldSeconds }
                            .takeIf { it >= 0 } ?: OVERVIEW_HOLD_PRESETS.size
                        OverlaySpinnerPreference(
                            title = stringResource(id = R.string.settings_overview_hold),
                            summary = stringResource(
                                R.string.settings_overview_hold_seconds,
                                formatHoldSeconds(uiState.overviewHoldSeconds),
                            ),
                            items = holdLabels.map { DropdownItem(title = it) },
                            selectedIndex = holdSelected,
                            startAction = {
                                Icon(
                                    Icons.Rounded.Schedule,
                                    modifier = Modifier.padding(end = 6.dp),
                                    contentDescription = stringResource(id = R.string.settings_overview_hold),
                                    tint = colorScheme.onBackground
                                )
                            },
                            onSelectedIndexChange = { index ->
                                if (index in OVERVIEW_HOLD_PRESETS.indices) {
                                    actions.onSetOverviewHoldSeconds(OVERVIEW_HOLD_PRESETS[index])
                                } else {
                                    showCustomHold = true
                                }
                            },
                        )
                        OverviewHoldCustomDialog(
                            show = showCustomHold,
                            currentSeconds = uiState.overviewHoldSeconds,
                            onConfirm = {
                                actions.onSetOverviewHoldSeconds(it)
                                showCustomHold = false
                            },
                            onDismiss = { showCustomHold = false },
                        )
                    }

                    Spacer(Modifier.height(bottomInnerPadding))
                }
            }
        }
    }
}

@Composable
private fun OverviewHoldCustomDialog(
    show: Boolean,
    currentSeconds: Float,
    onConfirm: (Float) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember(show) { mutableStateOf(formatHoldSeconds(currentSeconds)) }
    OverlayDialog(
        show = show,
        title = stringResource(R.string.settings_overview_hold_custom_title),
        onDismissRequest = onDismiss,
        content = {
            TextField(
                modifier = Modifier.padding(bottom = 16.dp),
                value = text,
                maxLines = 1,
                trailingIcon = {
                    Text(
                        text = stringResource(R.string.settings_overview_hold_custom_hint),
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = colorScheme.onSurfaceVariantActions,
                    )
                },
                onValueChange = { incoming ->
                    if (incoming.isEmpty() || incoming.matches(Regex("^\\d*\\.?\\d*$"))) {
                        text = incoming
                    }
                },
            )
            Row(horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(
                    text = stringResource(R.string.cancel),
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(20.dp))
                TextButton(
                    text = stringResource(R.string.ok),
                    onClick = {
                        text.toFloatOrNull()?.let { onConfirm(it) } ?: onDismiss()
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        },
    )
}

/**
 * WorkBuddy 设备同步区块（嵌在 WorkBuddy 卡片里）。
 *
 * 三个动作刻意分开，因为它们的代价完全不同：
 * - **刷新**会轮换 refresh_token，刷完电脑端那份立刻失效，必须紧接着推送；
 * - **推送**只搬运凭据，不改变任何 token，随时可以点；
 * - **从电脑取回**只读电脑端，把手机端已失效账号的 token 补回来。
 *
 * 早先是"刷新 → 推送 → 取回"一个按钮，好处是刷完不会忘推，坏处是想单独续凭据、
 * 或者只想把手机救活时，都得连带做一次有代价的刷新。现在拆成三个，各自只做一件事。
 */
@Composable
private fun WbSyncSection(
    uiState: SettingsUiState,
    actions: SettingsScreenActions,
) {
    val allSelected =
        uiState.wbAccounts.isNotEmpty() && uiState.wbSyncSelected.size >= uiState.wbAccounts.size

    Column(modifier = Modifier.fillMaxWidth().padding(top = 2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (uiState.wbAccounts.isEmpty()) {
                    "设备同步"
                } else {
                    // 把"有几个账号、选中几个"直接写出来，避免列表存在但全未勾选时
                    // 用户以为"没有可选账号"
                    "选择要操作的账号（${uiState.wbSyncSelected.size}/${uiState.wbAccounts.size}）"
                },
                fontSize = 13.sp,
                color = colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.weight(1f),
            )
            if (uiState.wbAccounts.isNotEmpty()) {
                Text(
                    text = if (allSelected) "全不选" else "全选",
                    fontSize = 13.sp,
                    color = colorScheme.primary,
                    modifier = Modifier.clickable { actions.onSelectAllWbSync(!allSelected) },
                )
            }
        }

        if (uiState.wbAccounts.isEmpty()) {
            Text(
                text = "还没有 WorkBuddy 账号，先到账号页扫码添加。",
                fontSize = 12.sp,
                color = colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

        /**
         * 按档位分组列出：国内版 / 国际版是两套独立客户端与域名，
         * 勾选清单里混着排会让人看不出"这个账号到底要给哪一档用"。
         */
        WorkBuddyVariant.group(uiState.wbAccounts).forEach { (variant, list) ->
            Text(
                text = "${variant.label} · ${variant.appLabel}（${list.size}）",
                fontSize = 12.sp,
                color = colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
            )
            list.forEach { acc ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = acc.id in uiState.wbSyncSelected,
                        onCheckedChange = { actions.onToggleWbSyncAccount(acc.id, it) },
                        colors = CheckboxDefaults.colors(checkedColor = colorScheme.primary),
                    )
                    Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                        Text(
                            text = acc.label.ifEmpty {
                                if (acc.uid.isNotEmpty()) "uid=${acc.uid.take(10)}" else "未命名账号"
                            },
                            fontSize = 14.sp,
                            color = colorScheme.onBackground,
                        )
                        val note = uiState.wbSyncResults[acc.id]
                        if (!note.isNullOrEmpty()) {
                            Text(
                                text = note,
                                fontSize = 11.sp,
                                color = colorScheme.onSurfaceVariantSummary,
                            )
                        }
                    }
                }
            }
        }

        MultilineInputField(
            value = uiState.wbSyncEndpoint,
            onValueChange = actions.onSetWbSyncEndpoint,
            label = "电脑地址（192.168.1.5:57900）",
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
        MultilineInputField(
            value = uiState.wbSyncPairCode,
            onValueChange = actions.onSetWbSyncPairCode,
            label = "配对码（电脑端设置页显示）",
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )

        SwitchPreference(
            title = "以手机为准",
            summary = "开启后把手机凭据强制写入电脑端；电脑正在登录的那个账号仍会被保护，不会被覆盖。",
            checked = uiState.wbSyncForcePush,
            onCheckedChange = actions.onSetWbSyncForcePush,
        )

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(
                text = if (uiState.wbSyncBusy && uiState.wbSyncMode == WbSyncMode.Refresh) {
                    "刷新中…"
                } else {
                    "刷新选中账号"
                },
                onClick = actions.onRefreshWbAccounts,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
            TextButton(
                text = if (uiState.wbSyncBusy && uiState.wbSyncMode == WbSyncMode.Push) {
                    "同步中…"
                } else {
                    "同步选中账号"
                },
                onClick = actions.onSyncWbAccounts,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }

        TextButton(
            text = if (uiState.wbSyncBusy && uiState.wbSyncMode == WbSyncMode.Pull) {
                "读取中…"
            } else {
                "从电脑同步凭据"
            },
            onClick = actions.onPullWbCredentials,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
            colors = ButtonDefaults.textButtonColorsPrimary(),
        )

        if (uiState.wbSyncStatus.isNotEmpty()) {
            Text(
                text = uiState.wbSyncStatus,
                fontSize = 12.sp,
                color = colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
            )
        }

        if (uiState.wbSyncLastAt > 0) {
            Text(
                text = "上次同步：" + java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US)
                    .format(java.util.Date(uiState.wbSyncLastAt)),
                fontSize = 11.sp,
                color = colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
            )
        }

        Text(
            text = "三个动作代价不同，所以分开：\n" +
                "· 刷新选中账号：只续本机凭据，不碰电脑。会换掉 refresh_token，电脑端那份随即失效，" +
                "刷完记得点「同步选中账号」。\n" +
                "· 同步选中账号：只把本机凭据推到电脑，再取回电脑端最新凭据，不刷新。\n" +
                "· 从电脑同步凭据：手机上失效的账号去电脑端补 token；手机上没有的账号直接按电脑端" +
                "新建过来（不必先扫码）。本机仍有效的账号不动。\n" +
                "· 国内版与国际版分开显示：两档是两套客户端、两套域名，积分不互通，token 也不能混用。" +
                "从电脑同步时会连档位一起带回来；国际版暂不支持签到与成长中心。",
            fontSize = 11.sp,
            color = colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        )
    }
}

/**
 * 账号剩余有效期。
 *
 * `expiresAt` 是**秒**（扫码时 `currentTimeMillis()/1000 + expiresIn`），这里换成毫秒再比。
 * 0 表示账号里没有这个字段——是"判断不了"，不能当成已过期。
 */
private fun wbRemaining(expiresAtSec: Long): String {
    if (expiresAtSec <= 0) return "未知"
    val hours = (expiresAtSec * 1000 - System.currentTimeMillis()) / 3_600_000.0
    return when {
        hours <= 0 -> "已过期"
        hours < 48 -> "剩余 " + String.format(java.util.Locale.US, "%.1f", hours) + " 小时"
        else -> "剩余 " + String.format(java.util.Locale.US, "%.1f", hours / 24) + " 天"
    }
}
