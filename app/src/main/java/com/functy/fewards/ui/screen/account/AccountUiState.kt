package com.functy.fewards.ui.screen.account

import androidx.compose.runtime.Immutable
import com.functy.fewards.core.workbuddy.WorkBuddyCredits
import com.functy.fewards.data.repository.AccountRepository

/**
 * 登录流程的阶段。扫码登录共用同一套状态机：
 * Waiting = 已展示二维码、等待扫码；Loading = 正在换 token。
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
    val balance: WorkBuddyCredits.Balance? = null,
    val error: String = "",
)

/**
 * 积分明细弹窗的状态（逐笔发放记录）。
 *
 * [success] 只用于区分"还没查过"和"查过但没有记录"，避免弹窗刚打开就显示"暂无明细"。
 */
@Immutable
data class WbCreditDetailState(
    val loading: Boolean = false,
    val success: Boolean = false,
    val records: List<WorkBuddyCredits.CreditRecord> = emptyList(),
    val error: String = "",
)

@Immutable
data class AccountUiState(
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
)

@Immutable
data class AccountActions(
    val onRemoveWb: (String) -> Unit,
    /** 返回 true 表示 token 被接受（弹窗据此关闭）；false 保留输入让用户改。 */
    val onImportWbToken: (String) -> Boolean,
    val onSetWbLoginMode: (Int) -> Unit,
    val onStartWbQr: () -> Unit,
    val onCancelWbQr: () -> Unit,
    /** 打开某账号的积分明细弹窗（同时拉取逐笔记录） */
    val onViewWbCredits: (String) -> Unit,
    val onCloseWbCredits: () -> Unit,
    /** 重新查询某账号的积分（绕过 2 分钟缓存） */
    val onRefreshWbCredits: (String) -> Unit,
)
