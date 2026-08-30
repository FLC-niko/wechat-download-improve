package com.wamp.download.task

import com.wamp.download.weread.WeReadAccountPool
import com.wamp.download.weread.WeReadWebAuth
import com.wamp.download.config.AppConfig
import com.wamp.download.store.Database
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Disabled
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.assertTrue

@Disabled("会操作本机 GUI，仅供人工验收")
class DirectScreenQrScanTest {

    @Test
    fun `生成最新微信读书二维码并弹出等待扫码入库`() {
        AppConfig.load()
        Database.init()
        WeReadAccountPool.loadAll()

        println("===== [1] 获取最新微信读书登录二维码 =====")
        val loginPair = WeReadWebAuth.createLogin()
        assertTrue(loginPair != null, "获取登录二维码失败")
        val (uid, scanUrl) = loginPair
        println("UID: $uid")
        println("URL: $scanUrl")

        println("===== [2] 生成大图二维码并在电脑端弹出 =====")
        val size = 600
        val writer = QRCodeWriter()
        val bitMatrix = writer.encode(scanUrl, BarcodeFormat.QR_CODE, size, size)

        val image = BufferedImage(size, size, BufferedImage.TYPE_INT_RGB)
        for (x in 0 until size) {
            for (y in 0 until size) {
                image.setRGB(x, y, if (bitMatrix.get(x, y)) Color.BLACK.rgb else Color.WHITE.rgb)
            }
        }

        val qrDir = File("qrcode").apply { mkdirs() }
        val jpgFile = File(qrDir, "weread_screen_qr.jpg")
        ImageIO.write(image, "JPEG", jpgFile)

        // 电脑端直接打开大图
        ProcessBuilder("open", jpgFile.absolutePath).start()

        println("\n=======================================================")
        println(">>> 电脑屏幕上已自动弹出最新的登录二维码大图！<<<")
        println(">>> 手机微信当前停留在【扫一扫】取景器，直接对准电脑屏幕扫描即可！<<<")
        println(">>> 扫码后在手机点击【确认登录】<<<")
        println("=======================================================\n")

        println("===== [3] 启动 120 秒长轮询等待授权 =====")
        val success = WeReadWebAuth.pollLogin(uid, timeoutSeconds = 120)
        println("长轮询最终结果: $success")

        if (success) {
            WeReadAccountPool.loadAll()
            val acc = WeReadAccountPool.primaryAccount()
            println("恭喜！新凭证已成功入库并激活: vid=${acc?.vid}, wr_skey=${acc?.credentials?.wrSkey}, status=${acc?.status}")
        }
    }
}
