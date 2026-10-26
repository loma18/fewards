package com.functy.fewards.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.functy.fewards.core.AppLog
import com.functy.fewards.core.workbuddy.WorkBuddyCredits
import com.functy.fewards.core.workbuddy.WorkBuddyLabel
import com.functy.fewards.core.workbuddy.WorkBuddyLogin
import com.functy.fewards.data.repository.AccountRepository
import com.functy.fewards.fewardsApp
import com.functy.fewards.ui.screen.account.AccountActions
import com.functy.fewards.ui.screen.account.AccountUiState
import com.functy.fewards.ui.screen.account.QrState
import com.functy.fewards.ui.screen.account.WbCreditDetailState
import com.functy.fewards.ui.screen.account.WbCreditState
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 账号页 ViewModel：WorkBuddy 扫码 / Token 登录，以及各账号的积分查询。
 *
 * 米游社与 Bing 已于本次改造中移除，只剩 WorkBuddy 一套登录流程
 * （QrState 状态机保留，扫码阶段语义不变）。
 */
class AccountViewModel : ViewModel() {

    private val accounts = AccountRepository()
    private val wbLogin = WorkBuddyLogin(fewardsApp.okhttpClient)
    private val credits = WorkBuddyCredits(fewardsApp.okhttpClient)

    private val _uiState = MutableStateFlow(AccountUiState())
    val uiState: StateFlow<AccountUiState> = _uiState.asStateFlow()

    private var wbQrJob: Job? = null
    private var creditJob: Job? = null
    private var creditDetailJob: Job? = null

    /** 余额缓存：官方接口较慢，2 分钟内重复进页面直接用上一次结果（与 b 端同口径） */
    private val creditCache = HashMap<String, Pair<Long, WorkBuddyCredits.Balance>>()

    /** 待查积分的账号 id（串行消费，避免并发打官方接口） */
    private val creditQueue = LinkedHashSet<String>()

    init {
        refresh()
        /**
         * 账号库一变就重读。
         *
         * 设置页的「从电脑同步凭据 / 同步选中账号」、WorkBuddy 巡检刷新都会改库，
         * 而这些动作都不在本页触发；不订阅就只能等用户切页（见 AccountPager 的 refresh）。
         * StateFlow 订阅会立刻收到当前值，`drop(1)` 跳过它，避免和上面的 refresh() 重复。
         */
        viewModelScope.launch {
            AccountRepository.revision.drop(1).collect { refresh() }
        }
    }

    fun refresh() {
        publishAccounts()
        syncWbCredits()
    }

    private fun publishAccounts() {
        val wb = accounts.workBuddyAccounts()
        _uiState.update { it.copy(wbAccounts = wb) }
    }

    // ==================== WorkBuddy ====================

    fun setWbLoginMode(mode: Int) {
        _uiState.update { it.copy(wbLoginMode = mode) }
    }

    /**
     * WorkBuddy 扫码授权：申请授权链接 -> 轮询 -> 落库（按 uid 去重）。
     * 扫码成功后只纳管，不自动签到；签到仍由首页「开始执行」统一触发。
     */
    fun startWbQrLogin() {
        wbQrJob?.cancel()
        _uiState.update { it.copy(wbQrState = QrState.Loading, wbQrContent = "", wbQrError = "") }
        wbQrJob = viewModelScope.launch {
            try {
                val session = wbLogin.startLogin()
                _uiState.update { it.copy(wbQrState = QrState.Waiting, wbQrContent = session.authUrl) }
                val result = wbLogin.awaitLogin(session)
                val existed = accounts.workBuddyAccounts().any {
                    it.uid == result.uid || (it.uid.isEmpty() && it.label == result.nickname)
                }
                val profile = WorkBuddyLabel.decodeProfile(result.accessToken)
                accounts.addWorkBuddyAccount(
                    AccountRepository.WorkBuddyAccount(
                        id = "wb_${result.uid}",
                        label = result.nickname.ifEmpty { profile.label ?: "Work Buddy 账号" },
                        token = result.accessToken,
                        avatarUrl = profile.avatarUrl.orEmpty(),
                        uid = result.uid,
                        enterpriseId = result.enterpriseId,
                        refreshToken = result.refreshToken,
                        expiresAt = result.expiresAt,
                        // 记录"这份凭据是什么时候拿到的"，同步时电脑端要靠它判断新旧
                        obtainedAt = System.currentTimeMillis(),
                    )
                )
                AppLog.i("WB", "账号 ${result.nickname} 扫码授权成功${if (existed) "（已更新）" else ""}")
                // 先刷新列表再标记成功：Confirmed 会触发弹窗关闭并 cancel 本协程，
                // 顺序反了就可能"账号已落库、列表却没刷新"
                publishAccounts()
                _uiState.update { it.copy(wbQrState = QrState.Confirmed) }
            } catch (c: kotlinx.coroutines.CancellationException) {
                /**
                 * 主动取消**不是失败**。
                 *
                 * 关弹窗（`onCancelWbQr`）、切 Tab、点「重新获取」都会 cancel 这个 job。
                 * 而取消是在协程的下一个挂起点（`delay` / 网络请求）抛出的——如果被下面
                 * 的 catch(Throwable) 接住，就会把刚写入的 Confirmed 覆盖成 Error，
                 * 弹窗消失动画期间显示成「获取二维码失败」，账号其实已经存进去了。
                 */
                throw c
            } catch (t: Throwable) {
                AppLog.e("WB", "扫码授权失败: ${t.message}")
                val detail = (t.message ?: t.javaClass.simpleName).take(120)
                _uiState.update {
                    it.copy(
                        wbQrState = if (t.message?.contains("过期") == true) QrState.Expired else QrState.Error,
                        wbQrError = detail,
                    )
                }
            }
        }
    }

