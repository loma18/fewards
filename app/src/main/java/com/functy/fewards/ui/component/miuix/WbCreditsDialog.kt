package com.functy.fewards.ui.component.miuix

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.functy.fewards.core.workbuddy.WorkBuddyCredits
import com.functy.fewards.ui.screen.account.WbCreditDetailState
import com.functy.fewards.ui.screen.account.WbCreditState
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.TabRowWithContour
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.window.WindowDialog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 「积分明细」弹窗（对齐官方客户端的同名弹窗，也是 b 项目 PackagesDialog 的手机版）。
 *
 * 数据来自 [WorkBuddyCredits.records] 的逐笔发放记录：每条一个积分包，
 * 带到期时刻与「已使用 x/y」。按「有效期内 / 已过期」分组——已过期的记录仍然列出，
 * 那是客户端的第二个 Tab，不是脏数据。
 *
 * 明细接口失败时回落到汇总里的包视图（[WbCreditState.balance]），
 * 并明确写出失败原因，不假装"没有积分"。
 */
@Composable
fun WbCreditsDialog(
    show: Boolean,
    accountLabel: String,
    credit: WbCreditState,
    detail: WbCreditDetailState,
    onDismissRequest: () -> Unit,
    onRefresh: () -> Unit,
) {
    // tab：0 = 有效期内（含长期有效），1 = 已过期
    var tab by rememberSaveable { mutableStateOf(0) }
    val active = detail.records.filter { !it.expired }
    val expired = detail.records.filter { it.expired }
    val shown = if (tab == 0) active else expired
    val balance = credit.balance

    WindowDialog(
        show = show,
        title = "积分明细",
        onDismissRequest = onDismissRequest,
        content = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = accountLabel,
                    fontSize = 13.sp,
                    color = colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                )
                Spacer(Modifier.height(6.dp))

                // ===== 汇总 =====
                Text(
                    text = when {
                        balance != null -> "剩余 ${WorkBuddyCredits.format(balance.remaining)}"
                        credit.loading -> "正在查询…"
                        credit.error.isNotEmpty() -> "积分查询失败"
                        else -> "—"
                    },
                    fontSize = 24.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (credit.error.isNotEmpty() && balance == null) {
                        colorScheme.primary
                    } else {
                        colorScheme.onSurface
                    },
                )
                if (balance != null) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "总额 ${WorkBuddyCredits.format(balance.total)} · " +
                            "已用 ${WorkBuddyCredits.format(balance.used)} · " +
                            "${balance.packageCount} 个积分包",
                        fontSize = 12.sp,
                        color = colorScheme.onSurfaceVariantSummary,
                    )
                } else if (credit.error.isNotEmpty()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = credit.error,
                        fontSize = 12.sp,
                        color = colorScheme.onSurfaceVariantSummary,
                    )
                }
                Spacer(Modifier.height(14.dp))

                when {
                    detail.error.isNotEmpty() -> {
                        Text(
                            text = "逐笔明细获取失败：${detail.error}",
                            fontSize = 12.sp,
                            color = colorScheme.onSurfaceVariantSummary,
                        )
                        Spacer(Modifier.height(10.dp))
                        // 明细失败时用汇总包兜底，至少能看到每个包的到期时间
                        if (balance != null && balance.packages.isNotEmpty()) {
                            Box(modifier = Modifier.heightIn(max = 300.dp)) {
                                LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    itemsIndexed(balance.packages) { _, pkg ->
                                        PackageRow(
                                            name = pkg.name,
                                            expireAt = pkg.expireAt,
                                            used = pkg.used,
                                            total = pkg.total,
                                            dimmed = pkg.expired || pkg.remaining <= 0,
                                            tag = when {
                                                pkg.expired -> "已过期"
                                                pkg.remaining <= 0 -> "已用完"
                                                pkg.expiringSoon -> "即将到期"
                                                else -> ""
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }

                    detail.loading && detail.records.isEmpty() -> {
                        Box(
                            modifier = Modifier.fillMaxWidth().height(120.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                InfiniteProgressIndicator(color = colorScheme.primary)
                                Spacer(Modifier.height(10.dp))
                                Text(
                                    text = "正在获取逐笔明细…",
                                    fontSize = 13.sp,
                                    color = colorScheme.onSurfaceVariantSummary,
                                )
                            }
                        }
                    }

                    detail.records.isEmpty() -> {
                        Box(
                            modifier = Modifier.fillMaxWidth().height(96.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = if (detail.success) "当前没有积分明细记录" else "正在获取逐笔明细…",
                                fontSize = 13.sp,
                                color = colorScheme.onSurfaceVariantSummary,
                            )
                        }
                    }

                    else -> {
                        TabRowWithContour(
                            tabs = listOf("有效期内 ${active.size}", "已过期 ${expired.size}"),
                            selectedTabIndex = tab,
                            onTabSelected = { tab = it },
                        )
                        Spacer(Modifier.height(12.dp))
                        Box(modifier = Modifier.heightIn(max = 320.dp)) {
                            if (shown.isEmpty()) {
                                Box(
                                    modifier = Modifier.fillMaxWidth().height(72.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = if (tab == 0) "没有有效期内的积分记录" else "没有已过期的记录",
                                        fontSize = 13.sp,
                                        color = colorScheme.onSurfaceVariantSummary,
                                    )
                                }
                            } else {
                                LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    itemsIndexed(shown) { _, record ->
                                        PackageRow(
                                            name = record.name,
                                            expireAt = record.expireAt,
                                            used = record.used,
                                            total = record.total,
                                            dimmed = record.expired || record.remaining <= 0,
                                            tag = when {
                                                record.expired -> "已过期"
                                                record.remaining <= 0 -> "已用完"
                                                else -> ""
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    TextButton(
                        text = "刷新",
                        onClick = onRefresh,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        text = "关闭",
                        onClick = onDismissRequest,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                    )
                }
            }
        },
    )
}

/**
 * 一条积分记录：名称（+ 状态标记）、到期时刻、「已使用 x/y」、细进度条。
 *
 * 进度条按 **已用比例** 填充——官方客户端就是这个口径（用满即整条填满）。
 */
@Composable
private fun PackageRow(
    name: String,
    expireAt: Long?,
    used: Double,
    total: Double,
    dimmed: Boolean,
    tag: String,
) {
    val ratio = if (total > 0) (used / total).coerceIn(0.0, 1.0).toFloat() else 0f
    val subtle = colorScheme.onSurfaceVariantSummary

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = name,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = if (dimmed) subtle else colorScheme.onSurface,
                maxLines = 1,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (tag.isNotEmpty()) {
                Spacer(Modifier.height(0.dp))
                Box(
                    modifier = Modifier
                        .padding(start = 6.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(colorScheme.surfaceContainer)
                        .padding(horizontal = 5.dp, vertical = 1.dp)
                ) {
                    Text(text = tag, fontSize = 10.sp, color = subtle)
                }
            }
        }
        Spacer(Modifier.height(3.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (expireAt != null) "到期 ${formatExpire(expireAt)}" else "长期有效",
                fontSize = 11.sp,
                color = subtle,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "已使用 ${WorkBuddyCredits.format(used)}/${WorkBuddyCredits.format(total)}",
                fontSize = 11.sp,
                color = if (dimmed) subtle else colorScheme.onSurface,
            )
        }
        Spacer(Modifier.height(5.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(colorScheme.surfaceContainer)
        ) {
            if (ratio > 0f) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(ratio)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(2.dp))
                        .background(if (dimmed) subtle else colorScheme.primary)
                )
            }
        }
    }
}

/** 到期时刻：与官方客户端「积分明细」一致，精确到秒 */
private fun formatExpire(ms: Long): String =
    SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.US).format(Date(ms))
