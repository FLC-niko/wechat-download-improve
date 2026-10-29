package com.wamp.download.rabbitmq

import kotlin.test.Test
import kotlin.test.assertEquals

class DeadLetterTaskInspectorTest {
    @Test
    fun `reads account name from v2 dead letter`() {
        val body = """
            {"schemaVersion":"archive.rabbit.v2","eventId":"e1","workflowId":"w1",
             "causationId":null,"idempotencyKey":"i1","messageType":"GZH_AUTO_BTA",
             "tenantCode":"pro","mediaType":"gzh","operation":"collect",
             "occurredAt":"2026-10-23T00:00:00Z","producer":{"service":"backend"},
             "business":{"jobId":1,"jobRunId":"run-1"},
             "payload":{"account":{"name":"广州海珠发布"},
             "captureWindow":{"startsAt":"2024-06-01","endsAt":"2025-07-01"},
             "collectionOptions":{}}}
        """.trimIndent().toByteArray()

        val hint = DeadLetterTaskInspector.hint(body)
        assertEquals("广州海珠发布", hint?.accountName)
        assertEquals("v2", hint?.messageType)
        assertEquals("广州海珠发布", hint?.resolveKey)
    }

    @Test
    fun `v2 entry url is preferred as the resolve key`() {
        val body = """
            {"schemaVersion":"archive.rabbit.v2","eventId":"e2","workflowId":"w2",
             "causationId":null,"idempotencyKey":"i2","messageType":"GZH_AUTO_BTA",
             "tenantCode":"pro","mediaType":"gzh","operation":"collect",
             "occurredAt":"2026-10-23T00:00:00Z","producer":{"service":"backend"},
             "business":{"jobId":2,"jobRunId":"run-2"},
             "payload":{"account":{"name":"海珠检查",
               "entryUrl":"https://mp.weixin.qq.com/s/real-article"},
             "captureWindow":{"startsAt":"2024-06-01","endsAt":"2025-07-01"},
             "collectionOptions":{}}}
        """.trimIndent().toByteArray()

        val hint = DeadLetterTaskInspector.hint(body)
        assertEquals("海珠检查", hint?.accountName)
        assertEquals("v2", hint?.messageType)
        assertEquals("https://mp.weixin.qq.com/s/real-article", hint?.resolveKey)
    }

    @Test
    fun `reads account name from legacy dead letter`() {
        val body = """
            {"queueName":"gzh-auto-BTA-queue","image":"","gzhName":"海珠检查",
             "tempTimeStamp":["2024-06-01","2025-07-01"],"url":null,
             "jobId":1,"correlationId":"legacy-1"}
        """.trimIndent().toByteArray()

        val hint = DeadLetterTaskInspector.hint(body)
        assertEquals("海珠检查", hint?.accountName)
        assertEquals("legacy", hint?.messageType)
        assertEquals("海珠检查", hint?.resolveKey)
    }
}