    fun cancelWbQr() {
        wbQrJob?.cancel()
        wbQrJob = null
        _uiState.update { it.copy(wbQrState = QrState.Idle) }
    }

    /** 返回 false 表示 token 为空，弹窗保留输入让用户改。 */
    fun importWbToken(token: String): Boolean {
        val trimmed = token.trim()
        if (trimmed.isEmpty()) {
            AppLog.e("WB", "token 为空")
            return false
        }
        val profile = WorkBuddyLabel.decodeProfile(trimmed)
        val uid = profile.uid
        // 老账号（扫码前导入的）没有 uid，退回按昵称判定，避免同一账号出现两条
        val existed = accounts.workBuddyAccounts().any {
            (uid.isNotEmpty() && it.uid == uid) ||
                (it.uid.isEmpty() && profile.label != null && it.label == profile.label)
        }
        accounts.addWorkBuddyAccount(
            AccountRepository.WorkBuddyAccount(
                // 同账号重复导入 = 更新，不再新增一条
                id = if (uid.isNotEmpty()) "wb_$uid" else "wb_" + System.currentTimeMillis(),
                label = profile.label ?: "Work Buddy 账号",
                token = trimmed,
                avatarUrl = profile.avatarUrl.orEmpty(),
                uid = uid,
                expiresAt = profile.expiresAt,
                obtainedAt = System.currentTimeMillis(),
            )
        )
        AppLog.i("WB", "WorkBuddy token 导入成功")
        refresh()
        return true
    }

    fun removeWb(id: String) {
        accounts.removeWorkBuddyAccount(id)
        creditCache.remove(id)
        // 账号都删了，弹窗不能继续挂着
        if (_uiState.value.wbCreditDetailId == id) closeWbCredits()
        refresh()
    }

    // ==================== WorkBuddy 积分 ====================

