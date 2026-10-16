package com.wamp.download.weread.eink

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.google.gson.Gson
import org.slf4j.LoggerFactory
import kotlin.test.*

class WeReadEinkAutoSolverLogTest {
    @Test
    fun `events appear in logs and status without tickets secret nonce or raw response`() {
        val logger = LoggerFactory.getLogger(WeReadEinkAutoSolverLog::class.java) as Logger
        val captured = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(captured)
        try {
            val diagnostics = WeReadEinkAutoSolverDiagnostics(vid = "log-test-vid")
            diagnostics.begin()
            diagnostics.record(WeReadEinkSolverEvent.TRIGGERED, enabled = true, allowlisted = true,
                secretConfigured = true)
            diagnostics.record(WeReadEinkSolverEvent.SOLVE_RESULT, solve = WeReadEinkCaptchaSolveResult(
                WeReadEinkCaptchaSolveCode.SOLVED,
                WeReadEinkCaptchaTicket("private-ticket", "private-randstr"), 1, 12, 200,
            ))
            diagnostics.finish(WeReadEinkAutoRecoveryAttempt(WeReadEinkAutoRecoveryCode.RECOVERED, 1))
            diagnostics.record(WeReadEinkSolverEvent.FINISHED, result = WeReadEinkAutoRecoveryCode.RECOVERED)
            val events = diagnostics.recentEvents()
            assertEquals(3, events.size)
            assertNotNull(events.first().attemptId)
            assertEquals(1, events.map { it.attemptId }.distinct().size)
            val logText = captured.list.joinToString("\n") { it.formattedMessage }
            val status = Gson().toJson(diagnostics.status(true, true, true))
            assertTrue(logText.contains("EINK_AUTO_SOLVER"))
            assertTrue(logText.contains("http_status=200"))
            assertTrue(logText.contains("solver_result=SOLVED"))
            assertTrue(status.contains("recent_events"))
            for (secret in listOf("private-ticket", "private-randstr", "private-secret", "private-nonce")) {
                assertFalse(logText.contains(secret))
                assertFalse(status.contains(secret))
            }
        } finally {
            logger.detachAppender(captured)
            captured.stop()
        }
    }

    @Test
    fun `history is bounded copied on reload and each attempt gets an independent id`() {
        val diagnostics = WeReadEinkAutoSolverDiagnostics(vid = "history-vid")
        diagnostics.begin()
        diagnostics.record(WeReadEinkSolverEvent.TRIGGERED)
        val oldId = diagnostics.snapshot().attemptId
        diagnostics.begin()
        diagnostics.record(WeReadEinkSolverEvent.TRIGGERED)
        assertNotEquals(oldId, diagnostics.snapshot().attemptId)
        repeat(100) { diagnostics.record(WeReadEinkSolverEvent.REMINDER_SUPPRESSED) }
        assertEquals(WeReadEinkAutoSolverLog.MAX_ENTRIES, diagnostics.recentEvents().size)
        val replacement = WeReadEinkAutoSolverDiagnostics(
            initial = diagnostics.afterReload(), vid = "history-vid", initialEvents = diagnostics.recentEvents(),
        )
        val snapshot = replacement.recentEvents()
        diagnostics.record(WeReadEinkSolverEvent.STALE_TASK_DROPPED)
        assertEquals(snapshot, replacement.recentEvents(), "old log history must not mutate the replacement")
    }
}
