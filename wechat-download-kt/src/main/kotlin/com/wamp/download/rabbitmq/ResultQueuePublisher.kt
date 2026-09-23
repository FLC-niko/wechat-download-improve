package com.wamp.download.rabbitmq

import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import org.slf4j.LoggerFactory

/**
 * 自动公众号 ATD 发布器。
 *
 * 一个 BTA 任务对应一条聚合 ATD，并在同一个租户 vhost 内发布。
 */
class ResultQueuePublisher(
    private val clientManager: RabbitMqClientManager,
    private val defaultResultEventId: String? = null,
) {
    companion object {
        private val logger = LoggerFactory.getLogger(ResultQueuePublisher::class.java)
    }

    @Deprecated("TaskProcessor 将迁移到直接传入 V2 BTA")
    suspend fun publishArticles(
        gzhName: String,
        jobId: Long,
        correlationId: String,
        tempTimeStamp: List<String>,
        urls: List<String>,
    ) {
        publish(
            input = legacyInput(gzhName, jobId, correlationId, tempTimeStamp),
            status = if (urls.isEmpty()) "EMPTY" else "SUCCEEDED",
            seedArticles = urls.distinct().map { SeedArticle(url = it) },
        )
    }

    @Deprecated("TaskProcessor 将迁移到直接传入 V2 BTA")
    suspend fun publishEmptyResult(
        gzhName: String,
        jobId: Long,
        correlationId: String,
        tempTimeStamp: List<String>,
    ) {
        publish(
            input = legacyInput(gzhName, jobId, correlationId, tempTimeStamp),
            status = "EMPTY",
        )
    }

    @Deprecated("TaskProcessor 将迁移到直接传入 V2 BTA")
    suspend fun publishFailedResult(
        gzhName: String,
        jobId: Long,
        correlationId: String,
        tempTimeStamp: List<String>,
        error: AtdError,
    ) {
        publish(
            input = legacyInput(gzhName, jobId, correlationId, tempTimeStamp),
            status = "FAILED",
            error = error,
        )
    }

    suspend fun publish(
        input: GzhAutoBta,
        status: String,
        seedArticles: List<SeedArticle> = emptyList(),
        error: AtdError? = null,
        eventId: String = defaultResultEventId ?: "evt_${UUID.randomUUID()}"
    ) {
        val message = buildMessage(input, status, seedArticles, error, eventId)
        // xdag 租户的结果经 archive.new-media.v2 路由到 archive.gzh.auto.atd.v2；
        // 其余 vhost 仍走 new-media-backend / gzh-auto-ATD-queue。
        val routingKey = if (clientManager.isXdagVhost) XDAG_RESULT_ROUTING else GZH_RESULT_ROUTING
        clientManager.publishJson(
            routingKey = routingKey,
            payload = message,
            eventId = message.eventId,
            workflowId = message.workflowId,
            producerService = "android-worker",
        )
        logger.info("ATD 发布成功: tenant=${input.tenantCode}, workflow=${input.workflowId}, status=$status, seedCount=${message.payload.seedArticles.size}")
    }

    internal fun buildMessage(
        input: GzhAutoBta,
        status: String,
        seedArticles: List<SeedArticle> = emptyList(),
        error: AtdError? = null,
        eventId: String = defaultResultEventId ?: "evt_${UUID.randomUUID()}"
    ): GzhAutoAtd {
        require(status in setOf("SUCCEEDED", "EMPTY", "FAILED")) { "不支持的 Android ATD 状态: $status" }
        require(status != "SUCCEEDED" || seedArticles.isNotEmpty()) {
            "SUCCEEDED ATD 必须至少包含一篇种子文章"
        }
        require(status != "EMPTY" || seedArticles.isEmpty()) {
            "EMPTY ATD 不允许包含种子文章"
        }
        require(status != "FAILED" || error != null) {
            "FAILED ATD 必须包含错误信息"
        }

        val deduplicated = seedArticles.distinctBy { it.url }
        val payload = GzhAutoAtdPayload(
            accountName = input.payload.account.name,
            status = status,
            captureWindow = input.payload.captureWindow,
            seedArticles = deduplicated,
            error = error,
        )
        return RabbitV2Envelope(
            schemaVersion = RABBIT_SCHEMA_VERSION,
            eventId = eventId,
            workflowId = input.workflowId,
            causationId = input.eventId,
            idempotencyKey = "${input.workflowId}:ATD",
            messageType = "RESULT",
            tenantCode = input.tenantCode,
            mediaType = input.mediaType,
            operation = input.operation,
            occurredAt = OffsetDateTime.now(ZoneOffset.ofHours(8)).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
            producer = RabbitProducer("android-worker"),
            business = input.business,
            payload = payload,
        )
    }

    private fun legacyInput(
        gzhName: String,
        jobId: Long,
        correlationId: String,
        tempTimeStamp: List<String>,
    ): GzhAutoBta = RabbitV2Envelope(
        schemaVersion = RABBIT_SCHEMA_VERSION,
        eventId = "legacy-$correlationId",
        workflowId = correlationId,
        causationId = null,
        idempotencyKey = "$correlationId:BTA",
        messageType = "COMMAND",
        tenantCode = "unknown",
        mediaType = "WECHAT_OFFICIAL_ACCOUNT",
        operation = "AUTO_CRAWL",
        occurredAt = OffsetDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
        producer = RabbitProducer("legacy-adapter"),
        business = RabbitBusiness(jobId = jobId, jobRunId = correlationId),
        payload = GzhAutoBtaPayload(
            account = GzhAccount(name = gzhName),
            captureWindow = CaptureWindow(
                startsAt = tempTimeStamp.getOrElse(0) { "" },
                endsAt = tempTimeStamp.getOrElse(1) { "" },
            ),
        ),
    )
}
