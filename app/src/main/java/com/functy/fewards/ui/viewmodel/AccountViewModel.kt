package com.functy.fewards.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.functy.fewards.core.AppLog
import com.functy.fewards.core.bing.BingConstants
import com.functy.fewards.core.bing.BingEngine
import com.functy.fewards.core.bing.BingIdentity
import com.functy.fewards.core.bing.BingOAuth
import com.functy.fewards.core.mihoyo.MihoyoApi
import com.functy.fewards.core.mihoyo.MihoyoProfileHydrator
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 账号页 ViewModel：米游社扫码 / Cookie 登录，WorkBuddy 扫码 / Token 登录，
 * Bing 内嵌 WebView 授权 / refresh_token 登录。
 * 三套流程状态机一致（QrState），只是协议客户端不同。
 */
class AccountViewModel : ViewModel() {

    private val accounts = AccountRepository()
    private val api = MihoyoApi(MihoyoApi.defaultClient())
    private val wbLogin = WorkBuddyLogin(fewardsApp.okhttpClient)
    private val bingOAuth = BingOAuth(fewardsApp.okhttpClient)
    private val credits = WorkBuddyCredits(fewardsApp.okhttpClient)

    private val _uiState = MutableStateFlow(AccountUiState())
    val uiState: StateFlow<AccountUiState> = _uiState.asStateFlow()

    private var qrJob: Job? = null
    private var wbQrJob: Job? = null
    private var bingJob: Job? = null
    private var creditJob: Job? = null
    private var creditDetailJob: Job? = null

    /** 余额缓存：官方接口较慢，2 分钟内重复进页面直接用上一次结果（与 b 端同口径） */
    private val creditCache = HashMap<String, Pair<Long, WorkBuddyCredits.Balance>>()

    /** 待查积分的账号 id（串行消费，避免并发打官方接口） */
    private val creditQueue = LinkedHashSet<String>()

    init {
        refresh()
    }

    fun refresh() {
        repairBingLabels()
        publishAccounts()
        hydrateMissingProfiles()
        syncWbCredits()
    }

    /**
     * 修正老的 Bing 占位标签。
     * 标签在纳管时写死，改名之后已有账号不会自己更新，所以在读取侧就地改一次。
     */
    private fun repairBingLabels() {
        accounts.bingAccounts().forEach { account ->
            val fixed = BingIdentity.normalizeLabel(account.label)
            if (fixed != account.label) accounts.addBingAccount(account.copy(label = fixed))
        }
    }

    private fun publishAccounts() {
        val mhy = accounts.mihoyoAccounts()
        val wb = accounts.workBuddyAccounts()
        val bing = accounts.bingAccounts()
        _uiState.update { it.copy(mihoyoAccounts = mhy, wbAccounts = wb, bingAccounts = bing) }
    }

    private fun hydrateMissingProfiles() {
        accounts.mihoyoAccounts()
            .filter { MihoyoProfileHydrator.needsHydration(it) }
            .forEach { acc ->
                if (!MihoyoProfileHydrator.markAttempted(acc.id)) return@forEach
                _uiState.update { it.copy(mhyHydratingIds = it.mhyHydratingIds + acc.id) }
                viewModelScope.launch {
                    try {
                        val next = MihoyoProfileHydrator.hydrate(api, acc)
                        if (next != acc) accounts.addMihoyoAccount(next)
                    } finally {
                        _uiState.update { it.copy(mhyHydratingIds = it.mhyHydratingIds - acc.id) }
                        publishAccounts()
                    }
                }
            }
    }

    fun setLoginMode(mode: Int) {
        _uiState.update { it.copy(loginMode = mode) }
    }

    // ==================== 米游社扫码 ====================

