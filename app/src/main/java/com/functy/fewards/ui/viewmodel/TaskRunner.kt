package com.functy.fewards.ui.viewmodel

import com.functy.fewards.core.AppLog
import com.functy.fewards.core.workbuddy.WorkBuddyEngine
import com.functy.fewards.core.workbuddy.WorkBuddyGrowth
import com.functy.fewards.core.workbuddy.WorkBuddyLabel
import com.functy.fewards.data.repository.AccountRepository
import com.functy.fewards.data.repository.SettingsRepositoryImpl
import com.functy.fewards.fewardsApp
import com.functy.fewards.work.RunTasksTileService
import com.functy.fewards.work.TaskNotifier
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 任务执行逻辑：WorkBuddy 签到 + 成长中心。
 * 入队入口在 work.TaskWorker.enqueue，保证进程被杀后仍可执行。
 *
 * 米游社与 Bing 已移除，现在只有一条分支，不再需要并行编排；
 * 保留 Job/async 结构是为了将来再加任务时不必重写等待逻辑。
 */
object TaskRunner {

    enum class TaskStatus { UNCONFIGURED, QUERYING, DONE, NOT_DONE }

    data class TaskUiState(
        val wbStatus: TaskStatus = TaskStatus.UNCONFIGURED,
        val wbRunning: Boolean = false,
        val running: Boolean = false,
        val lastRunSummary: String = "",
    )

    private val _state = MutableStateFlow(TaskUiState())
    val state: StateFlow<TaskUiState> = _state.asStateFlow()

    private val repo by lazy { SettingsRepositoryImpl() }
    private val accounts by lazy { AccountRepository() }

    /** 按本地完成标记刷新状态卡（跨天自动重置）。 */
    fun refreshStatus() {
        _state.value = _state.value.copy(
            wbStatus = resolveStatus(configured = accounts.workBuddyConfigured(), done = accounts.isDoneToday("wb")),
        )
    }

    private fun resolveStatus(configured: Boolean, done: Boolean): TaskStatus = when {
        !configured -> TaskStatus.UNCONFIGURED
        done -> TaskStatus.DONE
        else -> TaskStatus.NOT_DONE
    }

    /** 直接执行（Worker 调用）。 */
    suspend fun execute(runWb: Boolean = true): String {
        if (_state.value.running) return "已有任务在执行中"
        _state.value = _state.value.copy(
            running = true,
            wbStatus = if (runWb && _state.value.wbStatus != TaskStatus.UNCONFIGURED) {
                TaskStatus.QUERYING
            } else {
                _state.value.wbStatus
            },
        )
        RunTasksTileService.refresh(fewardsApp)
        var allOk = true
        if (runWb) TaskNotifier.startRun("正在执行…")
        val summaries = mutableListOf<String>()
        try {
            fun onStepDone(ok: Boolean) {
                if (!ok) allOk = false
                TaskNotifier.onStep(1, 1, summaries.joinToString("；"))
            }
            if (runWb) launchJobWb(summaries, ::onStepDone).join()
        } finally {
            _state.value = _state.value.copy(running = false)
            RunTasksTileService.refresh(fewardsApp)
        }
        val summary = summaries.joinToString("；").ifEmpty { "未选择任何任务" }
        _state.value = _state.value.copy(lastRunSummary = summary)
        if (runWb) TaskNotifier.complete(allOk, summary)
        return summary
    }

    private suspend fun launchJobWb(summaries: MutableList<String>, onStepDone: (Boolean) -> Unit): Job =
        kotlinx.coroutines.coroutineScope {
            val deferred = async {
                _state.value = _state.value.copy(wbRunning = true, wbStatus = TaskStatus.QUERYING)
                try {
                    val all = accounts.workBuddyAccounts()
                    // WB label：优先用 JWT 内实际昵称；历史占位名一并更新
                    all.forEach { acc ->
                        if (acc.label == "WorkBuddy 账号" || acc.label == "Work Buddy 账号") {
                            val real = WorkBuddyLabel.decode(acc.token)
                            if (real != null) {
                                accounts.addWorkBuddyAccount(acc.copy(label = real))
                            }
                        }
                    }
                    if (all.isEmpty() || !repo.wbMasterEnabled) {
                        if (all.isEmpty()) AppLog.w("WB", "WorkBuddy 未配置账号，跳过")
                        _state.value = _state.value.copy(wbRunning = false, wbStatus = TaskStatus.UNCONFIGURED)
                        return@async
                    }
                    /**
                     * 签到只有国内版有接口（端点在 copilot.tencent.com 上，国际版打过去是跨档请求），
                     * 国际版账号在这里就滤掉，不去撞一遍拿 401 回来。
                     *
                     * 全是国际版账号时按"今天没事可做"处理：不是失败，也不该让首页一直挂着未完成。
                     */
                    val list = all.filter { it.wbVariant.supportsCheckin }
                    val intlCount = all.size - list.size
                    if (list.isEmpty()) {
                        AppLog.i("WB", "只有国际版账号（$intlCount 个），暂无签到接口，跳过")
                        accounts.markDoneToday("wb")
                        summaries.add("WorkBuddy: 国际版暂不支持签到（$intlCount 个账号跳过）")
                        _state.value = _state.value.copy(wbRunning = false, wbStatus = TaskStatus.DONE)
                        onStepDone(true)
                        return@async
                    }
                    val engine = WorkBuddyEngine(fewardsApp.okhttpClient, list)
                    val ok = engine.runAll()

                    /**
                     * 成长中心排在签到之后：签到给的基础积分先到账，旅行/任务/兑换/盲盒
                     * 才有可能解锁新的奖励（比如任务送的抽奖机会）。
                     *
                     * 每个账号独立跑，一个失败不影响其余；硬失败（5xx / 网络不可达）只记日志，
                     * 不把整轮签到判成失败——签到本身可能已经成功了。
                     */
                    if (repo.wbGrowthEnabled) {
                        val growth = WorkBuddyGrowth(fewardsApp.okhttpClient)
                        var growthCredits = 0
                        for (acc in list) {
                            val r = runCatching { growth.run(acc) }.getOrNull()
                            if (r == null) {
                                AppLog.w("WB", "成长中心 ${acc.label}: 执行异常")
                                continue
                            }
                            growthCredits += r.creditsGained
                            if (r.parts.isNotEmpty() || r.creditsGained > 0) {
                                AppLog.i("WB", "成长中心 ${acc.label}: ${r.report}")
                            }
                            kotlinx.coroutines.delay(400)
                        }
                        if (growthCredits > 0) summaries.add("成长中心: +$growthCredits 积分")
                    }
                    if (intlCount > 0) summaries.add("WorkBuddy: $intlCount 个国际版账号跳过（暂无签到与成长中心）")

                    if (ok) accounts.markDoneToday("wb")
                    _state.value = _state.value.copy(
                        wbRunning = false,
                        wbStatus = if (ok) TaskStatus.DONE else TaskStatus.NOT_DONE
                    )
                    summaries.add("WorkBuddy: ${if (ok) "完成" else "存在失败项"}")
                    onStepDone(ok)
                } catch (t: Throwable) {
                    AppLog.e("WB", "WorkBuddy 执行异常: ${t.message}")
                    _state.value = _state.value.copy(wbRunning = false, wbStatus = TaskStatus.NOT_DONE)
                    summaries.add("WorkBuddy: 异常")
                }
            }
            deferred
        }
}
