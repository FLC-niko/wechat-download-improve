package com.wamp.download.rabbitmq

import com.google.gson.Gson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import com.wamp.download.task.AaosFallbackRequiredException

class ResultQueuePublisherTest {
    @Test
    fun `pro 旧协议向原 ATD 队列发布带 URL 的扁平文章并保留任务关联`() = runBlocking {
        val sent = mutableListOf<Pair<String, Any>>()
        val publisher = ResultQueuePublisher(
            RabbitMqClientManager(),
            legacyWireFormat = true,
            legacySender = { queue, payload -> sent += queue to payload },
        )
        publisher.publishArticles(
            "旧版公众号", 4321851L, "legacy-correlation", listOf("2022-12-19", "2023-01-08"),
            listOf("https://mp.weixin.qq.com/s/article-one", "https://mp.weixin.qq.com/s/article-one"),
        )
        assertEquals(1, sent.size)
        assertEquals(GZH_RESULT_QUEUE, sent.single().first)
        val json = com.google.gson.JsonParser.parseString(Gson().toJson(sent.single().second)).asJsonObject
        assertFalse(json.has("schemaVersion"))
        assertFalse(json.has("payload"))
        assertEquals(GZH_RESULT_QUEUE, json["queueName"].asString)
        assertEquals("旧版公众号", json["gzhName"].asString)
        assertEquals("https://mp.weixin.qq.com/s/article-one", json["url"].asString)
        assertEquals(4321851L, json["jobId"].asLong)
        assertEquals("legacy-correlation", json["correlationId"].asString)
        assertEquals(listOf("2022-12-19", "2023-01-08"), json["tempTimeStamp"].asJsonArray.map { it.asString })
    }

    @Test
    fun `pro 失败不能伪装空文章或向旧队列发送 V2 FAILED 而应转 AAOS`() = runBlocking {
        val sent = mutableListOf<Any>()
        val publisher = ResultQueuePublisher(
            RabbitMqClientManager(), legacyWireFormat = true,
            legacySender = { _, payload -> sent += payload },
        )
        assertFailsWith<AaosFallbackRequiredException> {
            publisher.publishFailedResult(
                "旧版公众号", 4321851L, "legacy-correlation", listOf("2022-12-19", "2023-01-08"),
                AtdError("ACCOUNT_LOOKUP_UNAVAILABLE", "主微信不可用", false),
            )
        }
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `pro 已确认无文章使用旧版空结果且不会包装 envelope`() = runBlocking {
        val sent = mutableListOf<Any>()
        val publisher = ResultQueuePublisher(
            RabbitMqClientManager(), legacyWireFormat = true,
            legacySender = { _, payload -> sent += payload },
        )
        publisher.publishEmptyResult("旧版公众号", 1L, "empty-result", listOf("2024-01-01", "2024-01-02"))
        val result = sent.single() as GzhAutoToBigData
        assertEquals(null, result.url)
        assertEquals("empty-result", result.correlationId)
    }

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
