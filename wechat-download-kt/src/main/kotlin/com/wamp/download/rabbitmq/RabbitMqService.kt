package com.wamp.download.rabbitmq

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import com.wamp.download.config.AppConfig
import com.wamp.download.store.InboxStore

/**
 * RabbitMQ 服务 — 组织三个 vhost 的连接与消费
 *
 * 参照 Worker 的多 vhost 架构，为 pro / test / thdag 各建立一个
 * 独立的 RabbitMqClientManager，并由 VhostConnectionSupervisor 统一托管，
 * 一个连接失败不会阻塞其他连接。
 *
 * 通过 onTaskReceived 回调，将业务处理逻辑注入（由上层定义，例如调用
 * ArticleFetcher 拉取文章后把链接发布到结果队列）。
 */
class RabbitMqService(
    private var onTaskReceived: suspend (GzhAutoBta, ResultQueuePublisher) -> Unit = { _, _ -> }
) {
    companion object {
        private val logger = LoggerFactory.getLogger(RabbitMqService::class.java)
    }

    val proManager = RabbitMqClientManager()
    val testManager = RabbitMqClientManager()
    val thdagManager = RabbitMqClientManager()

    private var supervisor: VhostConnectionSupervisor? = null
    private var started = false

    /**
     * 启动所有 vhost 连接并开始消费任务队列
     *
     * @param scope 协程作用域
     * @param onTaskReceived 任务处理回调（可选，覆盖构造时传入的）
     */
    fun start(
        scope: CoroutineScope,
        onTaskReceived: (suspend (GzhAutoBta, ResultQueuePublisher) -> Unit)? = null
    ) {
        if (started) return
        started = true
        if (onTaskReceived != null) {
            this.onTaskReceived = onTaskReceived
        }

        // 各 vhost 消费同一个任务队列；业务处理由 onTaskReceived 分发
        supervisor = VhostConnectionSupervisor(
            initialRetryIntervalMs = AppConfig.rabbitmqReconnectInitialDelayMs,
            maxRetryIntervalMs = AppConfig.rabbitmqReconnectMaxDelayMs,
            bindings = listOf(
                VhostConnectionSupervisor.VhostBinding(
                    name = "pro",
                    virtualHost = RABBITMQ_VIRTUAL_HOST,
                    manager = proManager,
                    onConnected = { startConsumer(proManager, "pro") }
                ),
                VhostConnectionSupervisor.VhostBinding(
                    name = "test",
                    virtualHost = RABBITMQ_TEST_VIRTUAL_HOST,
                    manager = testManager,
                    onConnected = { startConsumer(testManager, "test") }
                ),
                VhostConnectionSupervisor.VhostBinding(
                    name = "thdag",
                    virtualHost = RABBITMQ_THDAG_VIRTUAL_HOST,
                    manager = thdagManager,
                    onConnected = { startConsumer(thdagManager, "thdag") }
                )
            )
        )

        scope.launch { supervisor?.start(scope) }
        logger.info("RabbitMQ 服务已启动（pro/test/thdag 三个 vhost）")
    }

    private suspend fun startConsumer(manager: RabbitMqClientManager, env: String) {
        manager.startConsumer(GZH_TASK_QUEUE, autoAck = false, prefetch = 1) { _, ctx ->
            try {
                val task = ctx.decode<GzhAutoBta>()
                val claim = InboxStore.claim(
                    InboxStore.InboxMessage(
                        idempotencyKey = task.idempotencyKey,
                        eventId = task.eventId,
                        workflowId = task.workflowId,
                    )
                )
                if (!claim.claimed) {
                    logger.info("【$env】跳过重复任务 idempotencyKey=${task.idempotencyKey}")
                    return@startConsumer
                }
                logger.info("【$env】收到任务 workflowId=${task.workflowId}, gzh=${task.payload.account.name}, 日期范围=${task.payload.captureWindow}")
                onTaskReceived(task, ResultQueuePublisher(manager, claim.resultEventId))
                InboxStore.markCompleted(task.idempotencyKey)
            } catch (e: Exception) {
                runCatching {
                    val task = ctx.decode<GzhAutoBta>()
                    InboxStore.markFailed(task.idempotencyKey)
                }
                logger.error("【$env】任务处理失败 correlationId=${ctx.correlationId}", e)
                throw e  // 触发 RabbitMQ 层的重试/死信
            }
        }
        logger.info("【$env】已开始监听任务队列: $GZH_TASK_QUEUE")
    }

    fun snapshot(): Map<String, VhostConnectionState> = supervisor?.snapshot() ?: emptyMap()

    fun stop() {
        supervisor?.stop()
        proManager.close()
        testManager.close()
        thdagManager.close()
    }
}
