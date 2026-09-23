package com.wamp.download.weread.eink

import com.wamp.download.config.AppConfig
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.util.Base64
import javax.imageio.ImageIO

enum class WeReadEinkVerificationState {
    IDLE,
    WAITING,
    VERIFYING,
    SUCCEEDED,
    FAILED,
    EXPIRED,
}

data class WeReadEinkVerificationStatus(
    val state: WeReadEinkVerificationState,
    val verifyUrl: String? = null,
    val createdAt: Long? = null,
    val expiresAt: Long? = null,
    val lastError: String? = null,
)

data class WeReadEinkVerificationResult(
    val ok: Boolean,
    val code: String,
    val message: String,
    val nextUrl: String? = null,
)

/**
 * Holds the Tencent CAPTCHA result only for the duration of one authenticated recovery probe.
 * The ticket and randstr are deliberately never exposed by status(), logged, or persisted.
 */
internal class WeReadEinkHumanVerificationCoordinator(
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val clock: () -> Long = System::currentTimeMillis,
    private val nonceGenerator: () -> String = ::secureNonce,
    private val probe: () -> Boolean,
) {
    private data class Challenge(
        val nonce: String,
        val createdAt: Long,
        val expiresAt: Long,
        var state: WeReadEinkVerificationState = WeReadEinkVerificationState.WAITING,
        var lastError: String? = null,
    )

    private val lock = Any()
    private var challenge: Challenge? = null
    private var transientHeaders: Map<String, String> = emptyMap()

    init {
        require(ttlMs > 0L) { "ttlMs must be positive" }
    }

    fun createOrReuse(siteUrl: String): WeReadEinkVerificationStatus = synchronized(lock) {
        expireIfNeeded()
        val current = challenge
        val active = if (current != null && current.state == WeReadEinkVerificationState.WAITING) {
            current
        } else {
            val now = clock()
            Challenge(
                nonce = nonceGenerator(),
                createdAt = now,
                expiresAt = now + ttlMs,
            ).also { challenge = it }
        }
        active.toStatus(siteUrl)
    }

    fun status(siteUrl: String): WeReadEinkVerificationStatus = synchronized(lock) {
        expireIfNeeded()
        challenge?.toStatus(siteUrl) ?: WeReadEinkVerificationStatus(WeReadEinkVerificationState.IDLE)
    }

    fun canOpen(nonce: String): Boolean = synchronized(lock) {
        expireIfNeeded()
        challenge?.let { it.nonce == nonce && it.state == WeReadEinkVerificationState.WAITING } == true
    }

    fun requestHeaders(): Map<String, String> = synchronized(lock) {
        if (challenge?.state == WeReadEinkVerificationState.VERIFYING) transientHeaders else emptyMap()
    }

    fun complete(nonce: String, ticket: String?, randstr: String?): WeReadEinkVerificationResult {
        if (!validValue(ticket, MAX_TICKET_LENGTH) || !validValue(randstr, MAX_RANDSTR_LENGTH)) {
            return WeReadEinkVerificationResult(
                ok = false,
                code = "INVALID_CAPTCHA_RESULT",
                message = "验证码结果无效，请刷新页面后重试",
            )
        }

        synchronized(lock) {
            expireIfNeeded()
            val current = challenge
                ?: return result(false, "NOT_FOUND", "验证链接不存在或已失效")
            if (current.nonce != nonce) {
                return result(false, "NOT_FOUND", "验证链接不存在或已失效")
            }
            if (current.state == WeReadEinkVerificationState.EXPIRED) {
                return result(false, "EXPIRED", "验证链接已过期，请使用最新链接")
            }
            if (current.state != WeReadEinkVerificationState.WAITING) {
                return result(false, "ALREADY_USED", "该验证链接已经提交，请勿重复使用")
            }
            current.state = WeReadEinkVerificationState.VERIFYING
            current.lastError = null
            transientHeaders = mapOf(
                "wr_ticket" to ticket!!,
                "wr_randstr" to randstr!!,
            )
        }

        val probeSucceeded = try {
            probe()
        } catch (_: Exception) {
            false
        } finally {
            synchronized(lock) { transientHeaders = emptyMap() }
        }

        return synchronized(lock) {
            val current = challenge
            if (current == null || current.nonce != nonce) {
                return@synchronized result(false, "SESSION_CHANGED", "E-Ink 会话已变化，请使用最新验证链接")
            }
            if (probeSucceeded) {
                current.state = WeReadEinkVerificationState.SUCCEEDED
                current.lastError = null
                result(true, "RECOVERED", "验证成功，E-Ink 通道已恢复")
            } else {
                current.state = WeReadEinkVerificationState.FAILED
                current.lastError = "恢复探测未通过"
                result(false, "PROBE_FAILED", "验证码已提交，但 E-Ink 恢复探测未通过，请获取新链接重试")
            }
        }
    }

    fun reset() = synchronized(lock) {
        transientHeaders = emptyMap()
        challenge = null
    }

    private fun expireIfNeeded() {
        val current = challenge ?: return
        if (current.state == WeReadEinkVerificationState.WAITING && clock() >= current.expiresAt) {
            current.state = WeReadEinkVerificationState.EXPIRED
            current.lastError = "验证链接已过期"
            transientHeaders = emptyMap()
        }
    }

    private fun Challenge.toStatus(siteUrl: String): WeReadEinkVerificationStatus {
        val publicUrl = if (state == WeReadEinkVerificationState.WAITING) {
            "${siteUrl.trimEnd('/')}/weread/eink/verify/$nonce"
        } else {
            null
        }
        return WeReadEinkVerificationStatus(
            state = state,
            verifyUrl = publicUrl,
            createdAt = createdAt,
            expiresAt = expiresAt,
            lastError = lastError,
        )
    }

    private fun validValue(value: String?, maxLength: Int): Boolean =
        value != null && value.isNotBlank() && value.length <= maxLength

    private fun result(ok: Boolean, code: String, message: String) =
        WeReadEinkVerificationResult(ok = ok, code = code, message = message)

    private companion object {
        const val DEFAULT_TTL_MS = 5 * 60 * 1000L
        const val MAX_TICKET_LENGTH = 4096
        const val MAX_RANDSTR_LENGTH = 1024

        fun secureNonce(): String {
            val bytes = ByteArray(32)
            SecureRandom().nextBytes(bytes)
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }
    }
}

