package com.wamp.download.weread.eink

import com.google.gson.Gson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class WeReadEinkVerificationEvaluationTest {
    @Test
    fun `generation identifies a reset even when new sequence catches up`() {
        val coordinator = WeReadEinkHumanVerificationCoordinator(nonceGenerator = { "local-nonce" }, probe = { true })
        coordinator.createOrReuse("a", "http://localhost")
        coordinator.complete("a", "local-nonce", "mock-ticket", "mock-randstr")
        val before = coordinator.evaluation("a")
        coordinator.reset()
        repeat(2) {
            coordinator.createOrReuse("a", "http://localhost")
            coordinator.complete("a", "local-nonce", "mock-ticket", "mock-randstr")
        }
        val after = coordinator.evaluation("a")
        assertEquals(2, after.schemaVersion)
        assertTrue(after.lastSequence >= before.lastSequence)
        assertNotEquals(before.generationId, after.generationId)
    }

    @Test
    fun `successful human verification records elapsed times and no credentials`() {
        var now = 1000L
        val coordinator = WeReadEinkHumanVerificationCoordinator(
            clock = { now },
            nonceGenerator = { "secret-local-nonce" },
            probe = { now = 1500L; true },
        )
        coordinator.createOrReuse("private-account", "http://localhost")
        now = 1200L
        assertTrue(coordinator.complete("private-account", "secret-local-nonce", "private-ticket", "private-randstr").ok)
        val snapshot = coordinator.evaluation("private-account")
        assertEquals(listOf("WAITING", "VERIFYING", "SUCCEEDED"), snapshot.events.map { it.state.name })
        assertEquals(500L, snapshot.events.last().elapsedMs)
        assertEquals(300L, snapshot.events.last().verificationMs)
        assertEquals(1, snapshot.events.map { it.sessionId }.distinct().size)
        assertTrue(coordinator.requestHeaders().isEmpty())
        val json = Gson().toJson(snapshot)
        listOf("private-account", "secret-local-nonce", "private-ticket", "private-randstr", "verifyUrl").forEach {
            assertFalse(json.contains(it), "evaluation must not contain $it")
        }
    }

    @Test
    fun `probe failure remains in history after replacement challenge`() {
        val coordinator = WeReadEinkHumanVerificationCoordinator(nonceGenerator = { "nonce" }, probe = { false })
        coordinator.createOrReuse("account", "http://localhost")
        assertFalse(coordinator.complete("account", "nonce", "test-ticket", "test-randstr").ok)
        coordinator.createOrReuse("account", "http://localhost")
        val events = coordinator.evaluation("account").events
        assertEquals(listOf("WAITING", "VERIFYING", "FAILED", "WAITING"), events.map { it.state.name })
        assertNotEquals(events.first().sessionId, events.last().sessionId)
    }

    @Test
    fun `expiry is recorded once`() {
        var now = 1000L
        val coordinator = WeReadEinkHumanVerificationCoordinator(ttlMs = 100, clock = { now }, probe = { true })
        coordinator.createOrReuse("account", "http://localhost")
        now = 1100L
        coordinator.evaluation("account")
        val events = coordinator.evaluation("account").events
        assertEquals(listOf("WAITING", "EXPIRED"), events.map { it.state.name })
    }

    @Test
    fun `per account history is bounded and reports dropped events`() {
        val coordinator = WeReadEinkHumanVerificationCoordinator(nonceGenerator = { "nonce" }, probe = { true })
        repeat(10) {
            coordinator.createOrReuse("account", "http://localhost")
            coordinator.complete("account", "nonce", "test-ticket", "test-randstr")
        }
        val snapshot = coordinator.evaluation("account")
        assertEquals(20, snapshot.events.size)
        assertEquals(30L, snapshot.lastSequence)
        assertEquals(10L, snapshot.droppedEventCount)
        assertEquals(11L, snapshot.events.first().sequence)
    }

    @Test
    fun `accounts stay separate and reset removes history`() {
        val coordinator = WeReadEinkHumanVerificationCoordinator(nonceGenerator = { "nonce" }, probe = { true })
        coordinator.createOrReuse("account-a", "http://localhost")
        coordinator.createOrReuse("account-b", "http://localhost")
        coordinator.complete("account-a", "nonce", "test-ticket", "test-randstr")
        assertEquals(3, coordinator.evaluation("account-a").events.size)
        assertEquals(1, coordinator.evaluation("account-b").events.size)
        assertTrue(coordinator.evaluation("unknown-account").events.isEmpty())
        coordinator.reset()
        assertTrue(coordinator.evaluation("account-a").events.isEmpty())
    }
}
