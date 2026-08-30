package com.wamp.download.task

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CollectionCoverageTest {
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
}
