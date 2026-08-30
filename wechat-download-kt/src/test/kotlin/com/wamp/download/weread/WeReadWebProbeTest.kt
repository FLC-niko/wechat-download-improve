package com.wamp.download.weread

import kotlin.test.Test
import kotlin.test.assertEquals

class WeReadWebProbeTest {
    private val client = WeReadWebClient()

    @Test
    fun `probe response 200 with JSON body stays alive`() {
        assertEquals(
            WeReadWebResponseKind.SUCCESS,
            client.classifyProbeResponse(200, """{"books":[],"synckey":0}"""),
        )
    }

    @Test
    fun `probe response 200 with HTML login page is auth expired`() {
        assertEquals(
            WeReadWebResponseKind.AUTH_EXPIRED,
            client.classifyProbeResponse(200, "<html><body>login</body></html>"),
        )
    }

    @Test
    fun `probe response 401 or errCode -2012 is auth expired`() {
        assertEquals(WeReadWebResponseKind.AUTH_EXPIRED, client.classifyProbeResponse(401, ""))
        assertEquals(
            WeReadWebResponseKind.AUTH_EXPIRED,
            client.classifyProbeResponse(200, """{"errCode":-2012,"errMsg":"login timeout"}"""),
        )
    }

    @Test
    fun `probe response rate limited is not marked expired`() {
        assertEquals(
            WeReadWebResponseKind.RATE_LIMITED,
            client.classifyProbeResponse(200, """{"errCode":-2014,"errMsg":"frequent"}"""),
        )
    }
}
