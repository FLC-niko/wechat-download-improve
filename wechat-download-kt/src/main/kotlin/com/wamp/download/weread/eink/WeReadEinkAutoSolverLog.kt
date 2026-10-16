package com.wamp.download.weread.eink

import org.slf4j.LoggerFactory
import java.util.ArrayDeque

internal enum class WeReadEinkSolverEvent {
    CONFIG, TRIGGERED, IN_FLIGHT_SUPPRESSED, REMINDER_SUPPRESSED, NO_SCOPE,
    SOLVE_STARTED, SOLVE_RESULT, PROBE_STARTED, FINISHED,
    MANUAL_NOTIFY_STARTED, MANUAL_NOTIFY_FINISHED, RELOADED, STALE_TASK_DROPPED,
}

internal enum class WeReadEinkSolverFailure {
    TIMEOUT, CONNECTION_FAILED, IO_ERROR, INVALID_RESPONSE, HTTP_ERROR,
    SOLVER_REJECTED, CONFIG_ERROR, UNEXPECTED_ERROR,
}

/** This schema deliberately cannot accept raw request/response text, URLs or credentials. */
internal data class WeReadEinkSolverLogEntry(
    val timestamp: Long,
    val event: WeReadEinkSolverEvent,
    val attemptId: String? = null,
    val result: WeReadEinkAutoRecoveryCode? = null,
    val httpStatus: Int? = null,
    val solverResult: WeReadEinkCaptchaSolveCode? = null,
    val failure: WeReadEinkSolverFailure? = null,
    val durationMs: Long? = null,
    val attempts: Int? = null,
    val textSent: Boolean? = null,
    val qrSent: Boolean? = null,
    val enabled: Boolean? = null,
    val allowlisted: Boolean? = null,
    val secretConfigured: Boolean? = null,
)

/** Bounded per-account history, also written to the existing console and app.log appenders. */
internal class WeReadEinkAutoSolverLog(
    private val vid: String,
    initial: List<WeReadEinkSolverLogEntry> = emptyList(),
) {
    private val entries = ArrayDeque(initial.takeLast(MAX_ENTRIES))

    @Synchronized
    fun record(entry: WeReadEinkSolverLogEntry) {
        if (entries.size == MAX_ENTRIES) entries.removeFirst()
        entries.addLast(entry)
        logger.info(
            "EINK_AUTO_SOLVER vid={} event={} attempt_id={} result={} http_status={} solver_result={} failure={} " +
                "duration_ms={} attempts={} text_sent={} qr_sent={} enabled={} allowlisted={} secret_configured={}",
            vid, entry.event, entry.attemptId, entry.result, entry.httpStatus, entry.solverResult, entry.failure,
            entry.durationMs, entry.attempts, entry.textSent, entry.qrSent,
            entry.enabled, entry.allowlisted, entry.secretConfigured,
        )
    }

    @Synchronized
    fun recent(): List<WeReadEinkSolverLogEntry> = entries.toList()

    companion object {
        const val MAX_ENTRIES = 32
        private val logger = LoggerFactory.getLogger(WeReadEinkAutoSolverLog::class.java)
    }
}
