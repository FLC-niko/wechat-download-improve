package com.wamp.download.rabbitmq

import com.google.gson.Gson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ResultQueuePublisherTest {
    private val input = RabbitV2Envelope(
        schemaVersion = RABBIT_SCHEMA_VERSION,
        eventId = "evt-bta-1",
        workflowId = "wf-1",
        causationId = null,
        idempotencyKey = "wf-1:BTA",
        messageType = "COMMAND",
        tenantCode = "tenant-a",
        mediaType = "WECHAT_OFFICIAL_ACCOUNT",
        operation = "AUTO_CRAWL",
        occurredAt = "2026-08-10T00:00:00Z",
        producer = RabbitProducer("backend"),
        business = RabbitBusiness(123L, "run-1"),
        payload = GzhAutoBtaPayload(
            account = GzhAccount("测试公众号"),
            captureWindow = CaptureWindow("2026-08-10", "2026-08-13"),
        ),
    )

    @Test
    fun `ATD 保留关联字段并按 URL 去重`() {
        val message = ResultQueuePublisher(
            clientManager = RabbitMqClientManager(),
            defaultResultEventId = "evt-atd-1",
        ).buildMessage(
            input = input,
            status = "SUCCEEDED",
            seedArticles = listOf(
                SeedArticle("https://example.com/1", title = "one"),
                SeedArticle("https://example.com/1", title = "duplicate"),
                SeedArticle("https://example.com/2"),
            ),
        )

        assertEquals("evt-atd-1", message.eventId)
        assertEquals("evt-bta-1", message.causationId)
        assertEquals("wf-1:ATD", message.idempotencyKey)
        assertEquals("tenant-a", message.tenantCode)
        assertEquals(listOf("https://example.com/1", "https://example.com/2"), message.payload.seedArticles.map { it.url })
    }

    @Test
    fun `ATD 状态约束拒绝不完整结果`() {
        val publisher = ResultQueuePublisher(RabbitMqClientManager())

        assertFailsWith<IllegalArgumentException> {
            publisher.buildMessage(input, status = "SUCCEEDED")
        }
        assertFailsWith<IllegalArgumentException> {
            publisher.buildMessage(input, status = "FAILED")
        }
        assertFailsWith<IllegalArgumentException> {
            publisher.buildMessage(input, status = "EMPTY", seedArticles = listOf(SeedArticle("url")))
        }
    }

    @Test
    fun `ATD envelope 可按 JSON 往返解析`() {
        val message = ResultQueuePublisher(RabbitMqClientManager()).buildMessage(
            input = input,
            status = "EMPTY",
            eventId = "evt-atd-empty",
        )
        val decoded = Gson().fromJson(
            Gson().toJson(message),
            RabbitV2Envelope::class.java,
        )

        assertEquals("evt-atd-empty", decoded.eventId)
        assertEquals("wf-1:ATD", decoded.idempotencyKey)
    }
}