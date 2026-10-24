package com.wamp.download.rabbitmq

import com.rabbitmq.client.ConnectionFactory
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeadLetterReprocessIntegrationTest {
    @Test
    fun `dead letter is republished to BTA and acknowledged`() {
        assumeTrue(System.getenv("DLQ_INTEGRATION") == "1", "set DLQ_INTEGRATION=1 with a local RabbitMQ on 127.0.0.1:5672")

        val manager = RabbitMqClientManager()
        val factory = ConnectionFactory().apply {
            host = "127.0.0.1"
            port = 5672
            username = "guest"
            password = "guest"
            virtualHost = "/"
        }
        val body = """
            {"queueName":"gzh-auto-BTA-queue","image":"","gzhName":"广州海珠发布",
             "tempTimeStamp":["2024-06-01","2025-07-01"],"url":null,
             "jobId":9003,"correlationId":"dlq-integration-9003"}
        """.trimIndent().toByteArray()

        try {
            factory.newConnection("dlq-integration-check").use { checkConnection ->
                checkConnection.createChannel().use { checkChannel ->
                    checkChannel.queueDeclare(GZH_DEAD_QUEUE, true, false, false, null)
                    checkChannel.queueDeclare(GZH_TASK_QUEUE, true, false, false, null)
                    checkChannel.queuePurge(GZH_DEAD_QUEUE)
                    checkChannel.queuePurge(GZH_TASK_QUEUE)
                }
            }

            runBlocking {
                manager.connect(
                    host = "127.0.0.1",
                    port = 5672,
                    username = "guest",
                    password = "guest",
                    virtualHost = "/",
                )
                manager.publish("dead-message-queue", body)
                val result = manager.reprocessDeadLetters(limit = 1, dryRun = false, vhost = "pro") { "广州海珠发布" }
                assertEquals(1, result.scanned)
                assertEquals(1, result.requeued)
                assertEquals(0, result.failed)
            }

            factory.newConnection("dlq-integration-verify").use { verifyConnection ->
                verifyConnection.createChannel().use { verifyChannel ->
                    val delivered = verifyChannel.basicGet(GZH_TASK_QUEUE, true)
                    assertNotNull(delivered)
                    val rewritten = delivered.body.toString(Charsets.UTF_8)
                    assertTrue(rewritten.contains("\"gzhName\":\"广州海珠发布\""))
                    assertTrue(rewritten.contains("\"jobId\":9003"))
                    assertNull(verifyChannel.basicGet(GZH_DEAD_QUEUE, true))
                }
            }
        } finally {
            manager.close()
        }
    }
}
