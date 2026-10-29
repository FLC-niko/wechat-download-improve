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

    /**
     * 请求节奏下限（毫秒）。
     *
     * 2026-09-29 风控复盘：原 2 秒/请求 + 每 10~19 次一次 30~120 秒长停顿仍会触发 -2041。
     * 调整为 4 秒/请求、每 6~11 次一次 45~180 秒长停顿，并给每次请求附加最多 1.2 秒抖动，
     * 让请求间隔更接近人工操作，降低被判定为自动化行为的概率。
     */
    private const val MIN_REQUEST_INTERVAL_FLOOR_MS = 4_000L

    /** 人工验证恢复后的温和请求窗口：节奏减半、长停顿更频繁，避免刚恢复就被再次击中。 */
    private const val HUMAN_VERIFY_WARMUP_MS = 30 * 60 * 1000L
    private const val WARMUP_INTERVAL_MULTIPLIER = 2L

    /** 人工验证恢复后的温和窗口截止时间（vid → epoch ms）。 */
    private val warmupUntil = ConcurrentHashMap<String, Long>()

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

    /** 账号完成人工验证恢复健康后调用：进入温和请求窗口。 */
    fun markHumanVerifyRecovered(vid: String, warmupMs: Long = HUMAN_VERIFY_WARMUP_MS) {
        warmupUntil[vid] = System.currentTimeMillis() + warmupMs
    }

    // A restart must not immediately resume cache filling after a recent recovery.
    private val backgroundStartupUntil = System.currentTimeMillis() + HUMAN_VERIFY_WARMUP_MS

    internal fun canRunBackgroundRequests(vid: String, now: Long = System.currentTimeMillis()): Boolean =
        now >= backgroundStartupUntil && !isWarmingUp(vid, now) && !isVerificationActive(vid)

    private fun isWarmingUp(vid: String, now: Long = System.currentTimeMillis()): Boolean =
        (warmupUntil[vid] ?: 0L) > now

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
        val warmingUp = isWarmingUp(vid)
        val baseIntervalMs = maxOf(configuredMinIntervalMs, MIN_REQUEST_INTERVAL_FLOOR_MS)
        val minIntervalMs = if (warmingUp) baseIntervalMs * WARMUP_INTERVAL_MULTIPLIER else baseIntervalMs

        while (true) {
            val now = System.currentTimeMillis()
            val previous = lastRequestAt[vid] ?: 0L
            val waitMs = previous + minIntervalMs - now
            if (waitMs > 0L) {
                return waitMs + Random.nextLong(0L, 1_200L)
            }
            if (lastRequestAt.replace(vid, previous, now) || lastRequestAt.putIfAbsent(vid, now) == null) {
                val count = requestCounts.compute(vid) { _, current -> (current ?: 0) + 1 } ?: 1
                val jitterTrigger = if (warmingUp) Random.nextInt(3, 6) else Random.nextInt(6, 12)
                if (count >= jitterTrigger) {
                    requestCounts[vid] = 0
                    val jitterMs = if (warmingUp) {
                        Random.nextLong(30_000L, 90_000L)
                    } else {
                        Random.nextLong(45_000L, 180_000L)
                    }
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
