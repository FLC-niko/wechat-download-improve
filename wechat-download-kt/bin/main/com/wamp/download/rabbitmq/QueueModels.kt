package com.wamp.download.rabbitmq

const val RABBIT_SCHEMA_VERSION = "archive.rabbit.v2"

data class RabbitV2Envelope<T>(
    val schemaVersion: String,
    val eventId: String,
    val workflowId: String,
    val causationId: String?,
    val idempotencyKey: String,
    val messageType: String,
    val tenantCode: String,
    val mediaType: String,
    val operation: String,
    val occurredAt: String,
    val producer: RabbitProducer,
    val business: RabbitBusiness,
    val payload: T,
)

data class RabbitProducer(
    val service: String,
)

data class RabbitBusiness(
    val jobId: Long,
    val jobRunId: String,
)

data class CaptureWindow(
    val startsAt: String,
    val endsAt: String,
)

data class CollectionOptions(
    val includeText: Boolean = false,
    val includeImages: Boolean = false,
    val includeVideos: Boolean = false,
    val includeAudios: Boolean = false,
)

data class QrImageRef(
    val objectKey: String,
    val sha256: String,
    val contentType: String,
    val sizeBytes: Long,
)

data class GzhAccount(
    val name: String,
    val entryUrl: String? = null,
    val qrImage: QrImageRef? = null,
)

data class GzhAutoBtaPayload(
    val account: GzhAccount,
    val captureWindow: CaptureWindow,
    val collectionOptions: CollectionOptions = CollectionOptions(),
)

data class GzhAutoAtdPayload(
    val accountName: String,
    val status: String,
    val captureWindow: CaptureWindow,
    val seedArticles: List<SeedArticle>,
    val error: AtdError? = null,
)

data class SeedArticle(
    val url: String,
    val title: String? = null,
    val publishedAt: String? = null,
)

data class AtdError(
    val code: String,
    val message: String,
    val retryable: Boolean,
)

typealias GzhAutoBta = RabbitV2Envelope<GzhAutoBtaPayload>
typealias GzhAutoAtd = RabbitV2Envelope<GzhAutoAtdPayload>

/**
 * 公众号任务下发结构（BTA: Backend -> Worker）
 * 字段与 Worker 的 GzhAutoFromBackend 保持一致
 */
data class GzhAutoFromBackend(
    val queueName: String,
    val image: String,
    val gzhName: String,
    val tempTimeStamp: List<String>,
    val url: String,
    val jobId: Long,
    val correlationId: String
)

/**
 * 公众号采集结果回传结构（ATD: Worker -> Data）
 */
data class GzhAutoToBigData(
    val queueName: String,
    val gzhName: String,
    val tempTimeStamp: List<String>,
    val url: String?,
    val jobId: Long,
    val correlationId: String
)

/**
 * 服务状态上报结构
 */
data class ServerData(
    val status: ServerStatusType,
    val description: String?,
    val insertTime: Long
)

enum class ServerStatusType {
    IDLE,
    RUNNING,
    ERROR,
    FINISHED
}
