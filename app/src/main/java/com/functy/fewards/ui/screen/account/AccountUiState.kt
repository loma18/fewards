package com.functy.fewards.ui.screen.account

import androidx.compose.runtime.Immutable
import com.functy.fewards.data.repository.AccountRepository

/**
 * 登录流程的通用阶段：米游社扫码、WorkBuddy 扫码、Bing 的 WebView 授权共用。
 *
 * Bing 没有二维码，但阶段语义一一对应：
 * Waiting = 授权页已展示、等待用户登录；Loading = 正在换 token。
 */
enum class QrState { Idle, Loading, Waiting, Scanned, Confirmed, Expired, Error }

/**
 * 单个 WorkBuddy 账号的积分状态。
 *
 * [balance] 与 [error] 互斥：查询失败时保留 [error] 文案，卡片显示"积分查询失败"，
 * 而不是伪装成 0 —— 0 会被用户当成"真的没积分了"。
 */
@Immutable
data class WbCreditState(
    val loading: Boolean = false,
    val balance: com.functy.fewards.core.workbuddy.WorkBuddyCredits.Balance? = null,
    val error: String = "",
)

/**
 * 积分明细弹窗的状态（逐笔发放记录）。
 *
 * `success` 只用于区分"还没查过"和"查过但没有记录"，避免弹窗刚打开就显示"暂无明细"。
 */
@Immutable
data class WbCreditDetailState(
    val loading: Boolean = false,
    val success: Boolean = false,
    val records: List<com.functy.fewards.core.workbuddy.WorkBuddyCredits.CreditRecord> = emptyList(),
    val error: String = "",
)

@Immutable
data class AccountUiState(
    // 米游社
    val mihoyoAccounts: List<AccountRepository.MihoyoAccount> = emptyList(),
    val mhyHydratingIds: Set<String> = emptySet(),
    val loginMode: Int = 0, // 0 扫码；1 Cookie
    val qrState: QrState = QrState.Idle,
    val qrContent: String = "",
    // WorkBuddy
    val wbAccounts: List<AccountRepository.WorkBuddyAccount> = emptyList(),
    val wbLoginMode: Int = 0, // 0 扫码；1 Token
    val wbQrState: QrState = QrState.Idle,
    val wbQrContent: String = "",
    /** 失败时的原始错误信息（网络异常等），显示在二维码下方，避免只给一句「获取二维码失败」 */
    val wbQrError: String = "",
    /** 各 WorkBuddy 账号的积分（key = 账号 id）。进页面时自动查，2 分钟内复用缓存 */
    val wbCredits: Map<String, WbCreditState> = emptyMap(),
    /** 正在查看积分明细的账号 id；空串 = 弹窗关闭 */
    val wbCreditDetailId: String = "",
    val wbCreditDetail: WbCreditDetailState = WbCreditDetailState(),
    // Bing
    val bingAccounts: List<AccountRepository.BingAccount> = emptyList(),
    val bingLoginMode: Int = 0, // 0 微软登录（WebView）；1 refresh_token
    /** 授权页地址（含一次性 state）。空串表示还没生成。 */
    val bingAuthUrl: String = "",
    /** 本次授权的一次性 state，回跳时校验，防串号。 */
    val bingAuthCsrf: String = "",
    val bingAuthPhase: QrState = QrState.Idle,
)

@Immutable
data class AccountActions(
    val onSetLoginMode: (Int) -> Unit,
    val onStartQr: () -> Unit,
    val onCancelQr: () -> Unit,
    /** 返回 true 表示凭据被接受（弹窗据此关闭）；false 保留输入让用户改。 */
    val onImportCookie: (String) -> Boolean,
    val onRemoveMihoyo: (String) -> Unit,
    /** 同上：返回 true 表示 token 被接受。 */
    val onImportWbToken: (String) -> Boolean,
    val onRemoveWb: (String) -> Unit,
    val onSetWbLoginMode: (Int) -> Unit,
    val onStartWbQr: () -> Unit,
    val onCancelWbQr: () -> Unit,
    /** 打开某账号的积分明细弹窗（同时拉取逐笔记录） */
    val onViewWbCredits: (String) -> Unit,
    val onCloseWbCredits: () -> Unit,
    /** 重新查询某账号的积分（绕过 2 分钟缓存） */
    val onRefreshWbCredits: (String) -> Unit,
    // Bing
    val onSetBingLoginMode: (Int) -> Unit,
    /** 生成授权页并进入等待登录。 */
    val onStartBingAuth: () -> Unit,
    val onCancelBingAuth: () -> Unit,
    /** WebView 拦到回跳地址时调用；返回 true 表示已消费该回跳。 */
    val onBingRedirect: (String) -> Boolean,
    /** 返回 true 表示凭据被接受（弹窗据此关闭）。 */
    val onImportBingToken: (String) -> Boolean,
    val onRemoveBing: (String) -> Unit,
)
