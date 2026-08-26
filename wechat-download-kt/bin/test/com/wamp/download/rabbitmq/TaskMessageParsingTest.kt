package com.wamp.download.rabbitmq

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * 验证 RabbitMQ V2 BTA/ATD 消息解析。
 */
class TaskMessageParsingTest {

    private val gson = Gson()

    @Test
        fun `解析标准 V2 BTA 消息`() {
        val json = """
        {
                    "schemaVersion": "archive.rabbit.v2",
                    "eventId": "evt-bta-1",
                    "workflowId": "wf-1",
                    "causationId": null,
                    "idempotencyKey": "wf-1:BTA",
                    "messageType": "COMMAND",
                    "tenantCode": "tenant-a",
                    "mediaType": "WECHAT_OFFICIAL_ACCOUNT",
                    "operation": "AUTO_CRAWL",
                    "occurredAt": "2026-08-10T00:00:00Z",
                    "producer": {"service": "backend"},
                    "business": {"jobId": 1234567890123, "jobRunId": "run-1"},
                    "payload": {
                        "account": {"name": "测试公众号", "entryUrl": ""},
                        "captureWindow": {"startsAt": "2026-08-10", "endsAt": "2026-08-13"},
                        "collectionOptions": {"includeText": false}
                    }
        }
        """.trimIndent()

                val task = gson.fromJson<RabbitV2Envelope<GzhAutoBtaPayload>>(
                    json,
                    object : TypeToken<RabbitV2Envelope<GzhAutoBtaPayload>>() {}.type,
                )

                assertEquals("archive.rabbit.v2", task.schemaVersion)
                assertEquals("evt-bta-1", task.eventId)
                assertEquals("wf-1:BTA", task.idempotencyKey)
                assertEquals("tenant-a", task.tenantCode)
                assertEquals("测试公众号", task.payload.account.name)
                assertEquals("2026-08-10", task.payload.captureWindow.startsAt)
                assertEquals(1234567890123L, task.business.jobId)
    }

    @Test
    fun `解析 V2 ATD 关联字段`() {
        val json = """
        {"schemaVersion":"archive.rabbit.v2","eventId":"evt-atd-1","workflowId":"wf-1",
         "causationId":"evt-bta-1","idempotencyKey":"wf-1:ATD","messageType":"RESULT",
         "tenantCode":"tenant-a","mediaType":"WECHAT_OFFICIAL_ACCOUNT","operation":"AUTO_CRAWL",
         "occurredAt":"2026-01-01T00:00:00Z","producer":{"service":"android-worker"},
         "business":{"jobId":9223372036854775807,"jobRunId":"run-1"},
         "payload":{"accountName":"g","status":"EMPTY",
         "captureWindow":{"startsAt":"2026-01-01","endsAt":"2026-01-02"},"seedArticles":[]}}
        """.trimIndent()

        val result = gson.fromJson<RabbitV2Envelope<GzhAutoAtdPayload>>(
            json,
            object : TypeToken<RabbitV2Envelope<GzhAutoAtdPayload>>() {}.type,
        )
        assertEquals("RESULT", result.messageType)
        assertEquals("evt-bta-1", result.causationId)
        assertEquals("wf-1:ATD", result.idempotencyKey)
        assertEquals("EMPTY", result.payload.status)
        assertEquals(9223372036854775807L, result.business.jobId)
        assertNotNull(result.payload.captureWindow)
    }
}
