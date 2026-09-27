package com.wamp.download.weread.eink

import com.wamp.download.config.AppConfig
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.random.Random

/**
 * Process-wide request gate for the E-Ink account.
 *
 * Every task consumer and background sync shares this gate so a single account cannot create
 * concurrent bursts or tight pagination loops against i.weread.qq.com.
 *
 * Human-verification priority:
 * - [beginVerification] opens a short window while the captcha-bearing probe runs. Background
 *   requests never fail during this window; they wait (outside the semaphore) until the window
 *   closes, then continue normally, so no task or sync cursor is ever dropped.
 * - The probe request itself is marked with [asVerificationProbe], skips throttling entirely and
 *   takes the semaphore as soon as the in-flight request finishes. Long anti-risk-control sleeps
 *   are interruptible so they yield to the verification window instead of delaying the ticket.
 */
internal object WeReadEinkRequestGate {
    private val semaphores = ConcurrentHashMap<String, Semaphore>()
    private val lastRequestAt = ConcurrentHashMap<String, Long>()
    private val requestCounts = ConcurrentHashMap<String, Int>()
    private val verificationActive = ConcurrentHashMap.newKeySet<String>()
    private val waiters = ConcurrentHashMap<String, Waiter>()

    private class Waiter {
        val lock = ReentrantLock()
        val condition = lock.newCondition()
    }

    private val verificationProbeThread = ThreadLocal.withInitial { false }

    /** Upper bound for background yielding; a stuck window must never stall callers forever. */
    private const val VERIFICATION_YIELD_MAX_MS = 120_000L
    private const val VERIFICATION_WAIT_SLICE_MS = 500L
    private const val MAX_RESERVE_ATTEMPTS = 10

    /** Marks the current thread as the captcha recovery probe; nested safely. */
    fun <T> asVerificationProbe(block: () -> T): T {
        val previous = verificationProbeThread.get()
        verificationProbeThread.set(true)
        return try {
            block()
        } finally {
            verificationProbeThread.set(previous)
        }
    }

    fun isVerificationProbeRequest(): Boolean = verificationProbeThread.get()

    fun beginVerification(vid: String) {
        verificationActive.add(vid)
        wakeWaiters(vid)
    }

    fun endVerification(vid: String) {
        verificationActive.remove(vid)
        wakeWaiters(vid)
    }

    fun isVerificationActive(vid: String): Boolean = vid in verificationActive

    fun <T> execute(vid: String, block: () -> T): T {
        val semaphore = semaphore(vid)
        if (isVerificationProbeRequest()) {
            semaphore.acquire()
            return try {
                block()
            } finally {
                semaphore.release()
            }
        }

        awaitVerificationClear(vid)
        semaphore.acquire()
        var holdsLock = true
        try {
            var attempts = 0
            while (attempts < MAX_RESERVE_ATTEMPTS) {
                val waitMs = reserveRequestSlot(vid)
                if (waitMs <= 0L) break
                // Wait outside the semaphore so a verification probe can take the lock immediately.
                semaphore.release()
                holdsLock = false
                waitWakeable(vid, waitMs)
                awaitVerificationClear(vid)
                semaphore.acquire()
                holdsLock = true
                attempts++
            }
            return block()
        } finally {
            if (holdsLock) semaphore.release()
        }
    }

    /**
     * Reserves one request slot and returns how long the caller should wait before sending it.
     * A long anti-risk-control jitter is returned as a wakeable wait instead of a hard sleep.
     */
    private fun reserveRequestSlot(vid: String): Long {
        val configuredMinIntervalMs = AppConfig.wereadEinkMinRequestInterval * 1000L
        val minIntervalMs = maxOf(configuredMinIntervalMs, 2000L) // Force at least 2 seconds for E-Ink requests

        while (true) {
            val now = System.currentTimeMillis()
            val previous = lastRequestAt[vid] ?: 0L
            val waitMs = previous + minIntervalMs - now
            if (waitMs > 0L) {
                return waitMs + Random.nextLong(0L, 250L)
            }
            if (lastRequestAt.replace(vid, previous, now) || lastRequestAt.putIfAbsent(vid, now) == null) {
                val count = requestCounts.compute(vid) { _, current -> (current ?: 0) + 1 } ?: 1
                if (count >= Random.nextInt(10, 20)) {
                    requestCounts[vid] = 0
                    val jitterMs = Random.nextLong(30_000L, 120_000L)
                    lastRequestAt[vid] = now + jitterMs
                    return jitterMs
                }
                return 0L
            }
        }
    }

    private fun awaitVerificationClear(vid: String) {
        val deadline = System.currentTimeMillis() + VERIFICATION_YIELD_MAX_MS
        while (isVerificationActive(vid)) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0L) return
            waitWakeable(vid, minOf(remaining, VERIFICATION_WAIT_SLICE_MS))
        }
    }

    private fun waitWakeable(vid: String, waitMs: Long) {
        if (waitMs <= 0L) return
        val waiter = waiters.computeIfAbsent(vid) { Waiter() }
        waiter.lock.lock()
        try {
            waiter.condition.await(waitMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            waiter.lock.unlock()
        }
    }

    private fun wakeWaiters(vid: String) {
        val waiter = waiters[vid] ?: return
        waiter.lock.lock()
        try {
            waiter.condition.signalAll()
        } finally {
            waiter.lock.unlock()
        }
    }

    private fun semaphore(vid: String): Semaphore = semaphores.computeIfAbsent(vid) {
        Semaphore(AppConfig.wereadEinkMaxConcurrentPerAccount)
    }
}
