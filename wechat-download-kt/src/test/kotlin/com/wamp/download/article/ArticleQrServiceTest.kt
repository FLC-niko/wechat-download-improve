package com.wamp.download.article

import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.client.j2se.BufferedImageLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.wamp.download.rabbitmq.RabbitMqClientManager
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.Locale
import javax.imageio.ImageIO

class ArticleQrServiceTest {

    @Test
    fun testGenerateArticleQrRef_DecodesCorrectly() {
        val testUrl = "https://mp.weixin.qq.com/s/Nyj2R3NKpCcZGO20GFpZFg"
        val qrRef = ArticleQrService.generateArticleQrRef(testUrl)

        assertEquals("INLINE_BASE64", qrRef.transport)
        assertEquals("image/jpeg", qrRef.contentType)
        assertNotNull(qrRef.dataBase64)

        // 验证 Base64 解码后的字节大小一致
        val bytes = Base64.getDecoder().decode(qrRef.dataBase64)
        assertEquals(qrRef.sizeBytes, bytes.size.toLong())

        // 验证 SHA-256 校验和正确
        val sha256 = MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(Locale.US, it.toInt() and 0xff) }
        assertEquals(qrRef.sha256, sha256)

        // 验证 ZXing 可以准确解码出原 URL
        val image = ImageIO.read(ByteArrayInputStream(bytes))
        assertNotNull(image)
        val source = BufferedImageLuminanceSource(image)
        val bitmap = BinaryBitmap(HybridBinarizer(source))
        val result = MultiFormatReader().decode(bitmap)
        assertEquals(testUrl, result.text)
    }

    @Test
    fun testEnrichBodyWithArticleQr_NoMatchKeepsOriginal() {
        val manager = RabbitMqClientManager()
        val originalJson = """
            {
              "schemaVersion": "archive.rabbit.v2",
              "payload": {
                "account": {
                  "name": "NonExistentAccount999",
                  "qrImage": {
                    "objectKey": "card-qr-key",
                    "sha256": "abc",
                    "contentType": "image/jpeg",
                    "sizeBytes": 123
                  }
                }
              }
            }
        """.trimIndent()

        val bytes = originalJson.toByteArray(Charsets.UTF_8)
        val enriched = manager.enrichBodyWithArticleQr(bytes)
        assertEquals(originalJson, enriched.toString(Charsets.UTF_8))
    }
}
