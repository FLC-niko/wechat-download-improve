package com.wamp.download.weread

import kotlin.test.Test
import kotlin.test.assertEquals

class WeReadWebResponseClassifierTest {

    @Test
    fun `结构化识别认证失效错误码`() {
        val result = WeReadWebResponseClassifier.classify(200, "{\"errCode\": -2012, \"errMsg\": \"expired\"}")

        assertEquals(WeReadWebResponseKind.AUTH_EXPIRED, result.kind)
        assertEquals(-2012, result.errCode)
        assertEquals("expired", result.errMsg)
    }

    @Test
    fun `区分人工验证和 HTTP 频控`() {
        val humanVerify = WeReadWebResponseClassifier.classify(200, "{\"errCode\":-2041}")
        val rateLimited = WeReadWebResponseClassifier.classify(429, "{\"message\":\"too many requests\"}")
        val businessRateLimited = WeReadWebResponseClassifier.classify(200, "{\"errCode\":-2014}")

        assertEquals(WeReadWebResponseKind.HUMAN_VERIFY, humanVerify.kind)
        assertEquals(WeReadWebResponseKind.RATE_LIMITED, rateLimited.kind)
        assertEquals(WeReadWebResponseKind.RATE_LIMITED, businessRateLimited.kind)
    }

    @Test
    fun `识别可重试 HTTP 错误和普通 HTTP 错误`() {
        val serverError = WeReadWebResponseClassifier.classify(503, "service unavailable")
        val clientError = WeReadWebResponseClassifier.classify(400, "bad request")

        assertEquals(WeReadWebResponseKind.RETRYABLE_HTTP_ERROR, serverError.kind)
        assertEquals(WeReadWebResponseKind.HTTP_ERROR, clientError.kind)
    }

    @Test
    fun `允许错误码字段顺序和空白变化`() {
        val result = WeReadWebResponseClassifier.classify(
            200,
            "{ \"data\": {}, \"errMsg\": \"expired\", \"errCode\" : -2012 }",
        )

        assertEquals(WeReadWebResponseKind.AUTH_EXPIRED, result.kind)
    }

    @Test
    fun `成功空对象仍然是成功响应`() {
        val result = WeReadWebResponseClassifier.classify(200, "{\"reviews\":[]}")

        assertEquals(WeReadWebResponseKind.SUCCESS, result.kind)
    }

    @Test
    fun `未知非零业务错误不能当作成功`() {
        val result = WeReadWebResponseClassifier.classify(200, "{\"errCode\":-2999}")

        assertEquals(WeReadWebResponseKind.APPLICATION_ERROR, result.kind)
    }
}
