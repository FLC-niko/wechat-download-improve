package com.wamp.download.rabbitmq

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
