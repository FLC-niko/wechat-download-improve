package com.wamp.download.weread

internal enum class WeReadPaginationStopReason {
    DUPLICATE_PAGE,
    OFFSET_NOT_ADVANCED,
}

internal data class WeReadPaginationAssessment(
    val pageIds: List<String>,
    val nextOffset: Int?,
    val stopReason: WeReadPaginationStopReason?,
)

internal object WeReadWebPaginationGuard {
    fun assess(
        currentOffset: Int,
        previousOffset: Int?,
        articleIds: List<String>,
        seenArticleIds: Set<String>,
    ): WeReadPaginationAssessment {
        val pageIds = articleIds.filter { it.isNotBlank() }
        if (previousOffset != null && currentOffset <= previousOffset) {
            return WeReadPaginationAssessment(pageIds, null, WeReadPaginationStopReason.OFFSET_NOT_ADVANCED)
        }
        if (pageIds.isNotEmpty() && pageIds.all { it in seenArticleIds }) {
            return WeReadPaginationAssessment(pageIds, null, WeReadPaginationStopReason.DUPLICATE_PAGE)
        }

        val nextOffset = currentOffset.toLong() + articleIds.size
        if (nextOffset <= currentOffset || nextOffset > Int.MAX_VALUE) {
            return WeReadPaginationAssessment(pageIds, null, WeReadPaginationStopReason.OFFSET_NOT_ADVANCED)
        }
        return WeReadPaginationAssessment(pageIds, nextOffset.toInt(), null)
    }
}