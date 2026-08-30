package com.wamp.download.weread.eink

import okhttp3.HttpUrl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WeReadEinkChaptersClientTest {
    @Test
    fun `first request uses synckey and later request uses offset`() {
        val transport = RecordingTransport(
            WeReadEinkResponse(
                statusCode = 200,
                body = """
                    {
                      "data": [
                        {"reviewId":"r1","title":"One","createTime":1700000000,"mpInfo":{"originalId":"a1","title":"One"}},
                        {"reviewId":"r2","title":"Two","createTime":1700000001}
                      ],
                      "synckey": 42,
                      "hasMore": 1
                    }
                """.trimIndent(),
            ),
            WeReadEinkResponse(
                statusCode = 200,
                body = """
                    {"data":[{"reviewId":"r3","title":"Three","createTime":1700000002}],"synckey":43,"hasMore":0}
                """.trimIndent(),
            ),
        )
        val client = WeReadEinkChaptersClient(
            credentials = credentials(),
            transport = transport,
            baseUrl = "https://eink.example.test",
        )

        val first = client.fetchArticles(accountId = "MP_WXS_1", count = 2, synckey = 0)
        val second = client.fetchArticles(accountId = "MP_WXS_1", count = 2, offset = first.nextOffset)

        assertEquals(2, first.returnedCount)
        assertEquals(42L, first.synckey)
        assertEquals(1, first.hasMore)
        assertEquals(2L, first.nextOffset)
        assertEquals("a1", first.articles.first().mpInfo?.originalId)
        assertEquals(2L, second.requestedOffset)
        assertNull(second.nextOffset)
        assertEquals(43L, second.synckey)

        val firstUrl = transport.requests[0].url
        assertEquals("MP_WXS_1", firstUrl.queryParameter("bookId"))
        assertEquals("2", firstUrl.queryParameter("count"))
        assertEquals("0", firstUrl.queryParameter("synckey"))
        assertFalse(firstUrl.queryParameterNames.contains("offset"))

        val secondUrl = transport.requests[1].url
        assertEquals("2", secondUrl.queryParameter("offset"))
        assertFalse(secondUrl.queryParameterNames.contains("synckey"))
        assertEquals("1", transport.requests[0].headers["vid"])
        assertEquals("access-token", transport.requests[0].headers["accessToken"])
        assertTrue(transport.requests[0].headers["User-Agent"].orEmpty().contains("WRBrand/Onyx wr_eink"))
    }

    @Test
    fun `synckey and offset cannot be mixed`() {
        val transport = RecordingTransport()
        val client = WeReadEinkChaptersClient(credentials(), transport, "https://eink.example.test")

        assertFailsWith<IllegalArgumentException> {
            client.fetchArticles("MP_WXS_1", synckey = 1, offset = 0)
        }
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun `structured response errors are surfaced without parsing as an empty page`() {
        val transport = RecordingTransport(
            WeReadEinkResponse(200, "{\"errCode\":-2041,\"errMsg\":\"verify\"}"),
        )
        val client = WeReadEinkChaptersClient(credentials(), transport, "https://eink.example.test")

        val error = assertFailsWith<WeReadEinkApiException> {
            client.fetchArticles("MP_WXS_1")
        }

        assertEquals(WeReadEinkResponseKind.HUMAN_VERIFY, error.kind)
        assertEquals(-2041, error.errCode)
        assertEquals("verify", error.errMsg)
    }

    @Test
    fun `missing data array is a parse error`() {
        val transport = RecordingTransport(WeReadEinkResponse(200, "{\"synckey\":1}"))
        val client = WeReadEinkChaptersClient(credentials(), transport, "https://eink.example.test")

        assertFailsWith<WeReadEinkParseException> {
            client.fetchArticles("MP_WXS_1")
        }
    }

    private fun credentials() = WeReadEinkCredentials(
        vid = "1",
        accessToken = "access-token",
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
            return responses.removeFirstOrNull() ?: error("no fixture response left")
        }
    }
}
