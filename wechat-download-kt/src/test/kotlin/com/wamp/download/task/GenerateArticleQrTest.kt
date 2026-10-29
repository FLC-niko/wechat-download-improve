package com.wamp.download.task

import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Path
import javax.imageio.ImageIO

class GenerateArticleQrTest {

    @Test
    fun generateArticleQr(@TempDir dir: Path) {
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

        val outFile = dir.resolve("article_qr.jpg").toFile()
        ImageIO.write(image, "JPEG", outFile)
        check(outFile.length() > 0) { "QR file was not written" }

        val img = ImageIO.read(outFile)
        val source = com.google.zxing.client.j2se.BufferedImageLuminanceSource(img)
        val bitmap = com.google.zxing.BinaryBitmap(com.google.zxing.common.HybridBinarizer(source))
        val decoded = com.google.zxing.MultiFormatReader().decode(bitmap)
        check(decoded.text == articleUrl) { "QR round-trip mismatch: ${decoded.text}" }
    }

    @Test
    fun decodePulledQr() {
        val file = File("/tmp/pulled_qr.jpg")
        Assumptions.assumeTrue(file.exists(), "manual QR artifact /tmp/pulled_qr.jpg not present")
        val img = ImageIO.read(file)
        val source = com.google.zxing.client.j2se.BufferedImageLuminanceSource(img)
        val bitmap = com.google.zxing.BinaryBitmap(com.google.zxing.common.HybridBinarizer(source))
        val result = com.google.zxing.MultiFormatReader().decode(bitmap)
        println("DECODED URL: ${result.text}")
    }
}
