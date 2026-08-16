package com.wamp.download.rabbitmq

import com.google.gson.Gson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 验证 RabbitMQ 任务消息解析与 Worker 保持一致。
 *
 * 参照 Worker 的消息结构（GzhAutoFromBackend）：
 * - 直接 decode 为 GzhAutoFromBackend（无 envelope 包装）
 * - 字段：queueName/image/gzhName/tempTimeStamp/url/jobId/correlationId
 * - jobId 为 Long，correlationId 为字符串，日期范围为字符串列表
 */
class TaskMessageParsingTest {

    private val gson = Gson()

    @Test
    fun `解析标准任务消息`() {
        val json = """
        {
          "queueName": "gzh-auto-BTA-queue",
          "image": "http://img.example.com/cover.png",
          "gzhName": "测试公众号",
          "tempTimeStamp": ["2026-08-10", "2026-08-13"],
          "url": "",
          "jobId": 1234567890123,
          "correlationId": "corr-abc-123"
        }
        """.trimIndent()

        val task = gson.fromJson(json, GzhAutoFromBackend::class.java)

        assertEquals("gzh-auto-BTA-queue", task.queueName)
        assertEquals("http://img.example.com/cover.png", task.image)
        assertEquals("测试公众号", task.gzhName)
        assertEquals(listOf("2026-08-10", "2026-08-13"), task.tempTimeStamp)
        assertEquals("", task.url)
        assertEquals(1234567890123L, task.jobId)  // Long 精度
        assertEquals("corr-abc-123", task.correlationId)
    }

    @Test
    fun `解析大数值 jobId 不丢失精度`() {
        val json = """
        {"queueName":"q","image":"","gzhName":"g","tempTimeStamp":["2026-01-01","2026-01-02"],
         "url":"","jobId":9223372036854775807,"correlationId":"c1"}
        """.trimIndent()

        val task = gson.fromJson(json, GzhAutoFromBackend::class.java)
        assertEquals(9223372036854775807L, task.jobId)
    }

    @Test
    fun `结果消息 url 可为 null`() {
        // 空结果时 Worker 发布 url=null 的消息
        val json = """
        {"queueName":"gzh-auto-ATD-queue","gzhName":"g","tempTimeStamp":["2026-01-01","2026-01-02"],
         "url":null,"jobId":1,"correlationId":"c1"}
        """.trimIndent()

        val result = gson.fromJson(json, GzhAutoToBigData::class.java)
        assertNull(result.url)
        assertEquals("gzh-auto-ATD-queue", result.queueName)
    }
}
