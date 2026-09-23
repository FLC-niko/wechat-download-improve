package com.wamp.download.weread.eink

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WeReadEinkHumanVerificationTest {
    @Test
    fun `challenge is reused until expiry and status never exposes captcha values`() {
        var now = 1_000L
        var nonceNumber = 0
        val coordinator = WeReadEinkHumanVerificationCoordinator(
            ttlMs = 500L,
            clock = { now },
            nonceGenerator = { "nonce-${++nonceNumber}" },
            probe = { true },
        )

        val first = coordinator.createOrReuse("https://service.example/")
        val reused = coordinator.createOrReuse("https://service.example")

        assertEquals("https://service.example/weread/eink/verify/nonce-1", first.verifyUrl)
        assertEquals(first.verifyUrl, reused.verifyUrl)
        assertEquals(1_500L, first.expiresAt)

        now = 1_500L
        val expired = coordinator.status("https://service.example")
        assertEquals(WeReadEinkVerificationState.EXPIRED, expired.state)
        assertNull(expired.verifyUrl)

        val replacement = coordinator.createOrReuse("https://service.example")
        assertEquals("https://service.example/weread/eink/verify/nonce-2", replacement.verifyUrl)
    }

    @Test
    fun `captcha headers exist only during one successful probe`() {
        lateinit var coordinator: WeReadEinkHumanVerificationCoordinator
        var observedHeaders: Map<String, String> = emptyMap()
        coordinator = WeReadEinkHumanVerificationCoordinator(
            nonceGenerator = { "one-time" },
            probe = {
                observedHeaders = coordinator.requestHeaders()
                true
            },
        )
        coordinator.createOrReuse("https://service.example")

        val result = coordinator.complete("one-time", "secret-ticket", "secret-randstr")

        assertTrue(result.ok)
        assertEquals("RECOVERED", result.code)
        assertEquals("secret-ticket", observedHeaders["wr_ticket"])
        assertEquals("secret-randstr", observedHeaders["wr_randstr"])
        assertTrue(coordinator.requestHeaders().isEmpty())
        val statusText = coordinator.status("https://service.example").toString()
        assertFalse(statusText.contains("secret-ticket"))
        assertFalse(statusText.contains("secret-randstr"))

        val duplicate = coordinator.complete("one-time", "other-ticket", "other-randstr")
        assertFalse(duplicate.ok)
        assertEquals("ALREADY_USED", duplicate.code)
    }

    @Test
    fun `wrong nonce invalid values and failed probe do not leave headers installed`() {
        val coordinator = WeReadEinkHumanVerificationCoordinator(
            nonceGenerator = { "expected" },
            probe = { false },
        )
        coordinator.createOrReuse("https://service.example")

        assertEquals("NOT_FOUND", coordinator.complete("wrong", "ticket", "randstr").code)
        assertEquals("INVALID_CAPTCHA_RESULT", coordinator.complete("expected", "", "randstr").code)
        assertTrue(coordinator.canOpen("expected"))

        val failed = coordinator.complete("expected", "ticket", "randstr")
        assertFalse(failed.ok)
        assertEquals("PROBE_FAILED", failed.code)
        assertEquals(WeReadEinkVerificationState.FAILED, coordinator.status("https://service.example").state)
        assertTrue(coordinator.requestHeaders().isEmpty())
    }

    @Test
    fun `verification page uses official app id and nonce bound callback without credentials`() {
        val html = einkVerificationHtml("safe-nonce")

        assertTrue(html.contains("https://ssl.captcha.qq.com/TCaptcha.js"))
        assertTrue(html.contains("TencentCaptcha('2044038556'"))
        assertTrue(html.contains("/api/weread/eink/verify/safe-nonce/complete"))
        assertFalse(html.contains("accessToken"))
        assertFalse(html.contains("refreshToken"))
    }
}

