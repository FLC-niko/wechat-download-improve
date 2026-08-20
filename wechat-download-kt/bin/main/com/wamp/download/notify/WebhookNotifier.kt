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
    )

    private fun enabled(): Boolean =
        AppConfig.wecomWebhookUrl.isNotEmpty() || AppConfig.feishuWebhookUrl.isNotEmpty()

    /**
     * 发送文字通知
     */
    suspend fun notify(event: String, data: Map<String, Any?>): Boolean {
        if (!enabled()) return false

        // 防重复：同一事件在间隔内只通知一次
        val now = System.currentTimeMillis() / 1000
        val last = lastNotification[event] ?: 0
        if (now - last < AppConfig.webhookNotificationInterval) {
            return false
        }
        lastNotification[event] = now

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

        var success = true

        // 企业微信
        val wecomUrl = AppConfig.wecomWebhookUrl
        if (wecomUrl.isNotEmpty()) {
            success = sendWecomMarkdown(wecomUrl, lines) && success
        }

        // 飞书
        val feishuUrl = AppConfig.feishuWebhookUrl
        if (feishuUrl.isNotEmpty()) {
            success = sendFeishuCard(feishuUrl, label, lines) && success
        }

        if (success) logger.info("通知已发送: $event")
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
    suspend fun pushQrcode(imageBytes: ByteArray, message: String = "") {
        val wecomUrl = AppConfig.wecomWebhookUrl
        val feishuUrl = AppConfig.feishuWebhookUrl

        if (wecomUrl.isEmpty() && feishuUrl.isEmpty()) {
            logger.warn("未配置通知机器人 Webhook，二维码仅保存到本地")
            return
        }

        // 企业微信：直接推图片
        if (wecomUrl.isNotEmpty()) {
            try {
                sendWecomImage(wecomUrl, imageBytes)
                logger.info("二维码已推送到企业微信")
            } catch (e: Exception) {
                logger.error("企业微信推送二维码失败: ${e.message}")
            }
        }

        // 飞书：推卡片消息（含二维码访问链接）
        if (feishuUrl.isNotEmpty()) {
            try {
                val qrUrl = "${AppConfig.siteUrl}/qrcode/wx_qrcode.png"
                val cardText = buildString {
                    appendLine("**请扫码登录公众号后台**")
                    if (message.isNotEmpty()) appendLine(message)
                    appendLine()
                    appendLine("[点击查看二维码]($qrUrl)")
                    appendLine()
                    appendLine("> 如果无法点击，请复制以下链接到浏览器：")
                    appendLine("> $qrUrl")
                }
                sendFeishuCard(feishuUrl, "登录二维码", cardText)
                logger.info("二维码已推送到飞书")
            } catch (e: Exception) {
                logger.error("飞书推送二维码失败: ${e.message}")
            }
        }

        // 标记通知时间
        lastNotification["qr_code_pushed"] = System.currentTimeMillis() / 1000
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
