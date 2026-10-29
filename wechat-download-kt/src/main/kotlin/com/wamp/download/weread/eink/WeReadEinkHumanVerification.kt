package com.wamp.download.weread.eink

import com.wamp.download.config.AppConfig
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
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
 *
 * 验证挑战按账号（vid）隔离：一个账号被封禁需要人工验证时，不会覆盖或影响其他账号的
 * 验证状态；验证链接也带 vid，才能把票据准确交给对应账号的探测。
 */
internal class WeReadEinkHumanVerificationCoordinator(
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val clock: () -> Long = System::currentTimeMillis,
    private val nonceGenerator: () -> String = ::secureNonce,
    private val probe: (String) -> Boolean,
    private val probeRetries: Int = 0,
    private val probeRetryDelayMs: Long = 15_000L,
) {
    private class Challenge(
        val vid: String,
        val nonce: String,
        val createdAt: Long,
        val expiresAt: Long,
        val evaluationId: String = UUID.randomUUID().toString(),
        var verificationStartedAt: Long? = null,
        var state: WeReadEinkVerificationState = WeReadEinkVerificationState.WAITING,
        var lastError: String? = null,
    )

    private val lock = Any()

    /** 票据是进程内单值：同一时刻只允许一个账号的验证探测持有，避免票据被并发消耗。 */
    private val completionLock = Any()

    private val challenges = LinkedHashMap<String, Challenge>()
    private val evaluationRecorder = WeReadEinkVerificationEvaluationRecorder()

    private var transientHeaders: Map<String, String> = emptyMap()

    init {
        require(ttlMs > 0L) { "ttlMs must be positive" }
    }

    fun createOrReuse(vid: String, siteUrl: String): WeReadEinkVerificationStatus = synchronized(lock) {
        require(vid.isNotBlank()) { "vid must not be blank" }
        expireIfNeededLocked(vid)
        val current = challenges[vid]
        val active = if (current != null && current.state == WeReadEinkVerificationState.WAITING) {
            current
        } else {
            val now = clock()
            Challenge(
                vid = vid,
                nonce = nonceGenerator(),
                createdAt = now,
                expiresAt = now + ttlMs,
            ).also {
                challenges[vid] = it
                recordEvaluationLocked(it, now)
            }
        }
        active.toStatus(siteUrl)
    }

    fun status(vid: String, siteUrl: String): WeReadEinkVerificationStatus = synchronized(lock) {
        expireIfNeededLocked(vid)
        challenges[vid]?.toStatus(siteUrl) ?: WeReadEinkVerificationStatus(WeReadEinkVerificationState.IDLE)
    }

    fun evaluation(vid: String): WeReadEinkVerificationEvaluationSnapshot = synchronized(lock) {
        expireIfNeededLocked(vid)
        evaluationRecorder.snapshot(vid, clock())
    }

    fun canOpen(vid: String, nonce: String): Boolean = synchronized(lock) {
        expireIfNeededLocked(vid)
        challenges[vid]?.let { it.nonce == nonce && it.state == WeReadEinkVerificationState.WAITING } == true
    }

    /** 兼容旧版不含 vid 的链接：按 nonce 反查账号。 */
    fun findVidByNonce(nonce: String): String? = synchronized(lock) {
        challenges.entries.firstOrNull { it.value.nonce == nonce }?.key
    }

    fun requestHeaders(): Map<String, String> = synchronized(lock) { transientHeaders }

    /** 供 Web 渠道把一次性验证码票据挂到当前线程的请求头上（重放原请求）。 */
    fun updateHeaders(headers: Map<String, String>) {
        synchronized(lock) { transientHeaders = headers }
    }

    fun complete(
        vid: String,
        nonce: String,
        ticket: String?,
        randstr: String?,
    ): WeReadEinkVerificationResult {
        if (!validValue(ticket, MAX_TICKET_LENGTH) || !validValue(randstr, MAX_RANDSTR_LENGTH)) {
            return result(false, "INVALID_CAPTCHA_RESULT", "验证码结果无效，请刷新页面后重试")
        }

        val verificationSessionId = synchronized(lock) {
            expireIfNeededLocked(vid)
            val current = challenges[vid]
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
            current.verificationStartedAt = clock()
            recordEvaluationLocked(current, current.verificationStartedAt!!)
            current.evaluationId
        }

        val probeSucceeded = try {
            // 串行化探测：票据为进程单值，同一时刻只允许一个账号的验证探测使用它。
            synchronized(completionLock) {
                val activeSession = synchronized(lock) {
                    val current = challenges[vid]
                    if (current?.evaluationId == verificationSessionId &&
                        current.state == WeReadEinkVerificationState.VERIFYING) {
                        transientHeaders = mapOf(
                            "wr_ticket" to ticket!!,
                            "wr_randstr" to randstr!!,
                        )
                        true
                    } else false
                }
                if (activeSession) {
                    try {
                        // 票据通过后账号状态有传播延迟：第一次带票据探测，之后无票据重试。
                        // 手动页面与自动解题保持一致，避免"做对了还让用户再做一题"。
                        var ok = probe(vid)
                        if (!ok) {
                            synchronized(lock) { transientHeaders = emptyMap() }
                            repeat(probeRetries.coerceAtLeast(0)) {
                                if (!ok) {
                                    try { Thread.sleep(probeRetryDelayMs.coerceAtLeast(0L)) } catch (_: InterruptedException) {
                                        Thread.currentThread().interrupt()
                                        return@synchronized false
                                    }
                                    ok = try { probe(vid) } catch (_: Exception) { false }
                                }
                            }
                        }
                        ok
                    } finally {
                        synchronized(lock) { transientHeaders = emptyMap() }
                    }
                } else false
            }
        } catch (_: Exception) {
            false
        }

        return synchronized(lock) {
            val current = challenges[vid]
            if (current == null || current.nonce != nonce || current.evaluationId != verificationSessionId) {
                return@synchronized result(false, "SESSION_CHANGED", "E-Ink 会话已变化，请使用最新验证链接")
            }
            if (probeSucceeded) {
                current.state = WeReadEinkVerificationState.SUCCEEDED
                current.lastError = null
                recordEvaluationLocked(current)
                result(true, "RECOVERED", "验证成功，E-Ink 通道已恢复")
            } else {
                current.state = WeReadEinkVerificationState.FAILED
                current.lastError = "恢复探测未通过"
                recordEvaluationLocked(current)
                result(false, "PROBE_FAILED", "验证码已提交，但 E-Ink 恢复探测未通过，请获取新链接重试")
            }
        }
    }

    fun reset() = synchronized(lock) {
        transientHeaders = emptyMap()
        challenges.clear()
        evaluationRecorder.reset()
    }

    private fun expireIfNeededLocked(vid: String) {
        val current = challenges[vid] ?: return
        if (current.state == WeReadEinkVerificationState.WAITING && clock() >= current.expiresAt) {
            current.state = WeReadEinkVerificationState.EXPIRED
            current.lastError = "验证链接已过期"
            recordEvaluationLocked(current)
        }
    }

    private fun recordEvaluationLocked(challenge: Challenge, at: Long = clock()) {
        evaluationRecorder.record(
            vid = challenge.vid,
            sessionId = challenge.evaluationId,
            state = challenge.state,
            createdAt = challenge.createdAt,
            verificationStartedAt = challenge.verificationStartedAt,
            at = at,
        )
    }

    private fun Challenge.toStatus(siteUrl: String): WeReadEinkVerificationStatus {
        val publicUrl = if (state == WeReadEinkVerificationState.WAITING) {
            "${siteUrl.trimEnd('/')}/weread/eink/verify/$vid/$nonce"
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
    private val manualProbeRetries =
        System.getProperty("eink.verify.probeRetries")?.toIntOrNull() ?: 2
    private val coordinator = WeReadEinkHumanVerificationCoordinator(
        probeRetries = manualProbeRetries,
        probe = { vid ->
            // 同一套验证码票据同时服务 Web（weread.qq.com）与 E-Ink（i.weread.qq.com）：
            // 有 Web 凭证的账号优先用 Web 探测，否则走 E-Ink。
            val webAccount = com.wamp.download.weread.WeReadAccountPool.get(vid)
            WeReadEinkRequestGate.asVerificationProbe {
                if (webAccount != null && webAccount.hasWebCredentials()) {
                    com.wamp.download.weread.WeReadWebClient()
                        .probeAccount(webAccount) == com.wamp.download.weread.WeReadWebResponseKind.SUCCESS
                } else {
                    WeReadEinkRuntime.probeAccount(vid)
                }
            }
        },
    )

    fun createOrReuse(vid: String, siteUrl: String): WeReadEinkVerificationStatus =
        coordinator.createOrReuse(vid, siteUrl)

    fun status(vid: String, siteUrl: String): WeReadEinkVerificationStatus =
        coordinator.status(vid, siteUrl)

    fun evaluation(vid: String): WeReadEinkVerificationEvaluationSnapshot = coordinator.evaluation(vid)

    fun evaluationForVidOrEmpty(vid: String?): WeReadEinkVerificationEvaluationSnapshot =
        coordinator.evaluation(vid.orEmpty())

    fun statusForVidOrIdle(vid: String?, siteUrl: String): WeReadEinkVerificationStatus =
        if (vid == null) {
            WeReadEinkVerificationStatus(WeReadEinkVerificationState.IDLE)
        } else {
            coordinator.status(vid, siteUrl)
        }

    fun canOpen(vid: String, nonce: String): Boolean = coordinator.canOpen(vid, nonce)

    fun findVidByNonce(nonce: String): String? = coordinator.findVidByNonce(nonce)

    /**
     * 把一次性验证码票据挂到当前线程的请求头上执行 block（Web 端 -2041 用
     * `x-wr-ticket`/`x-wr-randstr` 重放原请求）。票据只在 block 执行期间可见。
     */
    fun <T> withTicket(ticket: String, randstr: String, block: () -> T): T =
        WeReadEinkRequestGate.asVerificationProbe {
            coordinator.updateHeaders(mapOf("wr_ticket" to ticket, "wr_randstr" to randstr))
            try {
                block()
            } finally {
                coordinator.updateHeaders(emptyMap())
            }
        }

    fun complete(vid: String, nonce: String, ticket: String?, randstr: String?): WeReadEinkVerificationResult {
        val result = coordinator.complete(vid, nonce, ticket, randstr)
        if (result.ok && result.code == "RECOVERED") {
            val webAccount = com.wamp.download.weread.WeReadAccountPool.get(vid)
            if (webAccount != null && webAccount.hasWebCredentials()) {
                com.wamp.download.weread.WeReadAccountPool.clearHumanVerificationRequired(vid)
            }
        }
        if (result.code != "PROBE_FAILED") return result
        val replacement = coordinator.createOrReuse(vid, AppConfig.siteUrl)
        WeReadEinkRuntime.allowHumanVerificationNotificationRetry(vid)
        return result.copy(nextUrl = replacement.verifyUrl)
    }

    /**
     * Captcha ticket headers are one-shot and may be consumed by the first request that carries
     * them. They are exposed only to the verification probe thread so concurrent background sync
     * or task requests can never burn the ticket before the recovery probe uses it.
     */
    fun requestHeaders(): Map<String, String> =
        if (WeReadEinkRequestGate.isVerificationProbeRequest()) coordinator.requestHeaders() else emptyMap()

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
