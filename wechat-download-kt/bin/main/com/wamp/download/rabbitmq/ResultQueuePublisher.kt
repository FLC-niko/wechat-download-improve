package com.wamp.download.rabbitmq

import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

/**
 * 结果队列发布器 — 参照 Worker 的 ResultQueuePublisher 实现
 *
 * 向结果队列 `gzh-auto-ATD-queue` 发布采集结果。
 * 消息体字段（queueName/gzhName/tempTimeStamp/url/jobId/correlationId）与文档保持一致。
 */
class ResultQueuePublisher(
    private val clientManager: RabbitMqClientManager,
    private val gson: Gson = Gson()
) {
    companion object {
        private val logger = LoggerFactory.getLogger(ResultQueuePublisher::class.java)
    }

    /**
     * 批量发布文章链接到结果队列
     *
     * @param gzhName 公众号名称
     * @param jobId 任务 ID
     * @param correlationId 关联 ID
     * @param tempTimeStamp 日期范围
     * @param urls 文章 URL 列表（可能为空，空则发一条 url=null 的消息）
     */
    suspend fun publishArticles(
        gzhName: String,
        jobId: Long,
        correlationId: String,
        tempTimeStamp: List<String>,
        urls: List<String>
    ) {
        val window = if (tempTimeStamp.isEmpty()) listOf("", "") else tempTimeStamp

        if (urls.isEmpty()) {
            publishEmptyResult(gzhName, jobId, correlationId, window)
            return
        }

        urls.forEach { url ->
            val payload = GzhAutoToBigData(
                queueName = GZH_RESULT_QUEUE,
                gzhName = gzhName,
                tempTimeStamp = window,
                url = url,
                jobId = jobId,
                correlationId = correlationId
            )
            clientManager.publishJson(GZH_RESULT_QUEUE, payload)
        }
        logger.info("结果队列发布统计: jobId=$jobId, correlationId=$correlationId, queue=$GZH_RESULT_QUEUE, publishedCount=${urls.size}")
    }

    /**
     * 发布空结果（当确认公众号在目标日期内没有发布文章时）
     */
    suspend fun publishEmptyResult(
        gzhName: String,
        jobId: Long,
        correlationId: String,
        tempTimeStamp: List<String>
    ) {
        val payload = GzhAutoToBigData(
            queueName = GZH_RESULT_QUEUE,
            gzhName = gzhName,
            tempTimeStamp = tempTimeStamp,
            url = null,  // 空结果，url 为 null
            jobId = jobId,
            correlationId = correlationId
        )
        clientManager.publishJson(GZH_RESULT_QUEUE, payload)
        logger.info("结果队列发布统计: jobId=$jobId, correlationId=$correlationId, queue=$GZH_RESULT_QUEUE, publishedCount=0")
    }
}
