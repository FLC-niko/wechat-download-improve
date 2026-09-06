package com.wamp.download.task

import com.wamp.download.weread.WeReadAccountPool
import com.wamp.download.weread.WeReadWebAuth
import com.wamp.download.config.AppConfig
import com.wamp.download.store.Database
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.client.j2se.MatrixToImageWriter
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.FileSystems
import kotlin.test.assertTrue

class SupervisedLiveQrTest {

    private fun resolveDevice(): String {
        // 优先使用 USB 物理有线连接 (serial: M7Z5HABUMJT8BUO7)，不依赖任何 WiFi/IP 局域网
        val proc = ProcessBuilder("sh", "-c", "adb devices | grep 'M7Z5HABUMJT8BUO7' | head -n1 | awk '{print \$1}'").start()
        val text = proc.inputStream.bufferedReader().readText().trim()
        proc.waitFor()
        if (text.isNotBlank()) return text
        return "M7Z5HABUMJT8BUO7"
    }

    private fun adb(cmd: String): String {
        val dev = resolveDevice()
        val fullCmd = "adb -s $dev $cmd"
        println("[ADB] > $fullCmd")
        val proc = ProcessBuilder("sh", "-c", fullCmd).start()
        val text = proc.inputStream.bufferedReader().readText()
        proc.waitFor()
        return text.trim()
    }

    @Test
    fun `在线拉取最新二维码并全自动引导微信扫码等待授权`() {
        AppConfig.load()
        Database.init()
        WeReadAccountPool.loadAll()

        println("==================================================")
        println(">>> [1/7] 请求全新微信读书 Web 登录二维码 <<<")
        println("==================================================")
        val loginPair = WeReadWebAuth.createLogin()
        assertTrue(loginPair != null, "获取登录二维码失败")
        val (uid, scanUrl) = loginPair
        println("获得专属登录 UUID: $uid")
        println("授权 URL: $scanUrl")

        val qrDir = File("qrcode").apply { mkdirs() }
        val qrFile = File(qrDir, "weread_live_qr.png")
        val writer = QRCodeWriter()
        val bitMatrix = writer.encode(scanUrl, BarcodeFormat.QR_CODE, 600, 600)
        MatrixToImageWriter.writeToPath(bitMatrix, "PNG", FileSystems.getDefault().getPath(qrFile.absolutePath))
        println("本地高清二维码图片已就绪: ${qrFile.absolutePath}")

        println("==================================================")
        println(">>> [2/7] 物理级环境重置与旧脏图清空 <<<")
        println("==================================================")
        // 唤醒屏幕
        adb("shell input keyevent 224")
        // 清空前台回退栈，回到微信根目录
        adb("shell am start --user 0 -n com.tencent.mm/.ui.LauncherUI")
        Thread.sleep(800)

        val targetPath = "/sdcard/DCIM/Camera/weread_live_qr.png"
        adb("shell rm -f $targetPath")
        adb("shell am broadcast --user 0 -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file://$targetPath")
        Thread.sleep(500)

        println("==================================================")
        println(">>> [3/7] 独立目录投送全新二维码并刷新媒体库 <<<")
        println("==================================================")
        adb("push ${qrFile.absolutePath} $targetPath")
        adb("shell am broadcast --user 0 -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file://$targetPath")
        Thread.sleep(1200)

        println("==================================================")
        println(">>> [4/7] 唤起微信扫一扫 (BaseScanUI) <<<")
        println("==================================================")
        adb("shell am start --user 0 -n com.tencent.mm/.ui.LauncherUI --ez \"LauncherUI.From.Scaner.Shortcut\" true -a android.intent.action.VIEW")
        Thread.sleep(1500)

        println("==================================================")
        println(">>> [5/7] 模拟点击进入相册选图页 (AlbumPreviewUI) <<<")
        println("==================================================")
        // 点击相册按钮坐标 (956, 2098)
        adb("shell input tap 956 2098")
        Thread.sleep(1500)

        println("==================================================")
        println(">>> [6/7] 自动选择第一张新鲜二维码 (首格图片) <<<")
        println("==================================================")
        // 首格相片坐标 (405, 266)
        adb("shell input tap 405 266")
        Thread.sleep(2500)

        println("==================================================")
        println(">>> [7/7] 手机屏幕已载入二维码！自动点击【登录】按钮并等待授权 <<<")
        println("==================================================")
        // 自动点击 MMWebViewUI 下方的【登录】蓝色按钮 (540, 2130)
        adb("shell input tap 540 2130")

        val success = WeReadWebAuth.pollLogin(uid, timeoutSeconds = 90)
        println("\n>>> 最终授权换票结果: $success <<<")

        // 无论成功与否，执行严格的后置阅后即焚清理
        println("=== 清理临时二维码图片 ===")
        adb("shell rm -f $targetPath")
        adb("shell am broadcast --user 0 -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file://$targetPath")
        qrFile.delete()

        if (success) {
            println("=== 账号池状态刷新 ===")
            WeReadAccountPool.loadAll()
            WeReadAccountPool.list().forEach {
                println(" -> 账号: vid=${it.vid}, username=${it.credentials.username}, status=${it.status}, skey=${it.credentials.wrSkey.take(16)}...")
            }
        }
        assertTrue(success, "微信扫码授权成功完成！")
    }
}
