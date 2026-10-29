package com.wamp.download.weread.eink

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

object WeReadEinkTerminalActivationStats {
    private class Counter {
        val attempted = AtomicLong(0L)
        val succeeded = AtomicLong(0L)
        val failed = AtomicLong(0L)
        val stillHumanVerify = AtomicLong(0L)

        @Volatile
        var lastOutcome: String = "NEVER"

        @Volatile
        var lastAt: Long = 0L
    }

    private val counters = ConcurrentHashMap<String, Counter>()

    fun recordAttempt(vid: String) {
        counter(vid).attempted.incrementAndGet()
    }

    fun recordSuccess(vid: String) {
        counter(vid).apply {
            succeeded.incrementAndGet()
            lastOutcome = "ACTIVATED"
            lastAt = System.currentTimeMillis()
        }
    }

    fun recordFailure(vid: String) {
        counter(vid).apply {
            failed.incrementAndGet()
            lastOutcome = "FAILED"
            lastAt = System.currentTimeMillis()
        }
    }

    fun recordStillHumanVerify(vid: String) {
        counter(vid).apply {
            stillHumanVerify.incrementAndGet()
            lastOutcome = "STILL_HUMAN_VERIFY"
            lastAt = System.currentTimeMillis()
        }
    }

    fun snapshot(): Map<String, Any?> = counters.entries
        .sortedBy { it.key }
        .associate { (vid, counter) ->
            vid to mapOf(
                "attempted" to counter.attempted.get(),
                "succeeded" to counter.succeeded.get(),
                "failed" to counter.failed.get(),
                "still_human_verify" to counter.stillHumanVerify.get(),
                "last_outcome" to counter.lastOutcome,
                "last_at" to counter.lastAt.takeIf { it > 0L },
            )
        }

    internal fun resetForTest() {
        counters.clear()
    }

    private fun counter(vid: String): Counter = counters.computeIfAbsent(vid) { Counter() }
}
