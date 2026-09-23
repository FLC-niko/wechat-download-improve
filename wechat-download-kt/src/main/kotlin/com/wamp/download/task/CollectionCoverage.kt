package com.wamp.download.task

internal enum class CollectionTerminalDecision {
    SUCCEEDED,
    EMPTY,
    FAILED_INCOMPLETE,
}

/** 采集覆盖度的纯函数，避免把“返回非空”误当成“已经覆盖请求窗口”。 */
internal object CollectionCoverage {
    /**
     * Remote APIs paginate from newest to oldest and cannot accept an end time. One request from
     * the oldest missing point therefore covers every newer gap; future time is never claimed.
     */
    fun remoteFetchEnvelope(
        requestedEnd: Long,
        now: Long,
        missingRanges: List<LongRange>,
    ): LongRange? = missingRanges.firstOrNull { it.first <= now }
        ?.let { it.first..minOf(requestedEnd, now) }

    /**
     * Remote APIs can only page down to "now": the future tail of a requested window is neither
     * collectable nor a coverage gap. Use this as the completeness bound so a same-day window is
     * not misjudged as incomplete (which would wrongly fall back to AAOS).
     */
    fun effectiveCoverageEnd(requestedStart: Long, requestedEnd: Long, now: Long): Long =
        minOf(requestedEnd, maxOf(requestedStart, now))

    /** Returns uncovered inclusive ranges after merging overlapping or adjacent verified intervals. */
    fun missingRanges(
        requestedStart: Long,
        requestedEnd: Long,
        coveredIntervals: List<LongRange>,
    ): List<LongRange> {
        require(requestedStart <= requestedEnd) { "requestedStart must not be after requestedEnd" }
        val merged = coveredIntervals
            .mapNotNull { interval ->
                val start = maxOf(requestedStart, interval.first)
                val end = minOf(requestedEnd, interval.last)
                if (start <= end) start..end else null
            }
            .sortedBy(LongRange::first)
            .fold(mutableListOf<LongRange>()) { result, interval ->
                val previous = result.lastOrNull()
                if (previous == null || interval.first > previous.last + 1) {
                    result += interval
                } else {
                    result[result.lastIndex] = previous.first..maxOf(previous.last, interval.last)
                }
                result
            }

        val missing = mutableListOf<LongRange>()
        var cursor = requestedStart
        for (interval in merged) {
            if (interval.first > cursor) missing += cursor..(interval.first - 1)
            cursor = maxOf(cursor, interval.last + 1)
            if (cursor > requestedEnd) break
        }
        if (cursor <= requestedEnd) missing += cursor..requestedEnd
        return missing
    }

    fun pageCoversWindowStart(
        publishTimes: List<Long>,
        requestedPageSize: Int,
        windowStart: Long,
    ): Boolean = publishTimes.size < requestedPageSize || publishTimes.any { it in 1..windowStart }

    fun terminalDecision(resultCount: Int, coverageComplete: Boolean): CollectionTerminalDecision = when {
        resultCount > 0 -> CollectionTerminalDecision.SUCCEEDED
        coverageComplete -> CollectionTerminalDecision.EMPTY
        else -> CollectionTerminalDecision.FAILED_INCOMPLETE
    }
}
