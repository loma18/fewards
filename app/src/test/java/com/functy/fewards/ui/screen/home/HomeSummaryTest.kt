package com.functy.fewards.ui.screen.home

import com.functy.fewards.ui.viewmodel.TaskRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 首页卡片的可见性与完成判定（现在只有 WorkBuddy 一个任务）。
 *
 * 规则：总开关关掉的任务整行不出现，也不参与「全部完成」——
 * 否则一个关掉的、今天没跑的任务会把顶部大卡永远按在「未完成」。
 */
class HomeSummaryTest {

    private fun state(
        wbStatus: TaskRunner.TaskStatus = TaskRunner.TaskStatus.UNCONFIGURED,
        wbChecked: Boolean = true,
        wbEnabled: Boolean = true,
    ) = HomeUiState(
        wbStatus = wbStatus,
        wbChecked = wbChecked,
        wbEnabled = wbEnabled,
    )

    @Test
    fun enabledAndDoneIsAllDone() {
        val s = state(wbStatus = TaskRunner.TaskStatus.DONE).summary()
        assertTrue(s.wbVisible)
        assertTrue(s.anyVisible)
        assertTrue(s.allDone)
        assertTrue(s.hasAnyConfigured)
    }

    @Test
    fun disabledHidesRowAndEverythingElse() {
        val s = state(wbStatus = TaskRunner.TaskStatus.DONE, wbEnabled = false).summary()
        assertFalse(s.wbVisible)
        assertFalse(s.anyVisible)
        assertFalse(s.allDone)
        assertFalse(s.hasAnyConfigured)
        assertFalse(s.canRun)
    }

    @Test
    fun unconfiguredIsNotAllDone() {
        val s = state(wbStatus = TaskRunner.TaskStatus.UNCONFIGURED).summary()
        assertFalse(s.allDone)
        assertFalse(s.hasAnyConfigured)
    }

    @Test
    fun undoneIsNotAllDone() {
        val s = state(wbStatus = TaskRunner.TaskStatus.NOT_DONE).summary()
        assertFalse(s.allDone)
        assertTrue(s.hasAnyConfigured)
    }

    @Test
    fun runningIsNotAllDone() {
        val s = state(wbStatus = TaskRunner.TaskStatus.QUERYING).summary()
        assertFalse(s.allDone)
    }

    @Test
    fun uncheckedDoesNotRun() {
        val s = state(wbStatus = TaskRunner.TaskStatus.DONE, wbChecked = false).summary()
        assertFalse(s.runWb)
        assertFalse(s.canRun)
    }

    @Test
    fun disabledNeverRunsEvenIfChecked() {
        val s = state(wbChecked = true, wbEnabled = false).summary()
        assertFalse(s.runWb)
    }

    @Test
    fun checkedAndEnabledRuns() {
        val s = state(wbStatus = TaskRunner.TaskStatus.NOT_DONE, wbChecked = true).summary()
        assertEquals(true, s.runWb)
        assertTrue(s.canRun)
    }
}
