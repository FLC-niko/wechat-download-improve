package com.wamp.download.weread.eink

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class WeReadEinkAutoSolverDiagnosticsTest {
    private val vid = "diagnostic-vid"
    private val verification = WeReadEinkVerificationStatus(
        WeReadEinkVerificationState.WAITING,
        verifyUrl = "http://127.0.0.1/verify/$vid/private-nonce",
    )

    @Test
    fun `disabled and excluded accounts skip solver with explicit manual reasons`() = runBlocking {
        for ((enabled, allowlist, expected) in listOf(
            Triple(false, setOf(vid), WeReadEinkAutoRecoveryCode.DISABLED),
            Triple(true, emptySet(), WeReadEinkAutoRecoveryCode.NOT_ALLOWLISTED),
            Triple(true, setOf("other"), WeReadEinkAutoRecoveryCode.NOT_ALLOWLISTED),
        )) {
            val tracker = WeReadEinkAutoSolverDiagnostics()
            tracker.begin()
            val result = performEinkAutoRecovery(
                enabled, allowlist, vid, verification,
                solve = { error("must not solve") },
                complete = { _, _, _, _ -> error("must not probe") },
            )
            tracker.finish(result)
            var notifications = 0
            sendEinkManualFallback(result, { verification }) {
                notifications++
                assertEquals(expected.name, result.notificationDiagnostics()["auto_solver_result"])
                assertEquals(expected.manualReason(), result.notificationDiagnostics()["auto_solver_reason"])
            }
            assertEquals(expected, result.code)
            assertEquals(1, notifications)
            assertEquals("MANUAL_REQUIRED", tracker.status(enabled, vid in allowlist)["phase"])
            assertEquals(false, tracker.status(enabled, vid in allowlist)["eligible"])
        }
    }

    @Test
    fun `HTTP failures and timeout produce classified manual notifications without raw data`() = runBlocking {
        val cases = listOf(
            Triple(401, "private-server-error", WeReadEinkAutoRecoveryCode.SIDE_CAR_UNAUTHORIZED),
            Triple(500, "private-server-error", WeReadEinkAutoRecoveryCode.SIDE_CAR_UNAVAILABLE),
            Triple(200, "not-json-private-server-error", WeReadEinkAutoRecoveryCode.INVALID_RESULT),
            Triple(200, """{"ok":false,"error":"private-server-error","attempts":1}""", WeReadEinkAutoRecoveryCode.SOLVE_FAILED),
            Triple(200, """{"ok":true,"ticket":"","randstr":"private-randstr"}""", WeReadEinkAutoRecoveryCode.INVALID_RESULT),
            Triple(200, "timeout", WeReadEinkAutoRecoveryCode.SIDE_CAR_UNAVAILABLE),
        )
        for ((status, body, expected) in cases) {
            val release = CountDownLatch(1)
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.createContext("/solve") { exchange ->
                try {
                    if (body == "timeout") release.await(5, TimeUnit.SECONDS)
                    val bytes = body.toByteArray()
                    exchange.sendResponseHeaders(status, bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                } catch (_: java.io.IOException) {
                    // Expected when the client times out and closes the connection.
                } finally { exchange.close() }
            }
            server.start()
            try {
                val client = WeReadEinkCaptchaSolverClient(
                    "http://127.0.0.1:${server.address.port}", "test-appid", "private-secret", 1, 1,
                )
                val tracker = WeReadEinkAutoSolverDiagnostics()
                tracker.begin()
                val attempt = performEinkAutoRecovery(
                    true, setOf(vid), vid, verification,
                    solve = client::solve,
                    complete = { _, _, _, _ -> error("failure must not probe") },
                    onPhase = tracker::transition,
                    onSolved = { tracker.record(WeReadEinkSolverEvent.SOLVE_RESULT, solve = it) },
                )
                tracker.finish(attempt)
                var notifications = 0
                sendEinkManualFallback(attempt, { verification }) {
                    notifications++
                    assertEquals(expected.name, attempt.notificationDiagnostics()["auto_solver_result"])
                }
                assertEquals(expected, attempt.code)
                val event = tracker.recentEvents().single()
                if (body == "timeout") {
                    assertEquals(WeReadEinkSolverFailure.TIMEOUT, event.failure)
                } else {
                    assertEquals(status, event.httpStatus)
                    assertNotNull(event.failure)
                }
                assertEquals(1, notifications)
                val exposed = tracker.status(true, true).toString() + attempt.notificationDiagnostics()
                for (secret in listOf("private-server-error", "private-randstr", "private-secret", "private-nonce")) {
                    assertFalse(exposed.contains(secret))
                }
            } finally {
                release.countDown()
                server.stop(0)
            }
        }
    }

    @Test
    fun `account gate admits one concurrent solve and another account remains independent`() {
        val first = WeReadEinkAutoSolverDiagnostics()
        val other = WeReadEinkAutoSolverDiagnostics()
        val accepted = AtomicInteger()
        val ready = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(8)
        try {
            val futures = (1..16).map {
                pool.submit {
                    ready.await()
                    if (first.tryAcquire()) accepted.incrementAndGet()
                }
            }
            ready.countDown()
            futures.forEach { it.get(5, TimeUnit.SECONDS) }
            assertEquals(1, accepted.get())
            assertTrue(other.tryAcquire())
            first.begin()
            first.transition(WeReadEinkAutoSolverPhase.SOLVING)
            assertEquals(WeReadEinkAutoSolverPhase.IDLE, other.snapshot().phase)
            first.release()
            assertTrue(first.tryAcquire())
        } finally { pool.shutdownNow() }
    }

    @Test
    fun `reload preserves completed diagnostics and invalidates running snapshot independently`() {
        val old = WeReadEinkAutoSolverDiagnostics()
        old.begin()
        old.transition(WeReadEinkAutoSolverPhase.SOLVING)
        val replacement = WeReadEinkAutoSolverDiagnostics(old.afterReload())
        old.finish(WeReadEinkAutoRecoveryAttempt(WeReadEinkAutoRecoveryCode.RECOVERED, 1))
        assertEquals(WeReadEinkAutoRecoveryCode.SESSION_CHANGED, replacement.snapshot().result)
        assertEquals(WeReadEinkAutoSolverPhase.MANUAL_REQUIRED, replacement.snapshot().phase)
        assertNotNull(replacement.snapshot().finishedAt)
        val finished = WeReadEinkAutoSolverDiagnostics(old.afterReload())
        assertEquals(old.snapshot(), finished.snapshot())
    }

    @Test
    fun `removed session cannot submit tickets or notify a replacement account`() = runBlocking {
        var current = true
        val phases = mutableListOf<WeReadEinkAutoSolverPhase>()
        val result = performEinkAutoRecovery(
            true, setOf(vid), vid, verification,
            solve = {
                current = false
                WeReadEinkCaptchaSolveResult(
                    WeReadEinkCaptchaSolveCode.SOLVED,
                    WeReadEinkCaptchaTicket("private-ticket", "private-randstr"), 1, 5,
                )
            },
            complete = { _, _, _, _ -> error("stale session must not submit") },
            onPhase = phases::add,
            isCurrent = { current },
        )
        assertEquals(WeReadEinkAutoRecoveryCode.SESSION_CHANGED, result.code)
        assertEquals(listOf(WeReadEinkAutoSolverPhase.SOLVING), phases)
        sendEinkManualFallback(result, { error("must not replace challenge") }) { error("must not notify") }
    }
}
