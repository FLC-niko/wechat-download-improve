package com.wamp.download.rabbitmq

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import com.wamp.download.config.AppConfig
import com.wamp.download.store.InboxStore

/**
 * RabbitMQ 服务 — 组织四个 vhost 的连接与消费
 *
 * 参照 Worker 的多 vhost 架构，为 pro / test / xdag / thdag 各建立一个
 * 独立的 RabbitMqClientManager，并由 VhostConnectionSupervisor 统一托管，
 * 一个连接失败不会阻塞其他连接。当前 xdag 尚未配置公众号 BTA/ATD 队列，
 * 只监督连接，不声明或消费公众号拓扑。
 *
 * 通过 onTaskReceived 回调，将业务处理逻辑注入（由上层定义，例如调用
 * ArticleFetcher 拉取文章后把链接发布到结果队列）。
 */
class RabbitMqService(
    private var onTaskReceived: suspend (RabbitTaskMessage, ResultQueuePublisher) -> Unit = { _, _ -> }
) {
    companion object {
        private val logger = LoggerFactory.getLogger(RabbitMqService::class.java)
    }

    val proManager = RabbitMqClientManager()
    val testManager = RabbitMqClientManager()
    val xdagManager = RabbitMqClientManager(enableGzhTopology = false)
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
        onTaskReceived: (suspend (RabbitTaskMessage, ResultQueuePublisher) -> Unit)? = null
    ) {
        if (started) return
        started = true
        if (onTaskReceived != null) {
            this.onTaskReceived = onTaskReceived
        }

        // pro/test/thdag 消费同一个任务队列；xdag 当前仅监督连接。
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
                    name = "xdag",
                    virtualHost = RABBITMQ_XDAG_VIRTUAL_HOST,
                    manager = xdagManager,
                    onConnected = {
                        logger.info("【xdag】连接成功；当前队列清单没有 $GZH_TASK_QUEUE，跳过公众号任务消费")
                    }
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
        logger.info("RabbitMQ 服务已启动（pro/test/xdag/thdag 四个 vhost）")
    }

    private suspend fun startConsumer(manager: RabbitMqClientManager, env: String) {
        manager.startConsumer(GZH_TASK_QUEUE, autoAck = false, prefetch = 1) { _, ctx ->
            var taskMessage: RabbitTaskMessage? = null
            try {
                taskMessage = RabbitTaskDecoder.decode(ctx.body)
                val claim = InboxStore.claim(
                    InboxStore.InboxMessage(
                        idempotencyKey = taskMessage.idempotencyKey,
                        eventId = taskMessage.eventId,
                        workflowId = taskMessage.workflowId,
                    )
                )
                if (!claim.claimed) {
                    logger.info("【$env】跳过重复任务 idempotencyKey=${taskMessage.idempotencyKey}")
                    return@startConsumer
                }
                when (taskMessage) {
                    is RabbitTaskMessage.V2 -> logger.info(
                        "【$env】收到 V2 任务 workflowId=${taskMessage.workflowId}, " +
                            "gzh=${taskMessage.task.payload.account.name}, " +
                            "日期范围=${taskMessage.task.payload.captureWindow}"
                    )
                    is RabbitTaskMessage.Legacy -> logger.info(
                        "【$env】收到旧版任务 correlationId=${taskMessage.workflowId}, " +
                            "gzh=${taskMessage.task.gzhName}, 日期范围=${taskMessage.task.tempTimeStamp}"
                    )
                }
                onTaskReceived(taskMessage, ResultQueuePublisher(manager, claim.resultEventId))
                InboxStore.markCompleted(taskMessage.idempotencyKey)
            } catch (e: Exception) {
                taskMessage?.let { runCatching { InboxStore.markFailed(it.idempotencyKey) } }
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
        xdagManager.close()
        thdagManager.close()
    }
}