object WeReadEinkHumanVerification {
    private val coordinator = WeReadEinkHumanVerificationCoordinator(probe = WeReadEinkRuntime::probe)

    fun createOrReuse(siteUrl: String): WeReadEinkVerificationStatus = coordinator.createOrReuse(siteUrl)

    fun status(siteUrl: String): WeReadEinkVerificationStatus = coordinator.status(siteUrl)

    fun canOpen(nonce: String): Boolean = coordinator.canOpen(nonce)

    fun complete(nonce: String, ticket: String?, randstr: String?): WeReadEinkVerificationResult {
        val result = coordinator.complete(nonce, ticket, randstr)
        if (result.code != "PROBE_FAILED") return result
        val replacement = coordinator.createOrReuse(AppConfig.siteUrl)
        WeReadEinkRuntime.allowHumanVerificationNotificationRetry()
        return result.copy(nextUrl = replacement.verifyUrl)
    }

    fun requestHeaders(): Map<String, String> = coordinator.requestHeaders()

    fun generateQrPng(url: String, size: Int = 600): ByteArray {
        require(url.isNotBlank()) { "url must not be blank" }
        require(size in 200..1200) { "size must be between 200 and 1200" }
        val hints = mapOf(
            EncodeHintType.CHARACTER_SET to "UTF-8",
            EncodeHintType.MARGIN to 2,
        )
        val matrix = QRCodeWriter().encode(url, BarcodeFormat.QR_CODE, size, size, hints)
        val image = BufferedImage(size, size, BufferedImage.TYPE_INT_RGB)
        for (x in 0 until size) {
            for (y in 0 until size) {
                image.setRGB(x, y, if (matrix[x, y]) 0x000000 else 0xFFFFFF)
            }
        }
        return ByteArrayOutputStream().use { output ->
            check(ImageIO.write(image, "png", output)) { "PNG encoder unavailable" }
            output.toByteArray()
        }
    }

    fun reset() = coordinator.reset()
}
