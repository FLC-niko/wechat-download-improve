package com.wamp.download.weread.eink

import com.wamp.download.config.AppConfig
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import kotlin.random.Random

/**
 * Process-wide request gate for the E-Ink account.
 *
 * Every task consumer and background sync shares this gate so a single account cannot create
 * concurrent bursts or tight pagination loops against i.weread.qq.com.
 */
internal object WeReadEinkRequestGate {
    private val semaphores = ConcurrentHashMap<String, Semaphore>()
    private val lastRequestAt = ConcurrentHashMap<String, Long>()

    fun <T> execute(vid: String, block: () -> T): T {
        val semaphore = semaphores.computeIfAbsent(vid) {
            Semaphore(AppConfig.wereadEinkMaxConcurrentPerAccount)
        }
        semaphore.acquire()
        try {
            throttle(vid)
            return block()
        } finally {
            semaphore.release()
        }
    }

    private fun throttle(vid: String) {
        val minIntervalMs = AppConfig.wereadEinkMinRequestInterval * 1000L
        while (true) {
            val now = System.currentTimeMillis()
            val previous = lastRequestAt[vid] ?: 0L
            val waitMs = previous + minIntervalMs - now
            if (waitMs <= 0L) {
                if (lastRequestAt.replace(vid, previous, now) || lastRequestAt.putIfAbsent(vid, now) == null) {
                    return
                }
            } else {
                Thread.sleep(waitMs + Random.nextLong(0L, 250L))
            }
        }
    }
}
