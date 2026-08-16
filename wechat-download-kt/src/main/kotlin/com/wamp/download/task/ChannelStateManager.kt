package com.wamp.download.task

import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicLong

/**
 * 通道状态机 — 主通道（appmsgpublish）频控状态跟踪
 *
 * 主通道触发频控（ret=200013）后标记降级一段时间，期间直接走微信读书回退，
 * 避免在降级期内反复打主通道浪费请求、加剧风控。
 */
object ChannelStateManager {
    private val logger = LoggerFactory.getLogger(ChannelStateManager::class.java)

    /** 主通道降级到期时间戳（毫秒），0 = 未降级 */
    @Volatile
    private var mainChannelBlockedUntil: Long = 0L

    /** 频控降级累计次数（用于日志/监控） */
    private val freqControlCount = AtomicLong(0)

    /**
     * 标记主通道频控降级
     *
     * @param durationMs 降级持续时间（毫秒），默认 60s
     */
    fun markMainChannelFreqControl(durationMs: Long = 60_000L) {
        mainChannelBlockedUntil = System.currentTimeMillis() + durationMs
        val count = freqControlCount.incrementAndGet()
        logger.warn("【通道状态机】主通道触发频控，标记降级 ${durationMs / 1000}s (累计 $count 次)")
    }

    /** 主通道当前是否可用（未处于降级期） */
    fun isMainChannelAvailable(): Boolean = System.currentTimeMillis() > mainChannelBlockedUntil

    /** 剩余降级秒数（>0 表示仍在降级期） */
    fun remainingDegradedSeconds(): Long =
        ((mainChannelBlockedUntil - System.currentTimeMillis()) / 1000).coerceAtLeast(0L)

    /** 状态快照（供 /api/weread/status 等接口监控） */
    fun snapshot(): Map<String, Any> = mapOf(
        "main_available" to isMainChannelAvailable(),
        "remaining_seconds" to remainingDegradedSeconds(),
        "freq_control_count" to freqControlCount.get(),
    )
}
