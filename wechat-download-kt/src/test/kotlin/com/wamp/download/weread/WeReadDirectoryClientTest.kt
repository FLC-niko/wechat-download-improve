package com.wamp.download.weread

import com.wamp.download.weread.eink.WeReadEinkRequest
import com.wamp.download.weread.eink.WeReadEinkResponse
import com.wamp.download.weread.eink.WeReadEinkTransport
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WeReadDirectoryClientTest {
    @Test
    fun `store search maps public account book info`() {
        val transport = RecordingTransport(
            WeReadEinkResponse(
                200,
                """
                    {"books":[
                      {"bookInfo":{"bookId":"MP_WXS_3295180586","title":"广州海珠发布","cover":"cover","intro":"intro"}},
                      {"bookInfo":{"bookId":"B001","title":"普通电子书"}}
                    ]}
                """.trimIndent(),
            ),
        )
        val client = client(transport)

        val accounts = client.searchAccounts("海珠")

        assertEquals(1, accounts.size)
        assertEquals(fakeid("3295180586"), accounts.single().fakeid)
        assertEquals("MP_WXS_3295180586", accounts.single().bookId)
        assertEquals("广州海珠发布", accounts.single().nickname)
        assertEquals("cover", accounts.single().headImg)
        assertEquals("/store/search", transport.requests.single().url.encodedPath)
        assertEquals("2", transport.requests.single().url.queryParameter("scope"))
    }

    @Test
    fun `name resolution requires an exact nickname match`() {
        val transport = RecordingTransport(
            WeReadEinkResponse(
                200,
                """{"books":[{"bookInfo":{"bookId":"MP_WXS_3295180586","title":"广州海珠发布"}}]}""",
            ),
        )
        val client = client(transport)

        assertNull(client.resolve("海珠检查"))
    }

    @Test
    fun `url resolution uses getreviewid and book info`() {
        val transport = RecordingTransport(
            WeReadEinkResponse(200, """{"bookId":"MP_WXS_3295180586"}"""),
            WeReadEinkResponse(200, """{"title":"广州海珠发布","cover":"cover","intro":"intro"}"""),
        )
        val client = client(transport)

        val account = client.resolveByUrl("https://mp.weixin.qq.com/s/example")

        assertNotNull(account)
        assertEquals(fakeid("3295180586"), account.fakeid)
        assertEquals("广州海珠发布", account.nickname)
        assertEquals("/mp/getreviewid", transport.requests[0].url.encodedPath)
        assertEquals("/book/info", transport.requests[1].url.encodedPath)
    }

    @Test
    fun `url with biz resolves without getreviewid`() {
        val transport = RecordingTransport(
            WeReadEinkResponse(200, """{"title":"广州海珠发布"}"""),
        )
        val client = client(transport)

        val account = client.resolveByUrl(
            "https://mp.weixin.qq.com/s?__biz=${fakeid("3295180586")}&mid=1",
        )

        assertNotNull(account)
        assertEquals(fakeid("3295180586"), account.fakeid)
        assertEquals("/book/info", transport.requests.single().url.encodedPath)
    }

    @Test
    fun `followed accounts merge multiple read sources and deduplicate`() {
        val transport = RecordingTransport(
            WeReadEinkResponse(200, """{"lists":[{"bookInfo":{"bookId":"MP_WXS_1","title":"A"}}]}"""),
            WeReadEinkResponse(200, """{"lists":[{"bookInfo":{"bookId":"MP_WXS_1","title":"A"}}]}"""),
            WeReadEinkResponse(200, """{"books":[{"bookId":"MP_WXS_1","title":"A"},{"bookId":"MP_WXS_2","title":"B"}]}"""),
            WeReadEinkResponse(200, """{"books":[{"bookInfo":{"bookId":"MP_WXS_3","title":"C"}}]}"""),
        )
        val client = client(transport)

        val accounts = client.listFollowedAccounts()

        assertEquals(listOf("MP_WXS_1", "MP_WXS_2", "MP_WXS_3"), accounts.map { it.bookId })
        assertTrue(transport.requests.any { it.url.encodedPath == "/shelf/sync" })
        assertTrue(transport.requests.any { it.url.encodedPath == "/subscription/books" })
    }

    @Test
    fun `terminal activation posts mobileSync and primes shelf and mp list`() {
        val transport = RecordingTransport(
            WeReadEinkResponse(200, "{}"),
            WeReadEinkResponse(200, "{}"),
            WeReadEinkResponse(200, "{}"),
        )
        val client = client(transport)

        assertTrue(client.activateTerminalSession())

        assertEquals("POST", transport.requests[0].method)
        assertEquals("/mobileSync", transport.requests[0].url.encodedPath)
        assertTrue(transport.requests[0].body.orEmpty().contains("\"preferTab\":1"))
        assertEquals("/shelf/sync", transport.requests[1].url.encodedPath)
        assertEquals("/mp/list", transport.requests[2].url.encodedPath)
    }

    private fun client(transport: WeReadEinkTransport): WeReadDirectoryClient =
        WeReadDirectoryClient(
            baseUrl = "https://eink.example.test",
            auth = object : WeReadDirectoryAuth {
                override fun vid(): String = "vid-1"
                override fun headers(): Map<String, String> = mapOf("vid" to "vid-1")
            },
            transport = transport,
            requestGate = { _, block -> block() },
        )

    private fun fakeid(uin: String): String =
        Base64.getEncoder().encodeToString(uin.toByteArray(Charsets.UTF_8))

    private class RecordingTransport(
        vararg responses: WeReadEinkResponse,
    ) : WeReadEinkTransport {
        private val responses = ArrayDeque(responses.toList())
        val requests = mutableListOf<WeReadEinkRequest>()

        override fun execute(request: WeReadEinkRequest): WeReadEinkResponse {
            requests += request
            return responses.removeFirstOrNull() ?: error("unexpected request: ${request.url}")
        }
    }
}
