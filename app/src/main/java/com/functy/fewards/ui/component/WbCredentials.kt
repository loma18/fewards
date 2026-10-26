package com.functy.fewards.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.functy.fewards.core.lansync.CredentialHealth
import com.functy.fewards.data.repository.AccountRepository
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 账号凭据详情：access_token / refresh_token，以及**各自**的过期时间。
 *
 * ## 为什么要看这个
 *
 * refresh_token 是**轮换式**的，手机和电脑各持一份时，一次刷新就会让另一份作废。
 * 排查"这台还能用、那台掉登录"时，唯一可靠的办法是把两边的 token 与过期时间摊开对照，
 * 所以两端的账号行都要能直接看到这几个值。
 *
 * ## 两个过期时间是两回事
 *
 * `expiresAt` 是 access_token 的到期时间（通常几小时），`refreshExpiresAt` 是
 * refresh_token 的到期时间（通常几十天）。**不能混用**：
 * - access 过期只代表要拿 refresh 换一份新的，账号仍然活着；
 * - refresh 过期才是真的没救，只能重新扫码。
 *
 * 所以这里两条时间线分开写，谁也不拿谁顶替。
 *
 * ## 默认掩码
 *
 * 完整 token 有近千字符，直接铺开会把列表撑爆，也容易被人扫屏。默认只显示
 * `头 6 位…尾 6 位`，点一下整块切到完整值（自动换行）。掩码里保留首尾正是
 * 为了肉眼比对两份凭据是不是同一条链。
 *
 * ## 什么时候才隐藏 token
 *
 * 只有**两个都不行**（access 过期且 refresh 也没有/已过期）时才折叠成一句"已失效"——
 * 那种情况下 token 已经没有任何用处。只有 access 过期时照常显示，因为 refresh 还能换一份，
 * 而用户恰恰需要看一眼这份 refresh 才能判断要不要重新扫码。
 */
@Composable
fun WbCredentialBlock(
    account: AccountRepository.WorkBuddyAccount,
    modifier: Modifier = Modifier,
) {
    val now = remember(account) { System.currentTimeMillis() }
    val accessDead = !CredentialHealth.isUsable(account.token, account.expiresAt, now)
    // refresh 过期时间只有电脑端回传才有；0（未知）时按"还用得上"处理，与 CredentialHealth 一致
    val refreshUsable = account.refreshToken.isNotEmpty() &&
        !CredentialHealth.isRefreshExpired(account.refreshToken, account.refreshExpiresAt, now)
    // access 过期但 refresh 还活着 ＝ 账号没死，只是要换一份；两个都不行才真的只能重新扫码
    val nothingUsable = accessDead && !refreshUsable
    var revealed by remember(account.id) { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clickable(enabled = !nothingUsable) { revealed = !revealed }
            .padding(start = 46.dp, end = 6.dp, top = 2.dp, bottom = 8.dp),
    ) {
        if (nothingUsable) {
            Text(
                text = "凭据已失效，不显示 token",
                fontSize = 11.sp,
                color = colorScheme.onSurfaceVariantSummary,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "access 到期 " + formatCredentialExpiry(account.expiresAt, now),
                fontSize = 10.sp,
                color = colorScheme.onSurfaceVariantSummary,
            )
            Text(
                text = "refresh 到期 " + formatCredentialExpiry(account.refreshExpiresAt, now),
                fontSize = 10.sp,
                color = colorScheme.onSurfaceVariantSummary,
            )
            return@Column
        }

        CredentialLine(
            label = "access",
            value = account.token,
            expiresAtSec = account.expiresAt,
            nowMs = now,
            revealed = revealed,
        )
        Spacer(Modifier.height(4.dp))
        CredentialLine(
            label = "refresh",
            value = account.refreshToken.ifEmpty { "（无，access 过期后只能重新扫码）" },
            expiresAtSec = account.refreshExpiresAt,
            nowMs = now,
            revealed = revealed,
            muted = account.refreshToken.isEmpty(),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = if (revealed) "点击收起完整 token" else "点击显示完整 token",
            fontSize = 10.sp,
            color = colorScheme.onSurfaceVariantSummary,
        )
        if (accessDead) {
            Text(
                text = "access 已过期：可用 refresh_token 换一份新的（账号本身没死）",
                fontSize = 10.sp,
                color = colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

/**
 * 一行凭据：label + token（掩码/完整）+ 该 token 自己的到期时间。
 *
 * 到期时间单独占一行并缩进对齐，避免两行 token 太长时挤在一起看不出谁对应谁。
 */
@Composable
private fun CredentialLine(
    label: String,
    value: String,
    expiresAtSec: Long,
    nowMs: Long,
    revealed: Boolean,
    muted: Boolean = false,
) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            fontSize = 10.sp,
            color = colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.width(50.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (revealed) value else maskCredential(value),
                fontSize = 10.sp,
                color = if (muted) colorScheme.onSurfaceVariantSummary else colorScheme.onSurface,
                maxLines = if (revealed) Int.MAX_VALUE else 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "到期 " + formatCredentialExpiry(expiresAtSec, nowMs),
                fontSize = 10.sp,
                color = colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

/**
 * token 掩码：首 6 + 尾 6。
 *
 * 太短的串（例如占位文案、异常数据）不做首尾拼接，否则会露出全部内容。
 */
internal fun maskCredential(value: String): String {
    if (value.length <= 14) return "••••••"
    return value.take(6) + "…" + value.takeLast(6)
}

/**
 * 过期时间的绝对时间 + 剩余量。
 *
 * 入参一律是**秒**；0 表示这条记录里没有这个字段（判断不了，不是已过期），
 * 这种情况要明说"未知"，不能渲染成 1970 年。
 */
internal fun formatCredentialExpiry(expiresAtSec: Long, nowMs: Long): String {
    if (expiresAtSec <= 0L) return "未知（这条记录没带这个时间）"
    val at = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(expiresAtSec * 1000))
    val hours = (expiresAtSec * 1000 - nowMs) / 3_600_000.0
    val remain = when {
        hours <= 0 -> "已过期"
        hours < 48 -> "剩余 " + String.format(Locale.US, "%.1f", hours) + " 小时"
        else -> "剩余 " + String.format(Locale.US, "%.1f", hours / 24) + " 天"
    }
    return "$at（$remain）"
}
