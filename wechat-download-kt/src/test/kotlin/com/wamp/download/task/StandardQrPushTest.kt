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

@Disabled("需要真实 ADB 设备，仅供人工验收")
class StandardQrPushTest {

    private val deviceId = "192.168.124.13:38711"

    private fun adb(cmd: String): String {
        val fullCmd = "adb -s $deviceId $cmd"
        val proc = ProcessBuilder("sh", "-c", fullCmd).start()
        val text = proc.inputStream.bufferedReader().readText()
        proc.waitFor()
        return text.trim()
    }

    @Test
    fun `生成标准24位RGB位图并推送到手机相册`() {
        AppConfig.load()
        Database.init()
        WeReadAccountPool.loadAll()

        println("===== [1] 获取最新微信读书登录二维码 =====")
        val loginPair = WeReadWebAuth.createLogin()
        assertTrue(loginPair != null, "获取登录二维码失败")
        val (uid, scanUrl) = loginPair
        println("UID: $uid")
        println("URL: $scanUrl")

        println("===== [2] 生成标准 24-bit sRGB JPEG 图片 (确保 Android Gallery 100% 完美解析) =====")
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
        val jpgFile = File(qrDir, "weread_qr.jpg")
        ImageIO.write(image, "JPEG", jpgFile)
        println("标准 JPEG 二维码生成完成: ${jpgFile.absolutePath}, 大小=${jpgFile.length()} bytes")

        println("===== [3] Push 到手机 Camera 和 Pictures 目录 =====")
        val targets = listOf(
            "/sdcard/DCIM/Camera/weread_qr.jpg",
            "/sdcard/Pictures/WeiXin/weread_qr.jpg",
            "/sdcard/Pictures/weread_qr.jpg",
            "/sdcard/Pictures/Screenshots/weread_qr.jpg"
        )

        for (target in targets) {
            val parent = File(target).parent
            adb("shell mkdir -p $parent")
            adb("push ${jpgFile.absolutePath} $target")
            adb("shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file://$target")
        }

        println("===== [4] 使用官方 Shortcut 唤起微信扫一扫 =====")
        adb("shell am start -n com.tencent.mm/.ui.LauncherUI --ez \"LauncherUI.From.Scaner.Shortcut\" true -a android.intent.action.VIEW")

        println("\n=======================================================")
        println(">>> 标准 JPEG 二维码已成功推送到手机相册！")
        println(">>> 现在请在微信扫一扫界面右下角点击【相册】，此时第一张即可正常显示并选中该二维码！")
        println(">>> 选中二维码并点击【确认登录】")
        println("=======================================================\n")

        println("===== [5] 启动 120 秒长轮询等待手机授权 =====")
        val success = WeReadWebAuth.pollLogin(uid, timeoutSeconds = 120)
        println("长轮询最终结果: $success")

        if (success) {
            WeReadAccountPool.loadAll()
            val acc = WeReadAccountPool.primaryAccount()
            println("恭喜！新凭证已成功入库并激活: vid=${acc?.vid}, wr_skey=${acc?.credentials?.wrSkey}, status=${acc?.status}")
        }
    }
}
