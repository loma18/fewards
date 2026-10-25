package com.functy.fewards.core.workbuddy

import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 成长中心的补登日期挑选（纯函数）。
 *
 * 这段逻辑最容易错、错了又最贵：挑错日期会消耗不可再生的补登卡。
 * 口径必须与官方成长中心一致：**当月 + 今天之前 + 活动上线之后 + score==0 + 未补过**。
 */
class WorkBuddyGrowthTest {

    private val growth = WorkBuddyGrowth(OkHttpClient())

    private fun streak(launch: String = "2026-06-17", madeUp: List<String> = emptyList()) =
        JSONObject().apply {
            put("launch_date", launch)
            put(
                "streak",
                JSONObject().apply {
                    put("days", 5)
                    put("makeup_dates", org.json.JSONArray(madeUp))
                }
            )
        }

    private fun heatmap(today: String, cells: List<Pair<String, Int>>) =
        JSONObject().apply {
            put("today", JSONObject().put("date", today))
            put(
                "cells",
                org.json.JSONArray().apply {
                    cells.forEach { (d, s) -> put(JSONObject().put("date", d).put("score", s)) }
                }
            )
        }

    @Test
    fun picksZeroScoreDaysBeforeToday() {
        val body = heatmap(
            "2026-10-26",
            listOf(
                "2026-10-10" to 0,
                "2026-10-12" to 0,
                "2026-10-20" to 30,
                "2026-10-26" to 0,
            )
        )
        val out = growth.makeupCandidates(streak(), body)
        // 有分数的、今天的都不算断登
        assertEquals(listOf("2026-10-12", "2026-10-10"), out)
    }

    @Test
    fun skipsFurureDays() {
        val body = heatmap("2026-10-26", listOf("2026-10-27" to 0, "2026-10-28" to 0))
        assertTrue(growth.makeupCandidates(streak(), body).isEmpty())
    }

    @Test
    fun skipsPreviousMonth() {
        // 只要当月：9-30 即使在活动期内也不能补
        val body = heatmap("2026-10-01", listOf("2026-09-30" to 0))
        assertTrue(growth.makeupCandidates(streak(), body).isEmpty())
    }

    @Test
    fun skipsDaysBeforeLaunch() {
        // launch_date 晚于月初时，以 launch_date 为准
        val body = heatmap("2026-10-26", listOf("2026-10-05" to 0, "2026-10-20" to 0))
        val out = growth.makeupCandidates(streak(launch = "2026-10-15"), body)
        assertEquals(listOf("2026-10-20"), out)
    }

    @Test
    fun skipsAlreadyMadeUpDays() {
        // makeup_dates 是"已补过"的历史，不是待办清单
        val body = heatmap("2026-10-26", listOf("2026-10-10" to 0, "2026-10-12" to 0))
        val out = growth.makeupCandidates(streak(madeUp = listOf("2026-10-10")), body)
        assertEquals(listOf("2026-10-12"), out)
    }

    @Test
    fun newestFirst() {
        val body = heatmap(
            "2026-10-26",
            listOf("2026-10-02" to 0, "2026-10-20" to 0, "2026-10-11" to 0)
        )
        // 最近的断点优先：尽早恢复当前连登
        assertEquals(listOf("2026-10-20", "2026-10-11", "2026-10-02"), growth.makeupCandidates(streak(), body))
    }

    @Test
    fun throwsWhenServerDateMissing() {
        // 结构异常时必须抛错，绝不能"猜一个日期"去消耗补登卡
        val body = JSONObject().put("cells", org.json.JSONArray())
        val err = runCatching { growth.makeupCandidates(streak(), body) }.exceptionOrNull()
        assertTrue(err is IllegalStateException)
    }

    @Test
    fun throwsWhenScoreInvalid() {
        val body = JSONObject().apply {
            put("today", JSONObject().put("date", "2026-10-26"))
            put("cells", org.json.JSONArray().put(JSONObject().put("date", "2026-10-10").put("score", -1)))
        }
        val err = runCatching { growth.makeupCandidates(streak(), body) }.exceptionOrNull()
        assertTrue(err is IllegalStateException)
    }
}
