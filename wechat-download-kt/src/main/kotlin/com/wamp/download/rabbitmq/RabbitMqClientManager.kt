package com.wamp.download.rabbitmq

import com.google.gson.Gson
import com.rabbitmq.client.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * RabbitMQ 客户端管理器 — 参照 Worker 的 RabbitMqClientManager 实现
 *
 * 功能：
 * - 支持 new-media-backend exchange + BTA/ATD/状态队列 拓扑
 * - JSON 发布、任务消费（手动 ack）、去重与重试
 * - 网络断开自动恢复，channel/connection 线程安全
 */
class RabbitMqClientManager(
    private val gson: Gson = Gson()
) {
    companion object {
        private val logger = LoggerFactory.getLogger(RabbitMqClientManager::class.java)
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var connection: Connection? = null
    private var channel: Channel? = null

    private val consumerRegistry = ConcurrentHashMap<String, String>()
    private val closed = AtomicBoolean(false)
    private val connectMutex = Mutex()
    // RabbitMQ Channel 不是线程安全对象。所有 publish / ack / nack 都经此锁串行化。
    private val channelOperationMutex = Mutex()

    data class ConnectionSettings(
        val host: String,
        val port: Int,
        val username: String,
        val password: String,
        val virtualHost: String
    )

    @Volatile
    private var connectionSettings: ConnectionSettings? = null

    val configuredVirtualHost: String?
        get() = connectionSettings?.virtualHost

    suspend fun connect(
        host: String = RABBITMQ_HOST,
        port: Int = RABBITMQ_PORT,
        username: String = RABBITMQ_USERNAME,
        password: String = RABBITMQ_PASSWORD,
        virtualHost: String = RABBITMQ_VIRTUAL_HOST
    ) {
        val settings = ConnectionSettings(host, port, username, password, virtualHost)
        connectMutex.withLock {
            check(!closed.get()) { "RabbitMQ manager 已关闭，不能重新连接" }
            withContext(Dispatchers.IO) {
                if (isConnected() && connectionSettings == settings) return@withContext
                connectionSettings = settings
                closeQuietly()
                val factory = ConnectionFactory().apply {
                    this.host = settings.host
                    this.port = settings.port
                    this.username = settings.username
                    this.password = settings.password
                    this.virtualHost = settings.virtualHost
                    this.isAutomaticRecoveryEnabled = true
                    this.networkRecoveryInterval = 60_000
                }
                val conn = factory.newConnection("wechat-download-kt-${settings.virtualHost}")
                conn.addShutdownListener { cause ->
                    if (!closed.get()) {
                        logger.warn("【RabbitMQ:${settings.virtualHost}】连接中断: ${cause.message}; 将按 60 秒周期恢复")
                    }
                }
                if (conn is Recoverable) {
                    conn.addRecoveryListener(object : RecoveryListener {
                        override fun handleRecoveryStarted(recoverable: Recoverable) {
                            logger.info("【RabbitMQ:${settings.virtualHost}】开始恢复连接")
                        }

                        override fun handleRecovery(recoverable: Recoverable) {
                            logger.info("【RabbitMQ:${settings.virtualHost}】连接及消费者已恢复")
                        }
                    })
                }
                val ch = conn.createChannel()
                connection = conn
                channel = ch
                declareTopology(ch)
                closed.set(false)
                logger.info("【RabbitMQ:${settings.virtualHost}】连接成功")
            }
        }
    }

    suspend fun reconnect() {
        val settings = connectionSettings ?: ConnectionSettings(
            RABBITMQ_HOST, RABBITMQ_PORT, RABBITMQ_USERNAME, RABBITMQ_PASSWORD, RABBITMQ_VIRTUAL_HOST
        )
        closeQuietly()
        delay(1000)
        connect(settings.host, settings.port, settings.username, settings.password, settings.virtualHost)
        logger.info("【RabbitMQ:${settings.virtualHost}】主动重连成功")
    }

    private fun closeQuietly() {
        try {
            channel?.close()
        } catch (e: Exception) {
            logger.warn("【RabbitMQ】关闭 channel 异常: ${e.message}")
        }
        try {
            connection?.close()
        } catch (e: Exception) {
            logger.warn("【RabbitMQ】关闭 connection 异常: ${e.message}")
        }
        channel = null
        connection = null
        consumerRegistry.clear()
    }

    private suspend fun getValidChannel(): Channel {
        val ch = channel
        if (ch?.isOpen == true) return ch
        if (connection != null) {
            repeat(60) {
                delay(1_000)
                channel?.takeIf { it.isOpen }?.let { return it }
            }
        }
        reconnect()
        return channel!!
    }

    private fun declareTopology(ch: Channel) {
        ch.exchangeDeclare(EXCHANGE_NEW_MEDIA, BuiltinExchangeType.DIRECT, true)
        ch.queueDeclare(GZH_TASK_QUEUE, true, false, false, null)
        ch.queueBind(GZH_TASK_QUEUE, EXCHANGE_NEW_MEDIA, GZH_TASK_ROUTING)
        ch.queueDeclare(GZH_RESULT_QUEUE, true, false, false, null)
        ch.queueBind(GZH_RESULT_QUEUE, EXCHANGE_NEW_MEDIA, GZH_RESULT_ROUTING)
        ch.queueDeclare(SERVER_STATUS_QUEUE, true, false, false, null)
        ch.queueDeclare(DEAD_MESSAGE_QUEUE, true, false, false, null)
    }

    fun isConnected(): Boolean = connection?.isOpen == true && channel?.isOpen == true

    suspend fun publishJson(queue: String, payload: Any) {
        val bytes = gson.toJson(payload).toByteArray(Charsets.UTF_8)
        publish(queue, bytes)
    }

    suspend fun publish(queue: String, body: ByteArray) {
        withContext(Dispatchers.IO) {
            val maxRetries = 3
            var lastException: IOException? = null

            repeat(maxRetries) { retry ->
                try {
                    val ch = getValidChannel()
                    channelOperationMutex.withLock {
                        ch.queueDeclare(queue, true, false, false, null)
                        ch.basicPublish(EXCHANGE_NEW_MEDIA, queueRouting(queue), basicProps(), body)
                    }
                    return@withContext
                } catch (e: IOException) {
                    lastException = e
                    if (retry < maxRetries - 1) {
                        logger.warn("【RabbitMQ】发布失败，重试中... (${e.message})")
                        runCatching { delay(1000L * (retry + 1)) }
                    } else {
                        logger.error("【RabbitMQ】发布失败，放弃", e)
                    }
                }
            }
            throw lastException ?: IOException("Unknown error")
        }
    }

    private fun queueRouting(queue: String): String = when (queue) {
        GZH_TASK_QUEUE -> GZH_TASK_ROUTING
        GZH_RESULT_QUEUE -> GZH_RESULT_ROUTING
        else -> queue
    }

    private fun basicProps(): AMQP.BasicProperties = AMQP.BasicProperties.Builder()
        .contentType("application/json")
        .deliveryMode(2) // persistent
        .build()

    suspend fun startConsumer(
        queue: String,
        autoAck: Boolean = false,
        prefetch: Int = 1,
        onMessage: suspend (Channel, DeliveryContext) -> Unit
    ) {
        if (consumerRegistry.containsKey(queue)) return
        val ch = getValidChannel()
        ch.basicQos(prefetch)
        ch.queueDeclare(queue, true, false, false, null)

        val deliverCallback = DeliverCallback { consumerTag, delivery ->
            scope.launch {
                val ctx = DeliveryContext(consumerTag, queue, delivery)
                try {
                    logger.debug("【RabbitMQ】收到消息 (Queue: $queue, Tag: ${delivery.envelope.deliveryTag}, Size: ${delivery.body.size} bytes)")
                    onMessage(ch, ctx)
                    if (!autoAck) acknowledge(ch, ctx)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.error("【RabbitMQ】消费处理异常", e)
                    if (!autoAck) {
                        retryOrDeadLetter(ch, ctx, e)
                    }
                }
            }
        }
        val cancelCallback = CancelCallback { _ -> }
        val tag = ch.basicConsume(queue, autoAck, deliverCallback, cancelCallback)
        consumerRegistry[queue] = tag
    }

    private fun shouldRequeue(ctx: DeliveryContext): Boolean {
        val retry = ctx.retryCount
        return retry < MAX_TASK_RETRY
    }

    private suspend fun acknowledge(ch: Channel, ctx: DeliveryContext) {
        channelOperationMutex.withLock {
            check(ch.isOpen) { "RabbitMQ channel 已关闭，无法确认消息" }
            ch.basicAck(ctx.delivery.envelope.deliveryTag, false)
        }
    }

    /**
     * 将失败消息重新发布并增加重试次数；超过上限后投递到死信队列。
     * 原消息仅在替代消息发布成功后才确认，从而避免异常路径丢消息。
     */
    private suspend fun retryOrDeadLetter(ch: Channel, ctx: DeliveryContext, error: Exception) {
        channelOperationMutex.withLock {
            if (!ch.isOpen) {
                logger.warn("【RabbitMQ】失败消息所在 channel 已关闭；消息将由 broker 重新投递")
                return
            }

            val headers = ctx.headers.toMutableMap()
            val nextRetry = ctx.retryCount + 1
            headers["x-last-error"] = (error.message ?: error.javaClass.simpleName).take(512)

            if (shouldRequeue(ctx)) {
                headers["x-retry-count"] = nextRetry
                val retryProperties = ctx.delivery.properties.builder().headers(headers).build()
                ch.basicPublish(EXCHANGE_NEW_MEDIA, queueRouting(ctx.queue), retryProperties, ctx.body)
                ch.basicAck(ctx.delivery.envelope.deliveryTag, false)
                logger.warn("【RabbitMQ】任务处理失败，已安排第 $nextRetry 次重试")
            } else {
                headers["x-final-retry-count"] = ctx.retryCount
                val deadProperties = ctx.delivery.properties.builder().headers(headers).build()
                ch.basicPublish("", DEAD_MESSAGE_QUEUE, deadProperties, ctx.body)
                ch.basicAck(ctx.delivery.envelope.deliveryTag, false)
                logger.error("【RabbitMQ】任务重试次数耗尽，已隔离到 $DEAD_MESSAGE_QUEUE", error)
            }
        }
    }

    fun stopConsumer(queue: String) {
        val ch = channel ?: return
        val tag = consumerRegistry.remove(queue) ?: return
        scope.launch { ch.basicCancel(tag) }
    }

    fun close() {
        if (closed.compareAndSet(false, true)) {
            scope.cancel()
            try {
                channel?.close()
            } catch (e: Exception) {
                logger.warn("【RabbitMQ】关闭 channel 异常: ${e.message}")
            }
            try {
                connection?.close()
            } catch (e: Exception) {
                logger.warn("【RabbitMQ】关闭 connection 异常: ${e.message}")
            }
        }
    }

    data class DeliveryContext(
        val consumerTag: String,
        val queue: String,
        val delivery: Delivery
    ) {
        val body: ByteArray get() = delivery.body
        val headers: Map<String, Any?> = delivery.properties.headers ?: emptyMap()
        val correlationId: String? = delivery.properties.correlationId
        val retryCount: Int = headers["x-retry-count"]?.toString()?.toIntOrNull() ?: 0
        fun parseBodyAsString(): String = body.toString(Charsets.UTF_8)
        inline fun <reified T> decode(gson: Gson = Gson()): T = gson.fromJson(parseBodyAsString(), T::class.java)
    }
}
