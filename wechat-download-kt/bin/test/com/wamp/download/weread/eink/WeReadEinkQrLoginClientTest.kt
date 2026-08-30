package com.wamp.download.weread.eink

import com.google.gson.JsonParser
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WeReadEinkQrLoginClientTest {
    @Test
    fun `qr login obtains refreshable credentials and records explicit expiry`() {
        val transport = RecordingTransport(
            WeReadEinkResponse(200, "{\"signature\":\"ticket-signature\",\"timeStamp\":\"ticket-time\"}"),
            WeReadEinkResponse(200, "{\"errcode\":0,\"uuid\":\"uuid-1\"}"),
            WeReadEinkResponse(200, "{\"wx_errcode\":404}"),
            WeReadEinkResponse(200, "{\"wx_errcode\":405,\"wx_code\":\"wx-code\"}"),
            WeReadEinkResponse(
                200,
                "{\"vid\":123,\"accessToken\":\"access\",\"refreshToken\":\"refresh\",\"expiresIn\":3600}",
            ),
        )
        val now = 1_700_000_000_000L
        val statuses = mutableListOf<WeReadEinkQrStatus>()
        val client = WeReadEinkQrLoginClient(
            transport = transport,
            baseUrl = "https://eink.example.test",
            timestampProvider = { now },
            randomProvider = { 7 },
            waitProvider = {},
        )

        val qr = client.requestQr()
        val code = client.pollForCode(qr.uuid, onStatus = statuses::add)
        val credentials = client.exchange(code, deviceId = "eink-device")

        assertEquals("uuid-1", qr.uuid)
        assertEquals("https://open.weixin.qq.com/connect/confirm?uuid=uuid-1", qr.confirmUrl)
        assertEquals(listOf(WeReadEinkQrStatus.SCANNED, WeReadEinkQrStatus.CONFIRMED), statuses)
        assertEquals("123", credentials.vid)
        assertEquals("access", credentials.accessToken)
        assertEquals("refresh", credentials.refreshToken)
        assertEquals(now, credentials.accessTokenIssuedAt)
        assertEquals(now + 3_600_000L, credentials.accessTokenExpiresAt)

        val ticketUrl = transport.requests[0].url
        assertEquals("weread", ticketUrl.queryParameter("nonceStr"))
        val connectUrl = transport.requests[1].url
        assertEquals("wxab9b71ad2b90ff34", connectUrl.queryParameter("appid"))
        assertEquals("ticket-signature", connectUrl.queryParameter("signature"))
        assertEquals("uuid-1", transport.requests[2].url.queryParameter("uuid"))
        assertEquals("404", transport.requests[3].url.queryParameter("last"))

        val loginRequest = transport.requests.last()
        assertEquals("https://eink.example.test/login", loginRequest.url.toString())
        val body = JsonParser.parseString(loginRequest.body).asJsonObject
        assertEquals("wx-code", body["code"].asString)
        assertEquals("eink-device", body["deviceId"].asString)
        assertEquals(7, body["random"].asInt)
        assertEquals(sha256("${now}eink-device7"), body["signature"].asString)
        assertTrue(body["installId"].asString.startsWith("eink31"))
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }

    private class RecordingTransport(vararg responses: WeReadEinkResponse) : WeReadEinkTransport {
        val requests = mutableListOf<WeReadEinkRequest>()
        private val responses = ArrayDeque(responses.toList())

        override fun execute(request: WeReadEinkRequest): WeReadEinkResponse {
            requests += request
            return responses.removeFirstOrNull() ?: error("no fixture response left")
        }
    }
}
