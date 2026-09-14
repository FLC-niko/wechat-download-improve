package com.wamp.download.task

import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import java.util.Locale
import javax.imageio.ImageIO

class GenerateArticleQrTest {

    @Test
    fun generateArticleQr() {
        val articleUrl = "https://mp.weixin.qq.com/s/Nyj2R3NKpCcZGO20GFpZFg"
        val size = 600
        val writer = QRCodeWriter()
        val bitMatrix = writer.encode(articleUrl, BarcodeFormat.QR_CODE, size, size)

        val image = BufferedImage(size, size, BufferedImage.TYPE_INT_RGB)
        for (x in 0 until size) {
            for (y in 0 until size) {
                image.setRGB(x, y, if (bitMatrix.get(x, y)) Color.BLACK.rgb else Color.WHITE.rgb)
            }
        }

        val outFile = File("/tmp/article_qr.jpg")
        ImageIO.write(image, "JPEG", outFile)
        println("Generated: ${outFile.absolutePath}, size=${outFile.length()}")

        val bytes = outFile.readBytes()
        val base64 = Base64.getEncoder().encodeToString(bytes)
        val sha256 = MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(Locale.US, it.toInt() and 0xff) }

        File("/tmp/article_qr_b64.txt").writeText(base64)
        File("/tmp/article_qr_sha256.txt").writeText(sha256)
        File("/tmp/article_qr_size.txt").writeText(bytes.size.toString())

        println("SHA256: $sha256")
        println("SizeBytes: ${bytes.size}")
        println("Base64Len: ${base64.length}")
    }

    @Test
    fun decodePulledQr() {
        val img = ImageIO.read(File("/tmp/pulled_qr.jpg"))
        val source = com.google.zxing.client.j2se.BufferedImageLuminanceSource(img)
        val bitmap = com.google.zxing.BinaryBitmap(com.google.zxing.common.HybridBinarizer(source))
        val result = com.google.zxing.MultiFormatReader().decode(bitmap)
        println("DECODED URL: ${result.text}")
    }
}
