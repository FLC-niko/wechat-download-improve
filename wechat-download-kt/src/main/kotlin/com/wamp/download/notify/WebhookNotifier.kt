package com.wamp.download.notify

import com.google.gson.Gson
import com.wamp.download.config.AppConfig
import kotlinx.coroutines.delay
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Webhook 通知模块 — 支持企业微信 + 飞书
 *
 * 对齐 Python 版 webhook.py + 参考 we-mp-rss 的 feishu.py / wechat.py
 *
 * 企业微信：支持 markdown 文字 + image 图片推送（base64+md5）
 * 飞书：支持 interactive 卡片消息（markdown 内容 + 二维码图片链接）
 */
object WebhookNotifier {
    data class QrPushResult(
        val wecomSuccess: Boolean,
        val feishuSuccess: Boolean,
    ) {
        val anySuccess: Boolean
            get() = wecomSuccess || feishuSuccess
    }

    private val logger = LoggerFactory.getLogger(WebhookNotifier::class.java)
    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val lastNotification = ConcurrentHashMap<String, Long>()

    private val eventLabels = mapOf(
        "login_success" to "登录成功",
        "login_failed" to "登录失败",
        "login_expired" to "登录过期",
        "login_expiring_soon" to "登录即将过期",
        "login_expiring_critical" to "登录即将过期（紧急）",
        "verification_required" to "触发验证",
        "qr_code_pushed" to "二维码已推送",
        "weread_eink_persistence_failed" to "微信读书 E-Ink 凭证落盘失败",
        "weread_eink_expired" to "微信读书 E-Ink 登录失效",
        "weread_eink_human_verify" to "微信读书 E-Ink 需要人工验证",
        "weread_eink_recovered" to "微信读书 E-Ink 登录恢复",
        "weread_accounts_expired" to "微信读书 Web 账号失效",
        "weread_account_recovered" to "微信读书 Web 账号恢复",
        "weread_account_recovery_failed" to "微信读书 Web 二维码恢复未完成",
        "weread_eink_recovery_failed" to "微信读书 E-Ink 二维码恢复未完成",
    )

    private fun enabled(): Boolean =
        (AppConfig.wecomEnabled && AppConfig.wecomWebhookUrl.isNotEmpty()) ||
            AppConfig.feishuWebhookUrl.isNotEmpty()