    /** 生成二维码并轮询扫码状态。 */
    fun startQrLogin() {
        qrJob?.cancel()
        _uiState.update { it.copy(qrState = QrState.Loading) }
        qrJob = viewModelScope.launch {
            try {
                val session = api.createQrLogin()
                _uiState.update { it.copy(qrState = QrState.Waiting, qrContent = session.url) }
                val result = api.awaitQrLogin(session) { status ->
                    when (status) {
                        is MihoyoApi.QrStatus.Scanned ->
                            _uiState.update { s -> s.copy(qrState = QrState.Scanned) }
                        else -> {}
                    }
                }
                val account = AccountRepository.MihoyoAccount(
                    id = "mhy_${result.mid}",
                    nickname = result.nickname,
                    stoken = result.stoken,
                    stuid = result.stuid,
                    mid = result.mid,
                    cookie = "account_id=${result.stuid}; account_id_v2=${result.stuid}; account_mid_v2=${result.mid}; ltoken=; luid=${result.stuid}; ltmid_v2=${result.mid}; stuid=${result.stuid}; stoken=${result.stoken}; mid=${result.mid}",
                )
                // 验证 stoken（老接口）
                val valid = api.validateStoken(account)
                if (!valid) {
                    AppLog.w("MHY", "stoken 验证失败，仍已保存（可能需要稍后重试）")
                }
                accounts.addMihoyoAccount(account)
                AppLog.i("MHY", "账号 ${result.nickname} 扫码登录成功")
                _uiState.update { it.copy(qrState = QrState.Confirmed) }
                refresh()
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
                AppLog.e("MHY", "扫码登录失败: ${t.message}")
                _uiState.update {
                    it.copy(
                        qrState = if (t.message?.contains("过期") == true)
                            QrState.Expired else QrState.Error
                    )
                }
            }
        }
    }

    fun cancelQr() {
        qrJob?.cancel()
        qrJob = null
        _uiState.update { it.copy(qrState = QrState.Idle) }
    }

    /** 返回 false 表示 cookie 不可解析，弹窗保留输入让用户改。 */
    fun importCookie(cookie: String): Boolean {
        val account = MihoyoApi.parseCookie(cookie.trim())
        if (account == null) {
            AppLog.e("MHY", "Cookie 无效：缺少 stoken 或 uid")
            return false
        }
        viewModelScope.launch {
            val valid = api.validateStoken(account)
            if (!valid) {
                AppLog.w("MHY", "stoken 验证未通过，仍已保存")
            }
            accounts.addMihoyoAccount(account)
            AppLog.i("MHY", "账号 ${account.nickname} Cookie 导入成功")
            refresh()
        }
        return true
    }

