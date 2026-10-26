package com.functy.fewards.ui.screen.account

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Star
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.functy.fewards.R
import com.functy.fewards.core.workbuddy.WorkBuddyCredits
import com.functy.fewards.data.repository.AccountRepository
import com.functy.fewards.ui.component.IconSquircleCornerFraction
import com.functy.fewards.ui.component.miuix.AddAccountDialog
import com.functy.fewards.ui.component.miuix.WbCreditsDialog
import com.functy.fewards.ui.viewmodel.AccountViewModel
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.TopAppBarDefaults
import top.yukonga.miuix.kmp.squircle.squircleClip
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/**
 * 账号页（Miuix）：只管理 WorkBuddy 账号。
 *
 * 结构：
 *  - 顶部积分汇总卡：总剩余可用积分 + 3 日内 / 5 日内到期可用积分；
 *  - WorkBuddy 账号列表：每行显示该账号的剩余与 3/5 日内到期额度，
 *    行尾星标按钮打开逐笔积分明细；
 *  - 右上角「+」直接打开添加弹窗（只剩一种账号类型，不再需要二级菜单）。
 *
 * 米游社与 Bing 已移除，历史账号数据不再读取。
 */
@Composable
fun AccountPagerMiuix(
    accountViewModel: AccountViewModel,
    bottomInnerPadding: Dp,
) {
    val scrollBehavior = MiuixScrollBehavior()
    val state by accountViewModel.uiState.collectAsStateWithLifecycle()
    val actions = accountViewModel.accountActions
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

    // TopAppBar 把 actions 垂直居中在「收起高度」（52dp）里，而大标题排在 52dp 之下。
    // 用与大标题同款的 title1 量一次行高，把「+」往下挪到和大标题同一条中线上；
    // 滚动收起时再按 collapsedFraction 收回，免得图标掉到收起栏下面。
    val textMeasurer = rememberTextMeasurer()
    val titleLineHeightPx = with(LocalDensity.current) {
        textMeasurer.measure(
            text = AnnotatedString(stringResource(R.string.account_title)),
            style = MiuixTheme.textStyles.title1,
        ).size.height
    }
    val plusOffsetPx = with(LocalDensity.current) {
        (TopAppBarDefaults.CollapsedHeight / 2).toPx() + titleLineHeightPx / 2f
    }

    // show* 驱动 WindowDialog 的开合；mounted* 保证关闭动画播完之前不卸载弹窗
    // （切到 Token Tab 会立刻把 qrState 归零，只靠 qrState 判断会动画演一半就消失）
    var showWbDialog by remember { mutableStateOf(false) }
    var wbDialogMounted by remember { mutableStateOf(false) }

    // 扫码成功才自动关弹窗；过期 / 失败保持打开以露出「重新获取」
    LaunchedEffect(state.wbQrState) {
        if (state.wbQrState == QrState.Confirmed) showWbDialog = false
    }
    // 停在扫码 Tab 且没有活着的会话时申请二维码。
    // 以「弹窗开合 + Tab」为 key：弹窗弹出动画（folme spring）结束后才申请，
    // 切走再切回扫码 Tab 也会重新申请一张新码；过期 / 失败后重开同样能自愈。
    LaunchedEffect(showWbDialog, state.wbLoginMode) {
        if (!showWbDialog || state.wbLoginMode != 0) return@LaunchedEffect
        if (state.wbQrState == QrState.Waiting || state.wbQrState == QrState.Scanned) return@LaunchedEffect
        kotlinx.coroutines.delay(450)
        if (showWbDialog && state.wbLoginMode == 0) actions.onStartWbQr()
    }

    if (wbDialogMounted) {
        AddAccountDialog(
            show = showWbDialog,
            title = stringResource(R.string.account_add_workbuddy),
            tabs = listOf(
                stringResource(R.string.workbuddy_login_qr),
                stringResource(R.string.workbuddy_login_token),
            ),
            selectedTab = state.wbLoginMode,
            onTabSelected = { tab ->
                dismissInput()
                actions.onSetWbLoginMode(tab)
                // 离开扫码 Tab 时停掉轮询；再切回扫码时上面那个 LaunchedEffect 会重新申请一张新码
                if (tab != 0) actions.onCancelWbQr()
            },
            // 空闲 / 加载中不显示上一轮的二维码（切走再切回时先转圈，而不是闪一张旧码）；
            // 过期 / 失败仍保留那张码，配合「已失效 + 重新获取」。
            qrContent = when (state.wbQrState) {
                QrState.Idle, QrState.Loading -> ""
                else -> state.wbQrContent
            },
            qrMessage = buildString {
                append(
                    stringResource(
                        when (state.wbQrState) {
                            QrState.Scanned -> R.string.workbuddy_qr_scanned
                            QrState.Expired -> R.string.qr_expired
                            QrState.Error -> R.string.qr_failed
                            else -> R.string.workbuddy_qr_wait_scan
                        }
                    )
                )
                // 失败时把真实原因带上，否则用户只能看到一句无法排查的通用文案
                if (state.wbQrState == QrState.Error && state.wbQrError.isNotEmpty()) {
                    append("：").append(state.wbQrError)
                }
            },
            canRetry = state.wbQrState == QrState.Expired || state.wbQrState == QrState.Error,
            credentialLabel = stringResource(R.string.workbuddy_token_hint),
            credentialAction = stringResource(R.string.workbuddy_token_import),
            onImportCredential = { raw ->
                dismissInput()
                val accepted = actions.onImportWbToken(raw)
                if (accepted) showWbDialog = false
                accepted
            },
            onDismissRequest = { showWbDialog = false },
            onDismissFinished = {
                wbDialogMounted = false
                actions.onCancelWbQr()
            },
            onRetry = { actions.onStartWbQr() },
        )
    }

    // 积分明细弹窗：常驻挂载，靠 show 控制开合（关闭动画期间还要渲染上一份数据）
    WbCreditsDialog(
        show = state.wbCreditDetailId.isNotEmpty(),
        accountLabel = state.wbAccounts.firstOrNull { it.id == state.wbCreditDetailId }?.label.orEmpty(),
        credit = state.wbCredits[state.wbCreditDetailId] ?: WbCreditState(),
        detail = state.wbCreditDetail,
        onDismissRequest = { actions.onCloseWbCredits() },
        onRefresh = {
            if (state.wbCreditDetailId.isNotEmpty()) actions.onRefreshWbCredits(state.wbCreditDetailId)
        },
    )

    Scaffold(
        topBar = {
            TopAppBar(
                color = colorScheme.surface,
                title = stringResource(R.string.account_title),
                scrollBehavior = scrollBehavior,
                actions = {
                    Box(
                        // 和大标题对齐：actions 默认垂直居中在「收起高度」（52dp）里，
                        // 大标题却排在 52dp 之下，所以整体下移「半个收起高度 + 半个行高」。
                        // 滚动收起时按 collapsedFraction 收回原位；offset 的 lambda 在布局阶段读，
                        // 不订阅重组。
                        modifier = Modifier.offset {
                            val collapsed = scrollBehavior.state.collapsedFraction
                            IntOffset(0, (plusOffsetPx * (1f - collapsed)).roundToInt())
                        },
                    ) {
                        IconButton(
                            onClick = {
                                dismissInput()
                                actions.onSetWbLoginMode(0)
                                wbDialogMounted = true
                                showWbDialog = true
                            },
                        ) {
                            Icon(
                                Icons.Rounded.Add,
                                contentDescription = stringResource(R.string.account_add),
                                tint = colorScheme.onBackground,
                            )
                        }
                    }
                },
            )
        },
        popupHost = { },
        contentWindowInsets = WindowInsets.systemBars.add(WindowInsets.displayCutout).only(WindowInsetsSides.Horizontal)
    ) { innerPadding ->
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
            if (state.wbAccounts.isNotEmpty()) {
                item {
                    CreditSummaryCard(
                        accounts = state.wbAccounts,
                        credits = state.wbCredits,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
                item { SectionHeader(stringResource(R.string.workbuddy)) }
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column {
                            state.wbAccounts.forEach { account ->
                                WbAccountRow(
                                    account = account,
                                    credit = state.wbCredits[account.id],
                                    onViewCredits = {
                                        dismissInput()
                                        actions.onViewWbCredits(account.id)
                                    },
                                    onRemove = {
                                        dismissInput()
                                        actions.onRemoveWb(account.id)
                                    },
                                )
                            }
                        }
                    }
                }
            } else {
                item { EmptyHint() }
            }

            item { Spacer(Modifier.height(bottomInnerPadding)) }
        }
    }
}

