package com.wamp.download.weread

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WeReadWebPaginationGuardTest {
    @Test
    fun `accepts a new page and advances by article count`() {
        val result = WeReadWebPaginationGuard.assess(
            currentOffset = 0,
            previousOffset = null,
            articleIds = listOf("a", "b"),
            seenArticleIds = emptySet(),
        )

        assertEquals(listOf("a", "b"), result.pageIds)
        assertEquals(2, result.nextOffset)
        assertNull(result.stopReason)
    }

    @Test
    fun `stops when the server repeats a complete page`() {
        val result = WeReadWebPaginationGuard.assess(
            currentOffset = 10,
            previousOffset = 0,
            articleIds = listOf("a", "b"),
            seenArticleIds = setOf("a", "b", "c"),
        )

        assertEquals(WeReadPaginationStopReason.DUPLICATE_PAGE, result.stopReason)
        assertNull(result.nextOffset)
    }

    @Test
    fun `stops when offset does not advance`() {
        val result = WeReadWebPaginationGuard.assess(
            currentOffset = 10,
            previousOffset = 10,
            articleIds = listOf("a"),
            seenArticleIds = emptySet(),
        )

        assertEquals(WeReadPaginationStopReason.OFFSET_NOT_ADVANCED, result.stopReason)
        assertNull(result.nextOffset)
    }

    @Test
    fun `stops before integer offset overflow`() {
        val result = WeReadWebPaginationGuard.assess(
            currentOffset = Int.MAX_VALUE,
            previousOffset = null,
            articleIds = listOf("a"),
            seenArticleIds = emptySet(),
        )

        assertEquals(WeReadPaginationStopReason.OFFSET_NOT_ADVANCED, result.stopReason)
        assertNull(result.nextOffset)
    }
}