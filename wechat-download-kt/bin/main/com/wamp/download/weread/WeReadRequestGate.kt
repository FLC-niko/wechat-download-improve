package com.wamp.download.weread

import com.wamp.download.config.AppConfig
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import kotlin.random.Random

/**
 * 进程级微信读书账号请求门。
 *
 * 文章任务、HTTP 路由和凭证刷新必须共享同一套并发与间隔状态，否则每个客户端实例
 * 各自限流仍会在账号维度形成并发突发。
 */
internal object WeReadRequestGate {
    private val semaphores = ConcurrentHashMap<String, Semaphore>()
    private val lastRequestAt = ConcurrentHashMap<String, Long>()

    fun <T> execute(vid: String, block: () -> T): T {
        val semaphore = semaphores.computeIfAbsent(vid) {
            Semaphore(AppConfig.wereadMaxConcurrentPerAccount.coerceAtLeast(1))
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
        val minIntervalMs = AppConfig.wereadMinRequestInterval.coerceAtLeast(0) * 1000
        while (true) {
            val now = System.currentTimeMillis()
            val previous = lastRequestAt[vid] ?: 0L
            val waitMs = previous + minIntervalMs - now
            if (waitMs <= 0) {
                if (lastRequestAt.replace(vid, previous, now) || lastRequestAt.putIfAbsent(vid, now) == null) {
                    return
                }
            } else {
                Thread.sleep(waitMs + Random.nextLong(0, 250))
            }
        }
    }
}