    /**
     * 查一遍所有 WorkBuddy 账号的积分余额。
     *
     * 进账号页自动触发；串行 + 小间隔，避免同时打多个官方接口。
     * 2 分钟内已查过的账号直接用缓存（除非 [force]）——官方汇总接口本身较慢，
     * 每次进页面都重查会让列表一直停在"查询中"。
     *
     * 用一个待查队列而不是"正在跑就直接返回"：`refresh()` 会因扫码加号、删除账号被
     * 频繁调用，直接返回会让刚加的账号永远停在"积分待查询"。
     */
    private fun syncWbCredits(force: Boolean = false) {
        val list = accounts.workBuddyAccounts().filter { it.uid.isNotEmpty() }
        if (list.isEmpty()) return

        val now = System.currentTimeMillis()
        // 先把手里的缓存铺进状态，避免每次进页面都从空白闪一下
        val seeded = list.mapNotNull { acc ->
            creditCache[acc.id]?.let { acc.id to WbCreditState(balance = it.second) }
        }.toMap()
        if (seeded.isNotEmpty()) {
            _uiState.update { it.copy(wbCredits = it.wbCredits + seeded) }
        }

        val targets = list.filter { acc ->
            val cached = creditCache[acc.id]
            force || cached == null || now - cached.first > CREDIT_CACHE_TTL_MS
        }
        if (targets.isEmpty()) return

        if (force) {
            creditQueue.clear()
            creditJob?.cancel()
        }
        creditQueue += targets.map { it.id }
        if (creditJob?.isActive == true) return

        creditJob = viewModelScope.launch {
            while (creditQueue.isNotEmpty()) {
                val id = creditQueue.first()
                creditQueue.remove(id)
                // 每轮重新读盘：上一个账号的刷新可能顺带改了列表
                val acc = accounts.workBuddyAccounts().firstOrNull { it.id == id } ?: continue
                _uiState.update { s ->
                    val prev = s.wbCredits[id] ?: WbCreditState()
                    s.copy(wbCredits = s.wbCredits + (id to prev.copy(loading = true, error = "")))
                }
                try {
                    val balance = credits.balance(acc)
                    creditCache[id] = System.currentTimeMillis() to balance
                    _uiState.update { s ->
                        s.copy(wbCredits = s.wbCredits + (id to WbCreditState(balance = balance)))
                    }
                } catch (c: kotlinx.coroutines.CancellationException) {
                    throw c
                } catch (e: Exception) {
                    // 失败不写缓存：下次进页面还要重试
                    _uiState.update { s ->
                        s.copy(wbCredits = s.wbCredits + (id to WbCreditState(error = e.message ?: "积分查询失败")))
                    }
                }
                kotlinx.coroutines.delay(120)
            }
        }
    }

    /** 打开某账号的积分明细弹窗，并拉取逐笔记录。 */
    fun viewWbCredits(id: String) {
        val account = accounts.workBuddyAccounts().firstOrNull { it.id == id } ?: return
        _uiState.update {
            it.copy(wbCreditDetailId = id, wbCreditDetail = WbCreditDetailState(loading = true))
        }
        creditDetailJob?.cancel()
        creditDetailJob = viewModelScope.launch {
            try {
                val records = credits.records(account)
                _uiState.update {
                    it.copy(wbCreditDetail = WbCreditDetailState(success = true, records = records))
                }
            } catch (c: kotlinx.coroutines.CancellationException) {
                throw c
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        wbCreditDetail = WbCreditDetailState(
                            success = true,
                            error = e.message ?: "明细查询失败",
                        )
                    )
                }
            }
        }
        // 余额还没拿到（或已过期）时顺手补一次，弹窗标题要显示汇总
        if (_uiState.value.wbCredits[id]?.balance == null) refreshWbCredits(id, withDetail = false)
    }

    /** 关闭积分明细弹窗。刻意不清空 [AccountUiState.wbCreditDetail]：关闭动画期间还要渲染内容。 */
    fun closeWbCredits() {
        creditDetailJob?.cancel()
        creditDetailJob = null
        _uiState.update { it.copy(wbCreditDetailId = "") }
    }

    /**
     * 重新查询某账号的积分（绕过缓存）。
     *
     * 走 [syncWbCredits] 的队列而不是单开一个协程：`creditJob` 只能有一个引用，
     * 覆盖它会让队列里的其他账号永远停在"查询中"。
     */
    fun refreshWbCredits(id: String, withDetail: Boolean = true) {
        if (accounts.workBuddyAccounts().none { it.id == id }) return
        creditCache.remove(id)
        syncWbCredits()
        if (withDetail && _uiState.value.wbCreditDetailId == id) viewWbCredits(id)
    }

    private companion object {
        /** 积分余额缓存时长，与 b 端 `BALANCE_CACHE_TTL_MS` 一致 */
        const val CREDIT_CACHE_TTL_MS = 2 * 60 * 1000L
    }

    val accountActions = AccountActions(
        onRemoveWb = ::removeWb,
        onSetWbLoginMode = ::setWbLoginMode,
        onStartWbQr = ::startWbQrLogin,
        onCancelWbQr = ::cancelWbQr,
        onImportWbToken = ::importWbToken,
        onViewWbCredits = ::viewWbCredits,
        onCloseWbCredits = ::closeWbCredits,
        onRefreshWbCredits = { refreshWbCredits(it) },
    )
}
