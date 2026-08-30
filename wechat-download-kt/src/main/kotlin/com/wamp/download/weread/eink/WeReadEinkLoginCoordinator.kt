package com.wamp.download.weread.eink

import com.wamp.download.config.AppConfig
import com.wamp.download.notify.WebhookNotifier
import com.wamp.download.weread.WeReadWebAuth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** Runs one E-Ink QR login at a time and exposes a secret-free status snapshot. */
object WeReadEinkLoginCoordinator {
    private val logger = LoggerFactory.getLogger(WeReadEinkLoginCoordinator::class.java)
    private val inProgress = AtomicBoolean(false)

    @Volatile
    private var state: String = "IDLE"

    @Volatile
    private var lastError: String = ""

    @Volatile
    private var updatedAt: Long = 0L

    fun start(scope: CoroutineScope): Boolean {
        if (!AppConfig.wereadEinkEnabled) return false
        if (!inProgress.compareAndSet(false, true)) return false
        state = "STARTING"
        lastError = ""
        updatedAt = System.currentTimeMillis()
        scope.launch(Dispatchers.IO) {
            try {
                val client = WeReadEinkQrLoginClient(baseUrl = AppConfig.wereadEinkBaseUrl)
                val credentials = client.login(
                    onQr = { qr ->
                        state = "WAITING_SCAN"
                        updatedAt = System.currentTimeMillis()
                        val png = WeReadWebAuth.generateQrPng(qr.confirmUrl)
                            ?: throw WeReadEinkQrLoginException("failed to generate E-Ink QR image")
                        File("qrcode").mkdirs()
                        File("qrcode/weread_eink_qrcode.png").writeBytes(png)
                        runBlocking {
                            WebhookNotifier.pushQrcode(
                                imageBytes = png,
                                message = "请使用微信扫码登录微信读书 E-Ink 通道",
                                fileName = "weread_eink_qrcode.png",
                            )
                        }
                        logger.info("E-Ink 登录二维码已生成并等待扫码")
                    },
                    onStatus = { qrStatus ->
                        state = qrStatus.name
                        updatedAt = System.currentTimeMillis()
                    },
                )
                WeReadEinkRuntime.installCredentials(credentials)
                state = "SUCCEEDED"
                updatedAt = System.currentTimeMillis()
                logger.info("E-Ink 登录成功并已原子保存凭证: vid=${credentials.vid}")
            } catch (error: Exception) {
                state = "FAILED"
                lastError = error.message.orEmpty().take(500)
                updatedAt = System.currentTimeMillis()
                logger.error("E-Ink 登录失败: ${error.message}")
            } finally {
                inProgress.set(false)
            }
        }
        return true
    }

    fun status(): Map<String, Any?> = mapOf(
        "in_progress" to inProgress.get(),
        "state" to state,
        "last_error" to lastError.ifBlank { null },
        "updated_at" to updatedAt,
    )
}
