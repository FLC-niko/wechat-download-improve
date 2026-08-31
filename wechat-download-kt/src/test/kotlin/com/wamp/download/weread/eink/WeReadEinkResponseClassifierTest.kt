package com.wamp.download.weread.eink

import kotlin.test.Test
import kotlin.test.assertEquals

class WeReadEinkResponseClassifierTest {
    @Test
    fun `classifies session timeout rate limit and human verification separately`() {
        assertEquals(
            WeReadEinkResponseKind.AUTH_EXPIRED,
            WeReadEinkResponseClassifier.classify(WeReadEinkResponse(200, "{\"errCode\":-2012}")).kind,
        )
        assertEquals(
            WeReadEinkResponseKind.RATE_LIMITED,
            WeReadEinkResponseClassifier.classify(WeReadEinkResponse(200, "{\"errCode\":-2014}")).kind,
        )
        assertEquals(
            WeReadEinkResponseKind.HUMAN_VERIFY,
            WeReadEinkResponseClassifier.classify(WeReadEinkResponse(200, "{\"errCode\":-2041}")).kind,
        )
    }

    @Test
    fun `unknown nonzero application error is never treated as success`() {
        val result = WeReadEinkResponseClassifier.classify(
            WeReadEinkResponse(200, "{\"errCode\":-2999,\"errMsg\":\"new error\"}"),
        )

        assertEquals(WeReadEinkResponseKind.APPLICATION_ERROR, result.kind)
        assertEquals(-2999, result.errCode)
    }

    @Test
    fun `200 non-json body is retryable`() {
        assertEquals(
            WeReadEinkResponseKind.RETRYABLE_HTTP_ERROR,
            WeReadEinkResponseClassifier.classify(
                WeReadEinkResponse(200, "<html>upstream gateway</html>"),
            ).kind,
        )
    }
}
