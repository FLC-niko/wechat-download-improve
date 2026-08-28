package com.wamp.download.wx

import kotlin.test.Test
import kotlin.test.assertEquals

class WxApiClientTest {

    @Test
    fun `频控错误码不会被识别为登录过期`() {
        assertEquals(
            WxApiClient.TokenStatus.RATE_LIMITED,
            WxApiClient.classifyTokenFailure("200013", "frequency control"),
        )
        assertEquals(
            WxApiClient.TokenStatus.RATE_LIMITED,
            WxApiClient.classifyTokenFailure(null, "请求过于频繁，触发频控"),
        )
    }

    @Test
    fun `明确的登录失效才进入过期分类`() {
        assertEquals(
            WxApiClient.TokenStatus.EXPIRED,
            WxApiClient.classifyTokenFailure("200003", "登录超时，请重新登录"),
        )
        assertEquals(
            WxApiClient.TokenStatus.EXPIRED,
            WxApiClient.classifyTokenFailure("200040", "invalid csrf token"),
        )
        assertEquals(
            WxApiClient.TokenStatus.UNAVAILABLE,
            WxApiClient.classifyTokenFailure("-1", "network unavailable"),
        )
    }
}