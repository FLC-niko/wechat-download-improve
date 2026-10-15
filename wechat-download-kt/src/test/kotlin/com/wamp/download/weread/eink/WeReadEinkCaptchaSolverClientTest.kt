package com.wamp.download.weread.eink

import com.google.gson.JsonParser
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WeReadEinkCaptchaSolverClientTest {
    @Test
    fun `successful solve sends authenticated documented contract`() {
        val capturedBody = AtomicReference<String>()
        val capturedSecret = AtomicReference<String>()
        withServer { server ->
            server.createContext("/solve") { exchange ->
                capturedBody.set(exchange.requestBody.bufferedReader().use { it.readText() })
                capturedSecret.set(exchange.requestHeaders.getFirst("X-SK"))
                exchange.respond(200, """{"ok":true,"ticket":"ticket-1","randstr":"@rand-1","attempts":1}""")
            }
            val result = client(server, secret = "local-secret").solve("https://test.invalid/verify/vid/nonce")

            assertEquals(WeReadEinkCaptchaSolveCode.SOLVED, result.code)
            assertEquals("ticket-1", result.captcha?.ticket)
            assertEquals("@rand-1", result.captcha?.randstr)
            assertEquals(1, result.attempts)
            assertEquals("local-secret", capturedSecret.get())
            val request = JsonParser.parseString(capturedBody.get()).asJsonObject
            assertEquals("test-appid", request["appid"].asString)
            assertEquals(1, request["retries"].asInt)
            assertEquals("https://test.invalid/verify/vid/nonce", request["entry_url"].asString)
            assertEquals(3, request.size())
        }
    }

    @Test
    fun `solver failures are classified without exposing captcha data`() {
        val cases = listOf(
            401 to "{}" to WeReadEinkCaptchaSolveCode.SIDE_CAR_UNAUTHORIZED,
            500 to "{}" to WeReadEinkCaptchaSolveCode.SIDE_CAR_UNAVAILABLE,
            429 to "{}" to WeReadEinkCaptchaSolveCode.SOLVE_FAILED,
            200 to "not-json" to WeReadEinkCaptchaSolveCode.INVALID_RESULT,
            200 to """{"ok":false,"error":"model failed","attempts":1}""" to WeReadEinkCaptchaSolveCode.SOLVE_FAILED,
            200 to """{"ok":true,"ticket":"","randstr":"@r","attempts":1}""" to WeReadEinkCaptchaSolveCode.INVALID_RESULT,
        )
        for ((responseAndBody, expected) in cases) {
            withServer { server ->
                server.createContext("/solve") { it.respond(responseAndBody.first, responseAndBody.second) }
                val result = client(server).solve("https://test.invalid/verify/vid/nonce")
                assertEquals(expected, result.code)
                assertNull(result.captcha)
            }
        }
    }

    @Test
    fun `oversized and slow responses fail closed`() {
        withServer { server ->
            server.createContext("/solve") { it.respond(200, "x".repeat(70 * 1024)) }
            assertEquals(
                WeReadEinkCaptchaSolveCode.INVALID_RESULT,
                client(server).solve("https://test.invalid/verify/vid/nonce").code,
            )
        }
        withServer { server ->
            server.createContext("/solve") { exchange ->
                Thread.sleep(1_500)
                runCatching { exchange.respond(200, """{"ok":false}""") }
            }
            val result = client(server, timeoutSeconds = 1).solve("https://test.invalid/verify/vid/nonce")
            assertEquals(WeReadEinkCaptchaSolveCode.SIDE_CAR_UNAVAILABLE, result.code)
            assertTrue(result.durationMs >= 500)
        }
    }

    @Test
    fun `non-loopback sidecar configuration is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            WeReadEinkCaptchaSolverClient(
                baseUrl = "https://solver.example.com:9991",
                appId = "test-appid",
                secret = "",
                retries = 1,
                timeoutSeconds = 5,
            )
        }
    }

    private fun client(
        server: HttpServer,
        secret: String = "",
        timeoutSeconds: Long = 5,
    ) = WeReadEinkCaptchaSolverClient(
        baseUrl = "http://127.0.0.1:${server.address.port}",
        appId = "test-appid",
        secret = secret,
        retries = 1,
        timeoutSeconds = timeoutSeconds,
    )

    private fun withServer(block: (HttpServer) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.start()
        try {
            block(server)
        } finally {
            server.stop(0)
        }
    }

    private fun HttpExchange.respond(status: Int, payload: String) {
        val bytes = payload.toByteArray()
        responseHeaders.add("Content-Type", "application/json; charset=utf-8")
        sendResponseHeaders(status, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }
}