    fun removeMihoyo(id: String) {
        accounts.removeMihoyoAccount(id)
        refresh()
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

    // ==================== Bing ====================

    fun setBingLoginMode(mode: Int) {
        _uiState.update { it.copy(bingLoginMode = mode) }
    }

    /**
     * 生成授权页并展示内嵌 WebView。
     * 这一步不发网络请求：URL 是纯拼接，真正的登录发生在 WebView 里。
     */
    fun startBingAuth() {
        bingJob?.cancel()
        val csrf = randomState()
        _uiState.update {
            it.copy(
                bingAuthCsrf = csrf,
                bingAuthUrl = BingOAuth.authorizeUrl(csrf),
                bingAuthPhase = QrState.Waiting,
            )
        }
    }

    fun cancelBingAuth() {
        bingJob?.cancel()
        bingJob = null
        _uiState.update {
            it.copy(bingAuthPhase = QrState.Idle, bingAuthUrl = "", bingAuthCsrf = "")
        }
    }

    /**
     * WebView 拦到授权回跳：校验 state -> 用授权码换 token -> 落库。
     * 返回 true 表示这个回跳已经被消费（WebView 不该再加载它）。
     */
    fun handleBingRedirect(url: String): Boolean {
        if (!BingOAuth.isRedirect(url)) return false

        BingOAuth.extractError(url)?.let { error ->
            AppLog.e("BING", "授权被拒绝：" + error)
            _uiState.update { it.copy(bingAuthPhase = QrState.Error) }
            return true
        }

        val code = BingOAuth.extractCode(url) ?: return false
        val expected = _uiState.value.bingAuthCsrf
        if (expected.isNotEmpty() && BingOAuth.extractState(url) != expected) {
            AppLog.e("BING", "授权回跳 state 不匹配，已忽略")
            _uiState.update { it.copy(bingAuthPhase = QrState.Error) }
            return true
        }

        bingJob?.cancel()
        bingJob = viewModelScope.launch {
            _uiState.update { it.copy(bingAuthPhase = QrState.Loading) }
            val token = bingOAuth.exchangeCode(code)
            if (token == null) {
                AppLog.e("BING", "授权码换 token 失败，请重新登录")
                _uiState.update { it.copy(bingAuthPhase = QrState.Error) }
                return@launch
            }
            if (token.refreshToken.isEmpty()) {
                // 没有 refresh_token 就撑不过一小时，宁可当场报错让用户重试
                AppLog.e("BING", "微软未返回 refresh_token，无法长期免登录")
                _uiState.update { it.copy(bingAuthPhase = QrState.Error) }
                return@launch
            }
            persistBing(
                refreshToken = token.refreshToken,
                fallbackAccessToken = token.accessToken,
                fallbackExpiresIn = token.expiresInSeconds,
            )
            _uiState.update { it.copy(bingAuthPhase = QrState.Confirmed) }
            publishAccounts()
        }
        return true
    }

    /**
     * 粘贴 refresh_token 导入。
     * 返回 false 表示格式不可接受，弹窗保留输入让用户改。
     */
    fun importBingToken(raw: String): Boolean {
        val token = raw.trim()
        if (token.length < MIN_BING_REFRESH_TOKEN_LENGTH || token.any { it.isWhitespace() }) {
            AppLog.e("BING", "refresh_token 格式不正确（长度不足或含空白字符）")
            return false
        }
        viewModelScope.launch {
            persistBing(refreshToken = token, fallbackAccessToken = "", fallbackExpiresIn = 0L)
            publishAccounts()
        }
        return true
    }

    fun removeBing(id: String) {
        accounts.removeBingAccount(id)
        refresh()
    }

    /**
     * 落库一个 Bing 账号。
     *
     * 先用 refresh_token 探一次（顺带拿到地区 / 余额 / 昵称）；探测失败也照样存下来，
     * 与米游社 cookie 导入一致：导入即纳管，可用性由下次执行时的日志给出。
     */
    private suspend fun persistBing(
        refreshToken: String,
        fallbackAccessToken: String,
        fallbackExpiresIn: Long,
    ) {
        if (refreshToken.isEmpty() && fallbackAccessToken.isEmpty()) {
            AppLog.e("BING", "没有可用的凭据，未保存")
            return
        }
        val probe = refreshToken.takeIf { it.isNotEmpty() }?.let {
            BingEngine.probe(
                client = fewardsApp.okhttpClient,
                oauth = bingOAuth,
                refreshToken = it,
                country = BingConstants.DEFAULT_COUNTRY,
            )
        }
        val finalRefresh = probe?.refreshToken?.takeIf { it.isNotEmpty() } ?: refreshToken
        val id = BingIdentity.accountId(finalRefresh)
        val existed = accounts.bingAccounts().any { it.id == id }
        accounts.addBingAccount(
            AccountRepository.BingAccount(
                id = id,
                label = probe?.displayName?.takeIf { it.isNotEmpty() }
                    ?: BingIdentity.fallbackLabel(finalRefresh),
                refreshToken = finalRefresh,
                accessToken = probe?.accessToken ?: fallbackAccessToken,
                expiresAt = probe?.expiresAt
                    ?: (System.currentTimeMillis() / 1000 + fallbackExpiresIn),
                country = probe?.country ?: BingConstants.DEFAULT_COUNTRY,
                lastBalance = probe?.balance ?: -1,
            )
        )
        AppLog.i("BING", if (existed) "Bing 账号已更新" else "Bing 账号已添加")
        if (probe == null) {
            AppLog.w("BING", "未能验证该账号（网络或授权问题），已先保存；执行时会再试一次")
        }
    }

    /** 一次性 CSRF state。 */
    private fun randomState(): String {
        val bytes = ByteArray(16)
        java.security.SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private companion object {
        /** refresh_token 是长串不透明凭据；明显过短 / 带空白的一律当格式错误。 */
        const val MIN_BING_REFRESH_TOKEN_LENGTH = 20

        /** 积分余额缓存时长，与 b 端 `BALANCE_CACHE_TTL_MS` 一致 */
        const val CREDIT_CACHE_TTL_MS = 2 * 60 * 1000L
    }

    val accountActions = AccountActions(
        onSetLoginMode = ::setLoginMode,
        onStartQr = ::startQrLogin,
        onCancelQr = ::cancelQr,
        onImportCookie = ::importCookie,
        onRemoveMihoyo = ::removeMihoyo,
        onImportWbToken = ::importWbToken,
        onRemoveWb = ::removeWb,
        onSetWbLoginMode = ::setWbLoginMode,
        onStartWbQr = ::startWbQrLogin,
        onCancelWbQr = ::cancelWbQr,
        onViewWbCredits = ::viewWbCredits,
        onCloseWbCredits = ::closeWbCredits,
        onRefreshWbCredits = { refreshWbCredits(it) },
        onSetBingLoginMode = ::setBingLoginMode,
        onStartBingAuth = ::startBingAuth,
        onCancelBingAuth = ::cancelBingAuth,
        onBingRedirect = ::handleBingRedirect,
        onImportBingToken = ::importBingToken,
        onRemoveBing = ::removeBing,
    )
}