/**
 * 顶部积分汇总：总剩余可用积分 + 3 日内 / 5 日内到期可用积分。
 *
 * 「到期」只统计**还没过期**的额度——已经过期的余额既不可用，也不该出现在"即将到期"里。
 * 查询失败 / 缺 uid 的账号不参与求和，但会在底部用小字说明有几个没统计进去，
 * 免得用户以为数字涵盖了全部账号。
 */
@Composable
private fun CreditSummaryCard(
    accounts: List<AccountRepository.WorkBuddyAccount>,
    credits: Map<String, WbCreditState>,
    modifier: Modifier = Modifier,
) {
    val summary = remember(accounts, credits) {
        CreditStats.summarize(accounts, credits, System.currentTimeMillis())
    }
    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp, 14.dp)) {
            Text(
                text = "总剩余可用积分",
                fontSize = 12.sp,
                color = colorScheme.onSurfaceVariantSummary,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = WorkBuddyCredits.format(summary.remaining),
                fontSize = 30.sp,
                fontWeight = FontWeight.SemiBold,
                color = colorScheme.onSurface,
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ExpiryStat(
                    label = "3 日内到期",
                    value = summary.expiringIn3Days,
                    emphasize = true,
                    modifier = Modifier.weight(1f),
                )
                ExpiryStat(
                    label = "5 日内到期",
                    value = summary.expiringIn5Days,
                    emphasize = false,
                    modifier = Modifier.weight(1f),
                )
            }
            val notes = buildList {
                if (summary.loading > 0) add("${summary.loading} 个账号查询中")
                if (summary.failed > 0) add("${summary.failed} 个账号查询失败")
                if (summary.skipped > 0) add("${summary.skipped} 个账号缺 uid 未统计")
            }
            if (notes.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = notes.joinToString(" · "),
                    fontSize = 11.sp,
                    color = colorScheme.onSurfaceVariantSummary,
                )
            }
        }
    }
}

