package com.wamp.download.task

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CollectionCoverageTest {
    @Test
    fun `完整缓存覆盖时不产生远端缺口`() {
        assertEquals(
            emptyList(),
            CollectionCoverage.missingRanges(1_000L, 2_000L, listOf(500L..2_500L)),
        )
    }

    @Test
    fun `合并缓存覆盖后只返回真正缺失区间`() {
        assertEquals(
            listOf(1_201L..1_499L, 1_801L..2_000L),
            CollectionCoverage.missingRanges(
                requestedStart = 1_000L,
                requestedEnd = 2_000L,
                coveredIntervals = listOf(1_500L..1_700L, 900L..1_200L, 1_701L..1_800L),
            ),
        )
    }

    @Test
    fun `没有覆盖记录时整个窗口都需要远端补齐`() {
        assertEquals(
            listOf(1_000L..2_000L),
            CollectionCoverage.missingRanges(1_000L, 2_000L, emptyList()),
        )
    }

    @Test
    fun `多个离散缺口只规划一次从最早缺口开始的远端翻页`() {
        assertEquals(
            1_000L..2_000L,
            CollectionCoverage.remoteFetchEnvelope(
                requestedEnd = 2_000L,
                now = 2_500L,
                missingRanges = listOf(1_000L..1_100L, 1_500L..1_600L),
            ),
        )
        assertEquals(
            1_000L..1_800L,
            CollectionCoverage.remoteFetchEnvelope(
                requestedEnd = 2_000L,
                now = 1_800L,
                missingRanges = listOf(1_000L..1_100L, 1_900L..2_000L),
            ),
        )
    }

    @Test
    fun `满页且全部晚于窗口起点时必须继续降级`() {
        val times = (1..20).map { 2_000L + it }

        assertFalse(CollectionCoverage.pageCoversWindowStart(times, requestedPageSize = 20, windowStart = 1_000L))
    }

    @Test
    fun `翻到窗口起点之前或服务端短页时视为覆盖`() {
        assertTrue(CollectionCoverage.pageCoversWindowStart(listOf(2_000L, 900L), 20, 1_000L))
        assertTrue(CollectionCoverage.pageCoversWindowStart(listOf(2_000L, 1_900L), 20, 1_000L))
    }

    @Test
    fun `只有确认覆盖后才允许发布EMPTY`() {
        assertEquals(
            CollectionTerminalDecision.FAILED_INCOMPLETE,
            CollectionCoverage.terminalDecision(resultCount = 0, coverageComplete = false),
        )
        assertEquals(
            CollectionTerminalDecision.EMPTY,
            CollectionCoverage.terminalDecision(resultCount = 0, coverageComplete = true),
        )
        assertEquals(
            CollectionTerminalDecision.SUCCEEDED,
            CollectionCoverage.terminalDecision(resultCount = 1, coverageComplete = false),
        )
    }

    @Test
    fun `覆盖边界不会把未来时段算作缺口`() {
        // 窗口末尾在未来（当天 23:59:59）时，覆盖判定只到当前时刻。
        assertEquals(1_800L, CollectionCoverage.effectiveCoverageEnd(1_000L, 2_000L, now = 1_800L))
        // 窗口完全在过去时保持原样。
        assertEquals(2_000L, CollectionCoverage.effectiveCoverageEnd(1_000L, 2_000L, now = 2_500L))
        // 窗口完全在未来时不会产生倒置区间。
        assertEquals(1_000L, CollectionCoverage.effectiveCoverageEnd(1_000L, 2_000L, now = 500L))
    }
}
