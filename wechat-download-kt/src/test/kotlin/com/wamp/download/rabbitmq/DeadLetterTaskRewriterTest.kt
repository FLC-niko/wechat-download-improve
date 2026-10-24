package com.wamp.download.rabbitmq

import com.google.gson.JsonParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DeadLetterTaskRewriterTest {
    @Test
    fun `v2 account name is rewritten without dropping other payload fields`() {
        val body = """
            {"schemaVersion":"archive.rabbit.v2","eventId":"e1","workflowId":"w1",
             "idempotencyKey":"i1","payload":{"account":{"name":"海珠检查",
             "entryUrl":"https://mp.weixin.qq.com/s/real"},
             "captureWindow":{"startsAt":"2024-06-01","endsAt":"2025-07-01"}}}
        """.trimIndent().toByteArray()

        val rewritten = DeadLetterTaskRewriter.rewriteAccountName(body, "广州海珠发布")
        val root = JsonParser.parseString(rewritten.toString(Charsets.UTF_8)).asJsonObject
        val account = root.getAsJsonObject("payload").getAsJsonObject("account")

        assertEquals("广州海珠发布", account.get("name").asString)
        assertEquals("https://mp.weixin.qq.com/s/real", account.get("entryUrl").asString)
        assertEquals("2024-06-01", root.getAsJsonObject("payload")
            .getAsJsonObject("captureWindow").get("startsAt").asString)
    }

    @Test
    fun `legacy gzh name is rewritten without dropping correlation id`() {
        val body = """
            {"queueName":"gzh-auto-BTA-queue","gzhName":"海珠检查",
             "tempTimeStamp":["2024-06-01","2025-07-01"],"jobId":1,"correlationId":"legacy-1"}
        """.trimIndent().toByteArray()

        val rewritten = DeadLetterTaskRewriter.rewriteAccountName(body, "广州海珠发布")
        val root = JsonParser.parseString(rewritten.toString(Charsets.UTF_8)).asJsonObject

        assertEquals("广州海珠发布", root.get("gzhName").asString)
        assertEquals("legacy-1", root.get("correlationId").asString)
    }

    @Test
    fun `invalid json is left unchanged`() {
        val body = "not-json".toByteArray()
        val rewritten = DeadLetterTaskRewriter.rewriteAccountName(body, "广州海珠发布")
        assertTrue(rewritten.contentEquals(body))
    }
}