/** 汇总卡里的一个到期额度小块 */
@Composable
private fun ExpiryStat(
    label: String,
    value: Double,
    emphasize: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(colorScheme.surfaceContainer)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Text(
            text = label,
            fontSize = 10.sp,
            color = colorScheme.onSurfaceVariantSummary,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = WorkBuddyCredits.format(value),
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            color = if (emphasize && value > 0) colorScheme.primary else colorScheme.onSurface,
        )
    }
}

/**
 * 一行 WorkBuddy 账号：头像 / 名称 + 剩余积分 + uid + 3、5 日内到期 + 两个按钮。
 *
 * 不用 BasicComponent：那里只有单行 summary，塞不下"剩余 + 两个到期窗口"。
 * 行高与左右留白按 BasicComponent 的口径（横向 16dp / 纵向 10dp）对齐，
 * 混在同一张卡片里看不出差异。
 */
@Composable
private fun WbAccountRow(
    account: AccountRepository.WorkBuddyAccount,
    credit: WbCreditState?,
    onViewCredits: () -> Unit,
    onRemove: () -> Unit,
) {
    val balance = credit?.balance
    val now = remember(credit) { System.currentTimeMillis() }
    val threeDays = CreditStats.expiringWithin(balance, 3, now)
    val fiveDays = CreditStats.expiringWithin(balance, 5, now)

    val remainingText = when {
        balance != null -> "剩余 " + WorkBuddyCredits.format(balance.remaining)
        credit?.loading == true -> "查询中…"
        credit?.error?.isNotEmpty() == true -> "查询失败"
        account.uid.isEmpty() -> "缺 uid"
        else -> "待查询"
    }
    val expiryText = when {
        balance != null ->
            "3 日内 " + WorkBuddyCredits.format(threeDays) + " · 5 日内 " + WorkBuddyCredits.format(fiveDays)
        credit?.error?.isNotEmpty() == true -> "3 日内 — · 5 日内 —"
        account.uid.isEmpty() -> "该账号缺少 uid，无法查询积分"
        else -> "正在查询积分…"
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AccountFace(
            url = account.avatarUrl,
            label = account.label,
            hydrating = false,
        )
        Spacer(Modifier.size(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = account.label,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    text = remainingText,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (balance != null) colorScheme.primary else colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = if (account.uid.isNotEmpty()) "uid=${account.uid}" else "token=${account.token.take(6)}****",
                fontSize = 11.sp,
                color = colorScheme.onSurfaceVariantSummary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(1.dp))
            Text(
                text = expiryText,
                fontSize = 11.sp,
                color = colorScheme.onSurfaceVariantSummary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // 查看积分：点开逐笔明细（剩余与 3/5 日额度已经显示在行内）
        IconButton(onClick = onViewCredits) {
            Icon(
                Icons.Rounded.Star,
                contentDescription = "查看积分",
                tint = colorScheme.primary,
            )
        }
        IconButton(onClick = onRemove) {
            Icon(
                Icons.Rounded.Delete,
                contentDescription = stringResource(R.string.workbuddy_logout),
                tint = colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

/** 灰色小标题 */
@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        color = colorScheme.onSurfaceVariantSummary,
        modifier = Modifier.padding(start = 6.dp, top = 10.dp, bottom = 6.dp),
    )
}

@Composable
private fun EmptyHint() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 72.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(R.string.account_empty),
            fontSize = 14.sp,
            color = colorScheme.onSurfaceVariantSummary,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun AccountFace(
    url: String,
    label: String,
    hydrating: Boolean,
    size: Dp = 36.dp,
) {
    val modifier = Modifier.padding(end = 6.dp)
    when {
        hydrating && url.isEmpty() -> AvatarLoading(size, modifier)
        url.isNotEmpty() -> UrlImage(url = url, size = size, modifier = modifier, contentDescription = label)
        else -> LetterAvatar(label = label, size = size, modifier = modifier)
    }
}

@Composable
private fun AvatarLoading(size: Dp, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center,
    ) {
        InfiniteProgressIndicator(color = colorScheme.primary)
    }
}

@Composable
private fun LetterAvatar(label: String, size: Dp, modifier: Modifier = Modifier) {
    val ch = label.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
    val hue = ((label.hashCode().toLong() and 0x7fffffffL) % 360L).toFloat()
    Box(
        modifier = modifier
            .size(size)
            .squircleClip(cornerRadius = size * IconSquircleCornerFraction)
            .background(Color.hsl(hue, 0.42f, 0.46f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = ch,
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
        )
    }
}

/** 网络头像：OkHttp 拉取 + miuix squircleClip；拉取中显示加载占位。 */
@Composable
private fun UrlImage(
    url: String,
    size: Dp,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    var bitmap by remember(url) { mutableStateOf<android.graphics.Bitmap?>(null) }
    var loaded by remember(url) { mutableStateOf(false) }
    LaunchedEffect(url) {
        loaded = false
        bitmap = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                com.functy.fewards.fewardsApp.okhttpClient.newCall(
                    okhttp3.Request.Builder().url(url).build()
                ).execute().use { resp ->
                    val bytes = resp.body.bytes()
                    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                }
            }.getOrNull()
        }
        loaded = true
    }
    when {
        bitmap != null -> Image(
            bitmap = bitmap!!.asImageBitmap(),
            contentDescription = contentDescription,
            modifier = modifier.size(size).squircleClip(cornerRadius = size * IconSquircleCornerFraction),
            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
        )
        !loaded -> AvatarLoading(size, modifier)
        else -> LetterAvatar(label = contentDescription.orEmpty(), size = size, modifier = modifier)
    }
}
