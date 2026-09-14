package com.wamp.download.rabbitmq

import com.google.gson.Gson
import com.rabbitmq.client.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * RabbitMQ 客户端管理器 — 参照 Worker 的 RabbitMqClientManager 实现
 *
 * 功能：
 * - 支持 new-media-backend direct exchange + BTA/ATD/状态队列拓扑
 * - JSON 发布、任务消费（手动 ack）、去重与重试
 * - 网络断开自动恢复，channel/connection 线程安全
 */
class RabbitMqClientManager(
    private val gson: Gson = Gson(),
    private val enableGzhTopology: Boolean = true,
) {
    companion object {
        private val logger = LoggerFactory.getLogger(RabbitMqClientManager::class.java)
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var connection: Connection? = null
    private var channel: Channel? = null

    /** 可选的死信/兜底消息载荷增强回调（例如业务层置换文章二维码） */
    var deadLetterPayloadEnricher: ((ByteArray) -> ByteArray)? = null

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
        val virtualHost: String,
    )

    private var connectionSettings: ConnectionSettings? = null

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
                if (enableGzhTopology) {
                    declareTopology(ch)
                }
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
        ch.confirmSelect()
        ch.exchangeDeclare(EXCHANGE_NEW_MEDIA, BuiltinExchangeType.DIRECT, true)
        ch.queueDeclare(GZH_TASK_QUEUE, true, false, false, null)
        ch.queueBind(GZH_TASK_QUEUE, EXCHANGE_NEW_MEDIA, GZH_TASK_ROUTING)
        ch.queueDeclare(GZH_RESULT_QUEUE, true, false, false, null)
        ch.queueBind(GZH_RESULT_QUEUE, EXCHANGE_NEW_MEDIA, GZH_RESULT_ROUTING)
        ch.queueDeclare(GZH_DEAD_QUEUE, true, false, false, null)
        ch.queueBind(GZH_DEAD_QUEUE, EXCHANGE_NEW_MEDIA, GZH_DEAD_ROUTING)
        ch.queueDeclare(SERVER_STATUS_QUEUE, true, false, false, null)
    }

    fun isConnected(): Boolean = connection?.isOpen == true && channel?.isOpen == true

    suspend fun publishJson(queue: String, payload: Any) {
        val bytes = gson.toJson(payload).toByteArray(Charsets.UTF_8)
        publish(queue, bytes)
    }

    suspend fun publishJson(
        routingKey: String,
        payload: Any,
        eventId: String,
        workflowId: String,
        producerService: String,
        headers: Map<String, Any?> = emptyMap(),
    ) {
        val bytes = gson.toJson(payload).toByteArray(Charsets.UTF_8)
        publish(
            routingKey = routingKey,
            body = bytes,
            eventId = eventId,
            workflowId = workflowId,
            producerService = producerService,
            headers = headers,
        )
    }

    suspend fun publish(queue: String, body: ByteArray) {
        publish(
            routingKey = queueRouting(queue),
            body = body,
            eventId = null,
            workflowId = null,
            producerService = null,
        )
    }

    suspend fun publish(
        routingKey: String,
        body: ByteArray,
        eventId: String?,
        workflowId: String?,
        producerService: String?,
        headers: Map<String, Any?> = emptyMap(),
    ) {
        withContext(Dispatchers.IO) {
            val maxRetries = 3
            var lastException: IOException? = null

            repeat(maxRetries) { retry ->
                try {
                    val ch = getValidChannel()
                    channelOperationMutex.withLock {
                        ch.queueDeclare(queueForRoutingKey(routingKey), true, false, false, null)
                        publishAndConfirm(
                            ch = ch,
                            exchange = EXCHANGE_NEW_MEDIA,
                            routingKey = routingKey,
                            properties = basicProps(eventId, workflowId, producerService, headers),
                            body = body,
                        )
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

    private fun publishAndConfirm(
        ch: Channel,
        exchange: String,
        routingKey: String,
        properties: AMQP.BasicProperties,
        body: ByteArray,
    ) {
        val returned = AtomicReference<String?>(null)
        val returnListener = ReturnListener { replyCode, replyText, _, returnedRoutingKey, _, _ ->
            returned.set("replyCode=$replyCode, text=$replyText, routingKey=$returnedRoutingKey")
        }
        ch.addReturnListener(returnListener)
        try {
            ch.basicPublish(exchange, routingKey, true, properties, body)
            ch.waitForConfirmsOrDie(5_000)
            returned.get()?.let { throw IOException("RabbitMQ returned message: $it") }
        } finally {
            ch.removeReturnListener(returnListener)
        }
    }

    private fun queueRouting(queue: String): String = when (queue) {
        GZH_TASK_QUEUE -> GZH_TASK_ROUTING
        GZH_RESULT_QUEUE -> GZH_RESULT_ROUTING
        GZH_DEAD_QUEUE -> GZH_DEAD_ROUTING
        else -> queue
    }

    private fun queueForRoutingKey(routingKey: String): String = when (routingKey) {
        GZH_TASK_ROUTING -> GZH_TASK_QUEUE
        GZH_RESULT_ROUTING -> GZH_RESULT_QUEUE
        GZH_DEAD_ROUTING -> GZH_DEAD_QUEUE
        else -> routingKey
    }

    private fun basicProps(
        eventId: String? = null,
        workflowId: String? = null,
        producerService: String? = null,
        headers: Map<String, Any?> = emptyMap(),
    ): AMQP.BasicProperties = AMQP.BasicProperties.Builder()
        .contentType("application/json")
        .contentEncoding("utf-8")
        .type(RABBIT_SCHEMA_VERSION)
        .messageId(eventId)
        .correlationId(workflowId)
        .headers(buildMap {
            put("x-schema-version", RABBIT_SCHEMA_VERSION)
            if (producerService != null) put("x-producer-service", producerService)
            putAll(headers)
        })
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
                } catch (e: RabbitTaskFormatException) {
                    logger.warn("【RabbitMQ】消息格式非法，转入死信队列: ${e.message}")
                    if (!autoAck) {
                        runCatching { moveToDeadLetter(ch, ctx, e) }
                            .onFailure { logger.error("【RabbitMQ】格式非法消息转死信失败", it) }
                    }
                } catch (e: Exception) {
                    logger.error("【RabbitMQ】消费处理异常", e)
                    if (!autoAck) {
                        if (e is com.wamp.download.task.AaosFallbackRequiredException) {
                            runCatching { moveToDeadLetter(ch, ctx, e) }
                                .onFailure { logger.error("【RabbitMQ】降级兜底转死信失败", it) }
                        } else {
                            retryOrDeadLetter(ch, ctx, e)
                        }
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
     * 若配置了 deadLetterPayloadEnricher，将消息体交由增强器处理（例如业务层置换为文章二维码）
     */
    internal fun enrichBodyWithArticleQr(body: ByteArray): ByteArray {
        return deadLetterPayloadEnricher?.invoke(body) ?: body
    }

    private suspend fun moveToDeadLetter(ch: Channel, ctx: DeliveryContext, error: Exception) {
        channelOperationMutex.withLock {
            if (!ch.isOpen) {
                logger.warn("【RabbitMQ】转死信时 channel 已关闭；消息将由 broker 重新投递")
                return
            }
            val headers = ctx.headers.toMutableMap().apply {
                put("x-last-error", error.javaClass.simpleName)
                put("x-original-queue", ctx.queue)
                error.message?.let { put("x-error-message", it) }
            }

            val vhost = connectionSettings?.virtualHost ?: ""
            val isXdag = vhost == RABBITMQ_XDAG_VIRTUAL_HOST
            val exchange = if (isXdag) "archive.new-media.v2" else EXCHANGE_NEW_MEDIA
            val routingKey = if (isXdag) "archive.gzh.auto.atd.v2.dlq" else GZH_DEAD_ROUTING

            val enrichedBody = enrichBodyWithArticleQr(ctx.body)
            publishAndConfirm(
                ch = ch,
                exchange = exchange,
                routingKey = routingKey,
                properties = ctx.delivery.properties.builder().headers(headers).build(),
                body = enrichedBody,
            )
            ch.basicAck(ctx.delivery.envelope.deliveryTag, false)
        }
    }

    /** 将失败消息重新发布并增加重试次数；超过上限后可靠转入死信队列。 */
    private suspend fun retryOrDeadLetter(ch: Channel, ctx: DeliveryContext, error: Exception) {
        try {
            if (shouldRequeue(ctx) && TASK_RETRY_DELAY_MS > 0) {
                delay(TASK_RETRY_DELAY_MS)
            }
            channelOperationMutex.withLock {
                if (!ch.isOpen) {
                    logger.warn("【RabbitMQ】失败消息所在 channel 已关闭；消息将由 broker 重新投递")
                    return
                }

                val headers = ctx.headers.toMutableMap()
                val nextRetry = ctx.retryCount + 1
                headers["x-last-error"] = error.javaClass.simpleName

                if (shouldRequeue(ctx)) {
                    headers["x-retry-count"] = nextRetry
                    val retryProperties = ctx.delivery.properties.builder().headers(headers).build()
                    publishAndConfirm(
                        ch = ch,
                        exchange = EXCHANGE_NEW_MEDIA,
                        routingKey = queueRouting(ctx.queue),
                        properties = retryProperties,
                        body = ctx.body,
                    )
                    ch.basicAck(ctx.delivery.envelope.deliveryTag, false)
                    logger.warn("【RabbitMQ】任务处理失败，已安排第 $nextRetry 次重试")
                } else {
                    headers["x-original-queue"] = ctx.queue
                    val deadProperties = ctx.delivery.properties.builder().headers(headers).build()
                    val enrichedBody = enrichBodyWithArticleQr(ctx.body)
                    publishAndConfirm(
                        ch = ch,
                        exchange = EXCHANGE_NEW_MEDIA,
                        routingKey = GZH_DEAD_ROUTING,
                        properties = deadProperties,
                        body = enrichedBody,
                    )
                    ch.basicAck(ctx.delivery.envelope.deliveryTag, false)
                    logger.error("【RabbitMQ】任务重试次数耗尽，已转入 $GZH_DEAD_QUEUE", error)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error("【RabbitMQ】失败消息替代发布失败；重新入队原消息", e)
            if (ch.isOpen) {
                runCatching {
                    channelOperationMutex.withLock {
                        if (ch.isOpen) {
                            ch.basicNack(ctx.delivery.envelope.deliveryTag, false, true)
                        }
                    }
                }.onFailure {
                    logger.warn("【RabbitMQ】原消息重新入队失败；channel 关闭后由 broker 处理", it)
                }
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