    /**
     * 发送文字通知
     */
    suspend fun notify(event: String, data: Map<String, Any?>): Boolean {
        if (!enabled()) return false

        // 防重复：同一事件在间隔内只通知一次
        val now = System.currentTimeMillis() / 1000
        val dedupKey = data["vid"]?.toString()?.takeIf { it.isNotBlank() }
            ?.let { "$event:$it" }
            ?: event
        val last = lastNotification[dedupKey] ?: 0
        if (now - last < AppConfig.webhookNotificationInterval) {
            return false
        }

        val label = eventLabels[event] ?: event
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))

        val lines = buildString {
            appendLine("**$label**")
            appendLine("> $timestamp")
            data.forEach { (k, v) ->
                if (v != null && v.toString().isNotEmpty()) {
                    appendLine("> $k: $v")
                }
            }
        }

        var success = false

        // 企业微信
        val wecomUrl = if (AppConfig.wecomEnabled) AppConfig.wecomWebhookUrl else ""
        if (wecomUrl.isNotEmpty()) {
            success = sendWecomMarkdown(wecomUrl, lines) || success
        }

        // 飞书
        val feishuUrl = AppConfig.feishuWebhookUrl
        if (feishuUrl.isNotEmpty()) {
            success = sendFeishuCard(feishuUrl, label, lines) || success
        }

        if (success) {
            lastNotification[dedupKey] = now
            logger.info("通知已发送: $event")
        }
        return success
    }

    /**
     * 推送二维码图片到通知机器人
     *
     * 企业微信：直接推 base64 图片
     * 飞书：推卡片消息，包含图片的 base64 data URI 或可访问 URL
     *
     * @param imageBytes 二维码图片字节数组
     * @param message 附加文字说明
     */
    suspend fun pushQrcode(
        imageBytes: ByteArray,
        message: String = "",
        fileName: String = "wx_qrcode.png",
        localFileAvailable: Boolean = true,
        title: String = "微信读书登录二维码",
        heading: String = "请扫码登录微信读书",
        targetUrl: String? = null,
    ): QrPushResult {
        val wecomUrl = if (AppConfig.wecomEnabled) AppConfig.wecomWebhookUrl else ""
        val feishuUrl = AppConfig.feishuWebhookUrl

        if (wecomUrl.isEmpty() && feishuUrl.isEmpty()) {
            logger.warn("未配置通知机器人 Webhook，二维码仅保存到本地")
            return QrPushResult(wecomSuccess = false, feishuSuccess = false)
        }

        // 企业微信：直接推图片
        val wecomSuccess = if (wecomUrl.isNotEmpty()) {
            try {
                val success = sendWecomImage(wecomUrl, imageBytes)
                if (success) {
                    logger.info("二维码已推送到企业微信")
                } else {
                    logger.error("企业微信推送二维码失败：Webhook 返回失败")
                }
                success
            } catch (e: Exception) {
                logger.error("企业微信推送二维码失败: ${e.message}")
                false
            }
        } else false

        // 飞书：推卡片消息（含二维码访问链接）
        val feishuSuccess = if (feishuUrl.isNotEmpty() && localFileAvailable) {
            try {
                val safeFileName = fileName.substringAfterLast('/').substringAfterLast('\\')
                val qrUrl = "${AppConfig.siteUrl}/qrcode/$safeFileName?v=${System.currentTimeMillis()}"
                val cardText = buildString {
                    appendLine("**$heading**")
                    if (message.isNotEmpty()) appendLine(message)
                    appendLine()
                    if (!targetUrl.isNullOrBlank()) {
                        appendLine("[直接打开验证页面]($targetUrl)")
                        appendLine()
                    }
                    appendLine("[点击查看二维码]($qrUrl)")
                }
                val success = sendFeishuCard(feishuUrl, title, cardText)
                if (success) {
                    logger.info("二维码已推送到飞书")
                } else {
                    logger.error("飞书推送二维码失败：Webhook 返回失败")
                }
                success
            } catch (e: Exception) {
                logger.error("飞书推送二维码失败: ${e.message}")
                false
            }
        } else {
            if (feishuUrl.isNotEmpty() && !localFileAvailable) {
                logger.error("飞书二维码推送跳过：本地二维码文件不可访问")
            }
            false
        }

        val result = QrPushResult(wecomSuccess, feishuSuccess)
        if (result.anySuccess) {
            lastNotification["qr_code_pushed"] = System.currentTimeMillis() / 1000
        } else {
            logger.error("二维码未成功推送到任何通知机器人")
        }
        return result
    }

    // ── 企业微信 ──────────────────────────────────

    private fun sendWecomMarkdown(webhookUrl: String, content: String): Boolean {
        val payload = mapOf(
            "msgtype" to "markdown",
            "markdown" to mapOf("content" to content)
        )
        return postWebhook(webhookUrl, payload, "wecom-markdown")
    }

    private fun sendWecomImage(webhookUrl: String, imageBytes: ByteArray): Boolean {
        val base64 = Base64.getEncoder().encodeToString(imageBytes)
        val md5 = MessageDigest.getInstance("MD5")
            .digest(imageBytes)
            .joinToString("") { "%02x".format(it) }

        val payload = mapOf(
            "msgtype" to "image",
            "image" to mapOf(
                "base64" to base64,
                "md5" to md5
            )
        )
        return postWebhook(webhookUrl, payload, "wecom-image")
    }

    // ── 飞书 ──────────────────────────────────

    /**
     * 发送飞书交互式卡片消息
     * 参考 we-mp-rss/core/notice/feishu.py
     */
    private fun sendFeishuCard(webhookUrl: String, title: String, content: String): Boolean {
        val payload = mapOf(
            "msg_type" to "interactive",
            "card" to mapOf(
                "config" to mapOf(
                    "wide_screen_mode" to true,
                    "enable_forward" to true
                ),
                "elements" to listOf(
                    mapOf(
                        "tag" to "div",
                        "text" to mapOf(
                            "content" to content,
                            "tag" to "lark_md"
                        )
                    )
                ),
                "header" to mapOf(
                    "template" to "blue",
                    "title" to mapOf(
                        "content" to title,
                        "tag" to "plain_text"
                    )
                )
            )
        )
        return postWebhook(webhookUrl, payload, "feishu-card")
    }

    // ── 通用 ──────────────────────────────────

    private fun postWebhook(url: String, payload: Map<String, Any>, tag: String): Boolean {
        return try {
            val json = gson.toJson(payload)
            val request = Request.Builder()
                .url(url)
                .post(json.toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(request).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    logger.error("[$tag] HTTP ${resp.code}: $body")
                    return false
                }
                // 检查企业微信 errcode
                if (body.contains("errcode")) {
                    val result = gson.fromJson(body, Map::class.java)
                    val errcode = (result?.get("errcode") as? Number)?.toInt() ?: 0
                    if (errcode != 0) {
                        logger.error("[$tag] errcode=$errcode: ${result?.get("errmsg")}")
                        return false
                    }
                }
                // 检查飞书 code
                if (body.contains("\"code\"")) {
                    val result = gson.fromJson(body, Map::class.java)
                    val code = (result?.get("code") as? Number)?.toInt() ?: 0
                    if (code != 0) {
                        logger.error("[$tag] code=$code: ${result?.get("msg")}")
                        return false
                    }
                }
                true
            }
        } catch (e: Exception) {
            logger.error("[$tag] 请求失败: ${e.message}")
            false
        }
    }
}
