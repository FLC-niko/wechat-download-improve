package com.wamp.download.article

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.wamp.download.model.QrImageRef
import com.wamp.download.store.ArticleStore
import com.wamp.download.store.SubscriptionStore
import org.slf4j.LoggerFactory
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.Locale
import javax.imageio.ImageIO

/**
 * 历史文章二维码服务。
 *
 * 针对微信高频扫描公众号名片码触发“操作太过于频繁，请稍后再试”的风控拦截，
 * 提供将已采集的历史文章转化为二维码的能力。扫码进入文章后通过点击作者头像跳转公众号，
 * 彻底绕过公众号名片码的风控限制。
 */
object ArticleQrService {

    private val logger = LoggerFactory.getLogger(ArticleQrService::class.java)

    /**
     * 根据 fakeid 查询最新可用文章链接
     */
    fun getLatestArticleUrlByFakeid(fakeid: String): String? {
        if (fakeid.isBlank()) return null
        return ArticleStore.sampleLink(fakeid)
    }

    /**
     * 根据公众号名称、别名或 fakeid 查询最新可用文章链接
     */
    fun getLatestArticleUrlByGzh(nameOrFakeid: String): String? {
        val trimmed = nameOrFakeid.trim()
        if (trimmed.isBlank()) return null

        // 1. 先尝试直接根据 fakeid 查找
        val byFakeid = ArticleStore.sampleLink(trimmed)
        if (!byFakeid.isNullOrBlank()) {
            return byFakeid
        }

        // 2. 按精准昵称查找订阅
        val sub = SubscriptionStore.findByNickname(trimmed)
        if (sub != null) {
            val link = ArticleStore.sampleLink(sub.fakeid)
            if (!link.isNullOrBlank()) return link
        }

        // 3. 按关键字模糊搜索订阅
        val candidates = SubscriptionStore.list(kw = trimmed, limit = 5).first
        for (candidate in candidates) {
            val link = ArticleStore.sampleLink(candidate.fakeid)
            if (!link.isNullOrBlank()) return link
        }

        return null
    }

    /**
     * 生成标准二维码字节流
     */
    fun generateQrImageBytes(
        content: String,
        size: Int = 600,
        format: String = "JPEG"
    ): ByteArray {
        val writer = QRCodeWriter()
        val hints = mapOf(
            EncodeHintType.CHARACTER_SET to "UTF-8",
            EncodeHintType.MARGIN to 1,
        )
        val bitMatrix = writer.encode(content, BarcodeFormat.QR_CODE, size, size, hints)
        val image = BufferedImage(size, size, BufferedImage.TYPE_INT_RGB)
        for (x in 0 until size) {
            for (y in 0 until size) {
                image.setRGB(x, y, if (bitMatrix.get(x, y)) Color.BLACK.rgb else Color.WHITE.rgb)
            }
        }
        val baos = ByteArrayOutputStream()
        ImageIO.write(image, format, baos)
        return baos.toByteArray()
    }

    /**
     * 将文章链接生成为符合 V2 协议标准的 QrImageRef (INLINE_BASE64)
     */
    fun generateArticleQrRef(articleUrl: String, size: Int = 600): QrImageRef {
        val bytes = generateQrImageBytes(articleUrl, size, "JPEG")
        val base64 = Base64.getEncoder().encodeToString(bytes)
        val sha256 = MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(Locale.US, it.toInt() and 0xff) }

        return QrImageRef(
            transport = "INLINE_BASE64",
            dataBase64 = base64,
            objectKey = null,
            sha256 = sha256,
            contentType = "image/jpeg",
            sizeBytes = bytes.size.toLong(),
        )
    }

    /**
     * 尝试根据公众号名称或 fakeid 生成文章二维码缓解引用。
     * 若本地尚无已采集文章，返回 null。
     */
    fun getOrGenerateArticleQr(nameOrFakeid: String): Pair<String, QrImageRef>? {
        val url = getLatestArticleUrlByGzh(nameOrFakeid) ?: return null
        return try {
            val qrRef = generateArticleQrRef(url)
            logger.info("成功为公众号 [$nameOrFakeid] 生成文章二维码: $url (sha256=${qrRef.sha256})")
            url to qrRef
        } catch (e: Exception) {
            logger.error("为公众号 [$nameOrFakeid] 生成文章二维码失败", e)
            null
        }
    }

    /**
     * 将失败/待转死信任务的载荷进行文章二维码增强。
     * 若任务的公众号在本地已采集过历史文章，将名片码置换为高清文章二维码，并填入 entryUrl。
     */
    fun enrichPayload(body: ByteArray): ByteArray {
        return try {
            val jsonStr = body.toString(Charsets.UTF_8)
            val element = JsonParser.parseString(jsonStr)
            if (!element.isJsonObject) return body
            val root = element.asJsonObject
            val payload = root.getAsJsonObject("payload") ?: return body
            val account = payload.getAsJsonObject("account") ?: return body
            val accountName = account.get("name")?.asString.orEmpty()
            if (accountName.isBlank()) return body

            val qrResult = getOrGenerateArticleQr(accountName)
            if (qrResult != null) {
                val (articleUrl, qrRef) = qrResult
                account.addProperty("entryUrl", articleUrl)
                account.add("qrImage", Gson().toJsonTree(qrRef))
                logger.info("【ArticleQrService】兜底转死信：成功为公众号 [$accountName] 置换为历史文章二维码 ($articleUrl)")
                root.toString().toByteArray(Charsets.UTF_8)
            } else {
                body
            }
        } catch (e: Exception) {
            logger.warn("【ArticleQrService】尝试置换文章二维码失败，维持原 payload", e)
            body
        }
    }
}
