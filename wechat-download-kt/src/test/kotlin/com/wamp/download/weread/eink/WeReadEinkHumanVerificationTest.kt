package com.wamp.download.weread.eink

import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.client.j2se.BufferedImageLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
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

        val first = coordinator.createOrReuse("vid-main", "https://service.example/")
        val reused = coordinator.createOrReuse("vid-main", "https://service.example")

        assertEquals("https://service.example/weread/eink/verify/vid-main/nonce-1", first.verifyUrl)
        assertEquals(first.verifyUrl, reused.verifyUrl)
        assertEquals(1_500L, first.expiresAt)

        now = 1_500L
        val expired = coordinator.status("vid-main", "https://service.example")
        assertEquals(WeReadEinkVerificationState.EXPIRED, expired.state)
        assertNull(expired.verifyUrl)

        val replacement = coordinator.createOrReuse("vid-main", "https://service.example")
        assertEquals("https://service.example/weread/eink/verify/vid-main/nonce-2", replacement.verifyUrl)
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
        coordinator.createOrReuse("vid-main", "https://service.example")

        val result = coordinator.complete("vid-main", "one-time", "secret-ticket", "secret-randstr")

        assertTrue(result.ok)
        assertEquals("RECOVERED", result.code)
        assertEquals("secret-ticket", observedHeaders["wr_ticket"])
        assertEquals("secret-randstr", observedHeaders["wr_randstr"])
        assertTrue(coordinator.requestHeaders().isEmpty())
        val statusText = coordinator.status("vid-main", "https://service.example").toString()
        assertFalse(statusText.contains("secret-ticket"))
        assertFalse(statusText.contains("secret-randstr"))

        val duplicate = coordinator.complete("vid-main", "one-time", "other-ticket", "other-randstr")
        assertFalse(duplicate.ok)
        assertEquals("ALREADY_USED", duplicate.code)
    }

    @Test
    fun `wrong nonce invalid values and failed probe do not leave headers installed`() {
        val coordinator = WeReadEinkHumanVerificationCoordinator(
            nonceGenerator = { "expected" },
            probe = { false },
        )
        coordinator.createOrReuse("vid-main", "https://service.example")

        assertEquals("NOT_FOUND", coordinator.complete("vid-main", "wrong", "ticket", "randstr").code)
        assertEquals("INVALID_CAPTCHA_RESULT", coordinator.complete("vid-main", "expected", "", "randstr").code)
        assertTrue(coordinator.canOpen("vid-main", "expected"))

        val failed = coordinator.complete("vid-main", "expected", "ticket", "randstr")
        assertFalse(failed.ok)
        assertEquals("PROBE_FAILED", failed.code)
        assertEquals(
            WeReadEinkVerificationState.FAILED,
            coordinator.status("vid-main", "https://service.example").state,
        )
        assertTrue(coordinator.requestHeaders().isEmpty())
    }

    @Test
    fun `accounts keep independent challenges and probes`() {
        var nonceNumber = 0
        val probed = mutableListOf<String>()
        val coordinator = WeReadEinkHumanVerificationCoordinator(
            nonceGenerator = { "nonce-${++nonceNumber}" },
            probe = { vid ->
                probed += vid
                true
            },
        )

        val first = coordinator.createOrReuse("vid-a", "https://service.example")
        val second = coordinator.createOrReuse("vid-b", "https://service.example")

        assertEquals("https://service.example/weread/eink/verify/vid-a/nonce-1", first.verifyUrl)
        assertEquals("https://service.example/weread/eink/verify/vid-b/nonce-2", second.verifyUrl)
        assertEquals("vid-a", coordinator.findVidByNonce("nonce-1"))
        assertEquals("vid-b", coordinator.findVidByNonce("nonce-2"))

        val completed = coordinator.complete("vid-a", "nonce-1", "ticket", "randstr")

        assertTrue(completed.ok)
        assertEquals(listOf("vid-a"), probed)
        assertEquals(
            WeReadEinkVerificationState.SUCCEEDED,
            coordinator.status("vid-a", "https://service.example").state,
        )
        // Completing vid-a must not consume or invalidate vid-b's challenge.
        assertEquals(
            WeReadEinkVerificationState.WAITING,
            coordinator.status("vid-b", "https://service.example").state,
        )
        assertTrue(coordinator.canOpen("vid-b", "nonce-2"))
        assertFalse(coordinator.canOpen("vid-a", "nonce-2"))
        assertEquals("NOT_FOUND", coordinator.complete("vid-b", "nonce-1", "ticket", "randstr").code)
        assertTrue(coordinator.canOpen("vid-b", "nonce-2"))
    }

    @Test
    fun `verification page uses official app id and nonce bound callback without credentials`() {
        val html = einkVerificationHtml("vid-safe", "safe-nonce")

        assertTrue(html.contains("https://ssl.captcha.qq.com/TCaptcha.js"))
        assertTrue(html.contains("TencentCaptcha('2044038556'"))
        assertTrue(html.contains("/api/weread/eink/verify/vid-safe/safe-nonce/complete"))
        assertTrue(html.contains("/api/weread/eink/verify/vid-safe/status"))
        assertTrue(html.contains("vid-safe"))
        assertFalse(html.contains("accessToken"))
        assertFalse(html.contains("refreshToken"))
    }

    @Test
    fun `expired and notice pages keep a per-account refresh entry`() {
        val expired = einkVerificationExpiredHtml(refreshPathForVid("vid-a"))
        assertTrue(expired.contains("/weread/eink/verify/vid-a/refresh"))

        val notice = einkVerificationNoticeHtml("处理中", "/weread/eink/verify/vid-b/refresh")
        assertTrue(notice.contains("/weread/eink/verify/vid-b/refresh"))
    }

    @Test
    fun `verification url is encoded into a scannable png`() {
        val url = "https://verify.example/weread/eink/verify/vid-a/nonce"
        val png = WeReadEinkHumanVerification.generateQrPng(url, size = 400)
        val image = ImageIO.read(ByteArrayInputStream(png))
        val decoded = MultiFormatReader().decode(
            BinaryBitmap(HybridBinarizer(BufferedImageLuminanceSource(image))),
        )

        assertEquals(url, decoded.text)
    }
}
