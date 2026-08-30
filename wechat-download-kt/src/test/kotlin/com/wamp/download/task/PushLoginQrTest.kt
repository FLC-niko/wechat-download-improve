package com.wamp.download.task

import com.wamp.download.weread.WeReadAccountPool
import com.wamp.download.weread.WeReadWebAuth
import com.wamp.download.config.AppConfig
import com.wamp.download.store.Database
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.client.j2se.MatrixToImageWriter
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Disabled
import java.io.File
import java.nio.file.FileSystems
import kotlin.test.assertTrue

@Disabled("需要真实 ADB 设备，仅供人工验收")
class PushLoginQrTest {

    private val deviceId = "192.168.124.13:38711"

    private fun adb(cmd: String): String {
        val fullCmd = "adb -s $deviceId $cmd"
        println("[ADB] > $fullCmd")
        val proc = ProcessBuilder("sh", "-c", fullCmd).start()
        val text = proc.inputStream.bufferedReader().readText()
        proc.waitFor()
        return text.trim()
    }

    @Test
    fun `生成最新微信读书二维码并持久化推送到手机各相册目录`() {
        AppConfig.load()
        Database.init()
        WeReadAccountPool.loadAll()

        println("===== [1] 获取最新微信读书登录二维码 =====")
        val loginPair = WeReadWebAuth.createLogin()
        assertTrue(loginPair != null, "获取登录二维码失败")
        val (uid, scanUrl) = loginPair
        println("UID: $uid")
        println("URL: $scanUrl")

        println("===== [2] 生成高清二维码图片 =====")
        val qrDir = File("qrcode").apply { mkdirs() }
        val qrFile = File(qrDir, "weread_qr.png")
        val writer = QRCodeWriter()
        val bitMatrix = writer.encode(scanUrl, BarcodeFormat.QR_CODE, 600, 600)
        MatrixToImageWriter.writeToPath(bitMatrix, "PNG", FileSystems.getDefault().getPath(qrFile.absolutePath))

        println("===== [3] 推送到手机相册所有常用目录 =====")
        val targets = listOf(
            "/sdcard/DCIM/Camera/weread_qr.png",
            "/sdcard/Pictures/WeiXin/weread_qr.png",
            "/sdcard/Pictures/weread_qr.png",
            "/sdcard/Pictures/QRCode/weread_qr.png",
            "/sdcard/Android/media/com.topviewclub.crawling/com.topviewclub.crawling/QRCode/weread_qr.png"
        )

        for (target in targets) {
            val parent = File(target).parent
            adb("shell mkdir -p $parent")
            adb("push ${qrFile.absolutePath} $target")
            // 广播通知图库
            adb("shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file://$target")
        }

        println("===== [4] 使用 Shortcut 唤起微信扫一扫 =====")
        adb("shell am start -n com.tencent.mm/.ui.LauncherUI --ez \"LauncherUI.From.Scaner.Shortcut\" true -a android.intent.action.VIEW")

        println("\n=======================================================")
        println(">>> 二维码已同时写入手机 Camera / WeiXin / Pictures 目录！")
        println(">>> 现在请在微信扫一扫右下角点击【相册】，此时最顶部第一张即可看到该二维码！")
        println(">>> 选中二维码并点击【确认登录】")
        println("=======================================================\n")

        println("===== [5] 启动 120 秒长轮询捕获登录结果 =====")
        val success = WeReadWebAuth.pollLogin(uid, timeoutSeconds = 120)
        println("长轮询最终结果: $success")

        if (success) {
            WeReadAccountPool.loadAll()
            val acc = WeReadAccountPool.primaryAccount()
            println("恭喜！新凭证已成功入库: vid=${acc?.vid}, wr_skey=${acc?.credentials?.wrSkey}, status=${acc?.status}")
        }
    }
}
