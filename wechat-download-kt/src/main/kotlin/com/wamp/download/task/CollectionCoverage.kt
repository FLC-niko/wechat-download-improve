package com.wamp.download.task

internal enum class CollectionTerminalDecision {
    SUCCEEDED,
    EMPTY,
    FAILED_INCOMPLETE,
}

/** 采集覆盖度的纯函数，避免把“返回非空”误当成“已经覆盖请求窗口”。 */
internal object CollectionCoverage {
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
