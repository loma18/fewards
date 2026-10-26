package com.functy.fewards.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.functy.fewards.ui.viewmodel.TaskRunner

/**
 * WorkManager 包装：手动执行经由这里入队，保证进程被杀后任务仍能执行。
 * 现在只有 WorkBuddy 一项，入参退化成单个布尔。
 */
class TaskWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val runWb = inputData.getBoolean(KEY_RUN_WB, false)
        return try {
            TaskRunner.execute(runWb)
            Result.success()
        } catch (t: Throwable) {
            Result.retry()
        }
    }

    companion object {
        const val KEY_RUN_WB = "run_wb"

        fun enqueue(context: Context, runWb: Boolean) {
            val request = OneTimeWorkRequestBuilder<TaskWorker>()
                .setInputData(workDataOf(KEY_RUN_WB to runWb))
                .build()
            WorkManager.getInstance(context).enqueue(request)
        }
    }
}
