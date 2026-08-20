package com.wamp.download.rabbitmq

import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

enum class VhostConnectionState {
    DISCONNECTED,
    CONNECTING,
    ACTIVE
}

/** 独立托管各 vhost；一个连接失败不会阻塞其他连接。 */
class VhostConnectionSupervisor(
    private val bindings: List<VhostBinding>,
    private val initialRetryIntervalMs: Long = 5_000L,
    private val maxRetryIntervalMs: Long = 60_000L
) {
    companion object {
        private val logger = LoggerFactory.getLogger(VhostConnectionSupervisor::class.java)
    }

    data class VhostBinding(
        val name: String,
        val virtualHost: String,
        val manager: RabbitMqClientManager,
        val onConnected: suspend () -> Unit = {},
        val onDisconnected: () -> Unit = {}
    )

    private val states = ConcurrentHashMap<String, VhostConnectionState>()
    private val initialAttempts = ConcurrentHashMap.newKeySet<String>()
    private val jobs = mutableListOf<Job>()
    private val started = AtomicBoolean(false)
    private val summaryLock = Any()
    private var lastAvailable: Set<String>? = null

    init {
        bindings.forEach { states[it.name] = VhostConnectionState.DISCONNECTED }
    }

    fun start(scope: CoroutineScope) {
        if (!started.compareAndSet(false, true)) return
        bindings.forEach { binding ->
            jobs += scope.launch { supervise(binding) }
        }
    }

    fun snapshot(): Map<String, VhostConnectionState> = states.toMap()

    fun stop() {
        if (!started.compareAndSet(true, false)) return
        jobs.forEach { it.cancel() }
        jobs.clear()
    }

    private suspend fun supervise(binding: VhostBinding) {
        var activated = false
        var retryDelayMs = initialRetryIntervalMs
        while (kotlin.coroutines.coroutineContext.isActive && started.get()) {
            if (!activated) {
                states[binding.name] = VhostConnectionState.CONNECTING
                try {
                    binding.manager.connect(virtualHost = binding.virtualHost)
                    binding.onConnected()
                    activated = true
                    retryDelayMs = initialRetryIntervalMs
                    states[binding.name] = VhostConnectionState.ACTIVE
                    logger.info("【vhost:${binding.name}】连接及消费者启动成功")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    states[binding.name] = VhostConnectionState.DISCONNECTED
                    logger.error("【vhost:${binding.name}】连接失败: ${e.message}; ${retryDelayMs / 1000} 秒后重试")
                } finally {
                    initialAttempts += binding.name
                    logAvailabilityIfChanged()
                }
            } else {
                val connected = binding.manager.isConnected()
                val newState = if (connected) {
                    VhostConnectionState.ACTIVE
                } else {
                    VhostConnectionState.DISCONNECTED
                }
                val previous = states.put(binding.name, newState)
                if (previous != newState) {
                    if (newState == VhostConnectionState.ACTIVE) {
                        logger.info("【vhost:${binding.name}】连接已恢复，继续消费")
                    } else {
                        logger.warn("【vhost:${binding.name}】当前不可用，开始按退避策略重连")
                        binding.onDisconnected()
                    }
                    logAvailabilityIfChanged()
                }
                if (!connected) {
                    activated = false
                    retryDelayMs = initialRetryIntervalMs
                }
            }
            delay(retryDelayMs)
            if (!activated) {
                retryDelayMs = (retryDelayMs * 2).coerceAtMost(maxRetryIntervalMs)
            }
        }
    }

    private fun logAvailabilityIfChanged() {
        if (initialAttempts.size < bindings.size) return
        synchronized(summaryLock) {
            val available = states.filterValues { it == VhostConnectionState.ACTIVE }.keys.toSortedSet()
            if (available == lastAvailable) return
            lastAvailable = available
            val unavailable = states.keys.minus(available).toSortedSet()
            if (available.isEmpty()) {
                logger.warn("【RabbitMQ】所有 vhost 均不可用，程序进入连接等待态；将按退避策略重连")
            } else {
                logger.info("【RabbitMQ】可用 vhost=$available，不可用 vhost=$unavailable；可用频道继续工作")
            }
        }
    }
}
