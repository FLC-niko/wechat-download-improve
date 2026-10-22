package com.wamp.download.task

import com.rabbitmq.client.ConnectionFactory
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import java.io.File

import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import javax.net.SocketFactory

// Manual integration test: requires QR fixtures in /tmp and a RabbitMQ broker.
@Disabled("manual integration test; set BTA_RABBIT_* env vars and prepare /tmp fixtures to run")
class PublishArticleBtaTaskTest {

    @Test
    fun publishArticleBtaTask() {
        val b64 = File("/tmp/article_qr_b64.txt").readText().trim()
        val sha256 = File("/tmp/article_qr_sha256.txt").readText().trim()
        val sizeBytes = File("/tmp/article_qr_size.txt").readText().trim().toLong()

        val jobId = System.currentTimeMillis() % 100000
        val workflowId = "wf-gdgqt-$jobId"
        val json = """
            {
              "schemaVersion": "archive.rabbit.v2",
              "eventId": "evt-$jobId",
              "workflowId": "$workflowId",
              "causationId": null,
              "idempotencyKey": "$workflowId:BTA",
              "messageType": "COMMAND",
              "tenantCode": "topview",
              "mediaType": "WECHAT_OFFICIAL_ACCOUNT",
              "operation": "AUTO_CRAWL",
              "occurredAt": "2026-09-13T02:15:00Z",
              "producer": { "service": "test-runner" },
              "business": { "jobId": $jobId, "jobRunId": "run-$jobId" },
              "payload": {
                "account": {
                  "name": "广东共青团",
                  "qrImage": {
                    "transport": "INLINE_BASE64",
                    "dataBase64": "$b64",
                    "sha256": "$sha256",
                    "contentType": "image/jpeg",
                    "sizeBytes": $sizeBytes
                  }
                },
                "captureWindow": {
                  "startsAt": "2026-09-11",
                  "endsAt": "2026-09-13"
                }
              }
            }
        """.trimIndent()

        val factory = ConnectionFactory().apply {
            host = System.getenv("BTA_RABBIT_HOST") ?: "127.0.0.1"
            port = 5672
            username = System.getenv("BTA_RABBIT_USER") ?: "guest"
            password = System.getenv("BTA_RABBIT_PASSWORD") ?: ""
            virtualHost = "xdag"
            isAutomaticRecoveryEnabled = false
            connectionTimeout = 10_000
            handshakeTimeout = 10_000

            val physicalAddr = NetworkInterface.getNetworkInterfaces().asSequence()
                .filter { it.isUp && !it.isLoopback && !it.name.startsWith("utun") }
                .flatMap { it.inetAddresses.asSequence() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { !it.isLoopbackAddress }
            if (physicalAddr != null) {
                println("Binding RabbitMQ client to local physical IP: ${physicalAddr.hostAddress}")
                setSocketFactory(object : SocketFactory() {
                    override fun createSocket(): Socket = Socket().apply {
                        bind(InetSocketAddress(physicalAddr, 0))
                    }
                    override fun createSocket(host: String, port: Int): Socket = Socket().apply {
                        bind(InetSocketAddress(physicalAddr, 0))
                        connect(InetSocketAddress(host, port), 10_000)
                    }
                    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
                        createSocket(host, port)
                    override fun createSocket(host: InetAddress, port: Int): Socket =
                        createSocket(host.hostAddress, port)
                    override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
                        createSocket(address.hostAddress, port)
                })
            }
        }

        factory.newConnection().use { conn ->
            conn.createChannel().use { ch ->
                val testQueue = "test-atd-listener-$jobId"
                ch.queueDeclare(testQueue, false, true, true, null)
                ch.queueBind(testQueue, "archive.new-media.v2", "archive.gzh.auto.atd.v2")
                println("Bound test queue $testQueue to archive.new-media.v2 (archive.gzh.auto.atd.v2)")

                println("Connected to RabbitMQ xdag, publishing task jobId=$jobId, account=广东共青团...")
                ch.basicPublish(
                    "archive.new-media.v2",
                    "archive.gzh.auto.bta.v2",
                    null,
                    json.toByteArray(Charsets.UTF_8)
                )
                println("Published successfully! jobId=$jobId, workflowId=$workflowId")

                println("Waiting for ATD result on $testQueue (timeout 180s)...")
                val start = System.currentTimeMillis()
                while (System.currentTimeMillis() - start < 180_000) {
                    val response = ch.basicGet(testQueue, true)
                    if (response != null) {
                        val body = String(response.body, Charsets.UTF_8)
                        println("=== RECEIVED ATD MESSAGE ===")
                        println(body)
                        return
                    }
                    Thread.sleep(2000)
                }
                println("Timeout waiting for ATD response!")
            }
        }
    }

    @Test
    fun publishArticleDlqTask() {
        val b64 = File("/tmp/article_qr_b64.txt").readText().trim()
        val sha256 = File("/tmp/article_qr_sha256.txt").readText().trim()
        val sizeBytes = File("/tmp/article_qr_size.txt").readText().trim().toLong()

        val jobId = System.currentTimeMillis() % 100000
        val workflowId = "wf-gdgqt-dlq-$jobId"
        val json = """
            {
              "schemaVersion": "archive.rabbit.v2",
              "eventId": "evt-dlq-$jobId",
              "workflowId": "$workflowId",
              "causationId": null,
              "idempotencyKey": "$workflowId:BTA",
              "messageType": "COMMAND",
              "tenantCode": "topview",
              "mediaType": "WECHAT_OFFICIAL_ACCOUNT",
              "operation": "AUTO_CRAWL",
              "occurredAt": "2026-09-13T02:15:00Z",
              "producer": { "service": "test-runner-dlq" },
              "business": { "jobId": $jobId, "jobRunId": "run-dlq-$jobId" },
              "payload": {
                "account": {
                  "name": "广东共青团",
                  "qrImage": {
                    "transport": "INLINE_BASE64",
                    "dataBase64": "$b64",
                    "sha256": "$sha256",
                    "contentType": "image/jpeg",
                    "sizeBytes": $sizeBytes
                  }
                },
                "captureWindow": {
                  "startsAt": "2026-09-11",
                  "endsAt": "2026-09-13"
                }
              }
            }
        """.trimIndent()

        val factory = ConnectionFactory().apply {
            host = System.getenv("BTA_RABBIT_HOST") ?: "127.0.0.1"
            port = 5672
            username = System.getenv("BTA_RABBIT_USER") ?: "guest"
            password = System.getenv("BTA_RABBIT_PASSWORD") ?: ""
            virtualHost = "xdag"
            isAutomaticRecoveryEnabled = false
            connectionTimeout = 10_000
            handshakeTimeout = 10_000

            val physicalAddr = NetworkInterface.getNetworkInterfaces().asSequence()
                .filter { it.isUp && !it.isLoopback && !it.name.startsWith("utun") }
                .flatMap { it.inetAddresses.asSequence() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { !it.isLoopbackAddress }
            if (physicalAddr != null) {
                println("Binding RabbitMQ client to local physical IP: ${physicalAddr.hostAddress}")
                setSocketFactory(object : SocketFactory() {
                    override fun createSocket(): Socket = Socket().apply {
                        bind(InetSocketAddress(physicalAddr, 0))
                    }
                    override fun createSocket(host: String, port: Int): Socket = Socket().apply {
                        bind(InetSocketAddress(physicalAddr, 0))
                        connect(InetSocketAddress(host, port), 10_000)
                    }
                    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
                        createSocket(host, port)
                    override fun createSocket(host: InetAddress, port: Int): Socket =
                        createSocket(host.hostAddress, port)
                    override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
                        createSocket(address.hostAddress, port)
                })
            }
        }

        factory.newConnection().use { conn ->
            conn.createChannel().use { ch ->
                val testQueue = "test-atd-dlq-listener-$jobId"
                ch.queueDeclare(testQueue, false, true, true, null)
                ch.queueBind(testQueue, "archive.new-media.v2", "archive.gzh.auto.atd.v2")
                println("Bound test queue $testQueue to archive.new-media.v2 (archive.gzh.auto.atd.v2)")

                println("Connected to RabbitMQ xdag, publishing DLQ fallback task jobId=$jobId, account=广东共青团...")
                ch.basicPublish(
                    "archive.new-media.v2",
                    "archive.gzh.auto.atd.v2.dlq",
                    null,
                    json.toByteArray(Charsets.UTF_8)
                )
                println("Published DLQ message successfully! jobId=$jobId, workflowId=$workflowId")

                println("Waiting for ATD result on $testQueue (timeout 180s)...")
                val start = System.currentTimeMillis()
                while (System.currentTimeMillis() - start < 180_000) {
                    val response = ch.basicGet(testQueue, true)
                    if (response != null) {
                        val body = String(response.body, Charsets.UTF_8)
                        println("=== RECEIVED ATD MESSAGE FROM DLQ RUN ===")
                        println(body)
                        return
                    }
                    Thread.sleep(2000)
                }
                println("Timeout waiting for ATD response from DLQ run!")
            }
        }
    }
}
