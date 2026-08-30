package com.wamp.download.task

import com.wamp.download.config.AppConfig
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicLong

/**
 * 通道状态机 — 主通道（appmsgpublish）可用性跟踪
 *
 * ret=200013 表示当前主微信通道已经失效，不是可通过降频恢复的临时频控。
 * 一旦出现便在本进程内永久停用，直到写入新凭证后显式重置。
 */
object ChannelStateManager {
    private val logger = LoggerFactory.getLogger(ChannelStateManager::class.java)

    /** 主通道降级到期时间戳（毫秒），0 = 未降级 */
    @Volatile
    private var mainChannelBlockedUntil: Long = 0L

    @Volatile
    private var mainChannelInvalidated: Boolean = false

    @Volatile
    private var mainChannelInvalidReason: String = ""

    /** 频控降级累计次数（用于日志/监控） */
    private val freqControlCount = AtomicLong(0)

    /** 永久失效累计次数（用于日志/监控） */
    private val invalidationCount = AtomicLong(0)

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

    /** 标记主通道永久失效；进程运行期间不会自动恢复。 */
    fun markMainChannelInvalid(reason: String = "ret=200013") {
        mainChannelInvalidated = true
        mainChannelInvalidReason = reason
        val count = invalidationCount.incrementAndGet()
        logger.error("【通道状态机】主通道已永久失效，直接使用回退通道: reason=$reason (累计 $count 次)")
    }

    /** 写入一套新凭证后清除旧凭证对应的失效状态。 */
    fun resetMainChannelAfterCredentialUpdate() {
        mainChannelInvalidated = false
        mainChannelInvalidReason = ""
        mainChannelBlockedUntil = 0L
        logger.info("【通道状态机】主微信凭证已更新，清除旧凭证失效状态")
    }

    /** 主通道当前是否可用。默认配置关闭，200013 失效后也不会自动恢复。 */
    fun isMainChannelAvailable(): Boolean =
        AppConfig.mainEnabled && !mainChannelInvalidated && System.currentTimeMillis() > mainChannelBlockedUntil

    fun isMainChannelInvalidated(): Boolean = mainChannelInvalidated

    fun mainChannelUnavailableReason(): String = when {
        mainChannelInvalidated -> mainChannelInvalidReason.ifBlank { "invalidated" }
        !AppConfig.mainEnabled -> "disabled-by-config"
        System.currentTimeMillis() <= mainChannelBlockedUntil -> "temporarily-rate-limited"
        else -> ""
    }

    /** 剩余降级秒数（>0 表示仍在降级期） */
    fun remainingDegradedSeconds(): Long =
        ((mainChannelBlockedUntil - System.currentTimeMillis()) / 1000).coerceAtLeast(0L)

    /** 状态快照（供 /api/weread/status 等接口监控） */
    fun snapshot(): Map<String, Any> = mapOf(
        "main_enabled" to AppConfig.mainEnabled,
        "main_available" to isMainChannelAvailable(),
        "main_invalidated" to mainChannelInvalidated,
        "main_unavailable_reason" to mainChannelUnavailableReason(),
        "remaining_seconds" to remainingDegradedSeconds(),
        "freq_control_count" to freqControlCount.get(),
        "invalidation_count" to invalidationCount.get(),
    )
}
