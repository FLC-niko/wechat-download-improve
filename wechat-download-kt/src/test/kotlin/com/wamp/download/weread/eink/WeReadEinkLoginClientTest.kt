package com.wamp.download.weread.eink

import com.google.gson.JsonParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WeReadEinkLoginClientTest {
    @Test
    fun `refresh sends profile fields and signed request body`() {
        val transport = RecordingTransport(
            WeReadEinkResponse(
                statusCode = 200,
                body = """
                    {"vid":"old-vid","accessToken":"new-access","refreshToken":"new-refresh"}
                """.trimIndent(),
            ),
        )
        val client = WeReadEinkLoginClient(
            transport = transport,
            baseUrl = "https://eink.example.test",
            timestampProvider = { 1_700_000_000_000L },
            randomProvider = { 7 },
        )

        val refreshed = client.refresh(credentials())

        assertEquals("old-vid", refreshed.vid)
        assertEquals("new-access", refreshed.accessToken)
        assertEquals("new-refresh", refreshed.refreshToken)
        val request = transport.requests.single()
        assertEquals("POST", request.method)
        assertEquals("https://eink.example.test/login", request.url.toString())
        assertEquals("application/json; charset=utf-8", request.headers["Content-Type"])

        val body = JsonParser.parseString(request.body).asJsonObject
        assertEquals("eink-device", body["deviceId"].asString)
        assertEquals("BOOX", body["deviceName"].asString)
        assertEquals(3, body["deviceType"].asInt)
        assertEquals(7, body["random"].asInt)
        assertEquals(1_700_000_000_000L, body["timestamp"].asLong)
        assertEquals("refresh-token", body["refreshToken"].asString)
        assertEquals(
            "9cc1a97d6346294a24ed5354ab0238e31ea5f566ae1ba0f1c88f39aea03724f6",
            body["signature"].asString,
        )
    }

    @Test
    fun `refresh accepts access-token-only response and preserves stable credentials`() {
        val client = WeReadEinkLoginClient(
            transport = RecordingTransport(
                WeReadEinkResponse(200, "{\"accessToken\":\"new-access\"}"),
            ),
        )

        val refreshed = client.refresh(credentials())

        assertEquals("old-vid", refreshed.vid)
        assertEquals("new-access", refreshed.accessToken)
        assertEquals("refresh-token", refreshed.refreshToken)
        assertEquals("eink-device", refreshed.deviceId)
    }

    @Test
    fun `refresh rejects credentials for a different vid`() {
        val client = WeReadEinkLoginClient(
            transport = RecordingTransport(
                WeReadEinkResponse(
                    200,
                    "{\"vid\":\"another-vid\",\"accessToken\":\"new-access\"}",
                ),
            ),
        )

        val error = assertFailsWith<WeReadEinkParseException> {
            client.refresh(credentials())
        }

        assertTrue(error.message.orEmpty().contains("vid"))
    }

    @Test
    fun `refresh still requires a new access token`() {
        val client = WeReadEinkLoginClient(
            transport = RecordingTransport(
                WeReadEinkResponse(200, "{\"vid\":\"old-vid\"}"),
            ),
        )

        val error = assertFailsWith<WeReadEinkParseException> {
            client.refresh(credentials())
        }

        assertTrue(error.message.orEmpty().contains("accessToken"))
    }

    @Test
    fun `refresh failure is classified instead of returning empty credentials`() {
        val client = WeReadEinkLoginClient(
            transport = RecordingTransport(
                WeReadEinkResponse(200, "{\"errCode\":-2041,\"errMsg\":\"verify\"}"),
            ),
        )

        val error = assertFailsWith<WeReadEinkApiException> {
            client.refresh(credentials())
        }

        assertEquals(WeReadEinkResponseKind.HUMAN_VERIFY, error.kind)
        assertEquals(-2041, error.errCode)
        assertTrue(error.message.orEmpty().contains("verify"))
    }

    private fun credentials() = WeReadEinkCredentials(
        vid = "old-vid",
        accessToken = "old-access",
        refreshToken = "refresh-token",
        deviceId = "eink-device",
    )

    private class RecordingTransport(
        vararg responses: WeReadEinkResponse,
    ) : WeReadEinkTransport {
        val requests = mutableListOf<WeReadEinkRequest>()
        private val responses = ArrayDeque(responses.toList())

        override fun execute(request: WeReadEinkRequest): WeReadEinkResponse {
            requests += request
            return responses.removeFirst()
        }
    }
}
