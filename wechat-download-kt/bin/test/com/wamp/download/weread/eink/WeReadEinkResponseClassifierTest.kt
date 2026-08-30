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
}