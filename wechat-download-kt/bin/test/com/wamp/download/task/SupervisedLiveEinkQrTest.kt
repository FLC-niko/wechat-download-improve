package com.wamp.download.task

import com.wamp.download.weread.eink.WeReadEinkQrLoginClient
import com.wamp.download.weread.eink.WeReadEinkQrStatus
import com.wamp.download.config.AppConfig
import com.wamp.download.store.Database
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.client.j2se.MatrixToImageWriter
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.FileSystems
import kotlin.test.assertTrue

class SupervisedLiveEinkQrTest {

    private fun resolveDevice(): String {
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
    fun `在线拉取微信读书Eink官方二维码并全自动引导微信扫码`() {
        AppConfig.load()
        Database.init()

        println("==================================================")
        println(">>> [1/7] 请求全新微信读书 Eink 官方登录二维码 <<<")
        println("==================================================")
        val client = WeReadEinkQrLoginClient()
        val qrReq = client.requestQr()
        println("获得 Eink UUID: ${qrReq.uuid}")
        println("确认页 URL: ${qrReq.confirmUrl}")

        val qrDir = File("qrcode").apply { mkdirs() }
        val qrFile = File(qrDir, "weread_eink_live_qr.png")
        val writer = QRCodeWriter()
        val bitMatrix = writer.encode(qrReq.confirmUrl, BarcodeFormat.QR_CODE, 600, 600)
        MatrixToImageWriter.writeToPath(bitMatrix, "PNG", FileSystems.getDefault().getPath(qrFile.absolutePath))
        println("本地高清 Eink 二维码已就绪: ${qrFile.absolutePath}")

        println("==================================================")
        println(">>> [2/7] 物理级环境重置与旧图清空 <<<")
        println("==================================================")
        adb("shell input keyevent 224")
        adb("shell am start --user 0 -n com.tencent.mm/.ui.LauncherUI")
        Thread.sleep(800)

        val targetPath = "/sdcard/DCIM/Camera/weread_eink_${System.currentTimeMillis()}.png"
        adb("shell rm -f /sdcard/DCIM/Camera/weread_eink_*.png")
        Thread.sleep(500)

        println("==================================================")
        println(">>> [3/7] 投送全新 Eink 二维码并刷新媒体库 <<<")
        println("==================================================")
        adb("push ${qrFile.absolutePath} $targetPath")
        adb("shell am broadcast --user 0 -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file://$targetPath")
        Thread.sleep(1500)

        println("==================================================")
        println(">>> [4/7] 唤起微信扫一扫 (BaseScanUI) <<<")
        println("==================================================")
        adb("shell am start --user 0 -n com.tencent.mm/.ui.LauncherUI --ez \"LauncherUI.From.Scaner.Shortcut\" true -a android.intent.action.VIEW")
        Thread.sleep(1500)

        println("==================================================")
        println(">>> [5/7] 模拟点击进入相册选图页 (AlbumPreviewUI) <<<")
        println("==================================================")
        adb("shell input tap 956 2098")
        Thread.sleep(1500)

        println("==================================================")
        println(">>> [6/7] 自动选择最新拍摄/生成的二维码 (第1张图片, 405, 266) <<<")
        println("==================================================")
        adb("shell input tap 405 266")
        Thread.sleep(3500)

        println("==================================================")
        println(">>> [7/7] 自动点击【允许/同意/登录】按钮 (540, 2130 & 540, 1500) <<<")
        println("==================================================")
        adb("shell input tap 540 2130")
        Thread.sleep(500)
        adb("shell input tap 540 1500")

        var wxCode = ""
        val pollThread = Thread {
            println("[EinkPoll] 开始轮询微信扫码状态...")
            try {
                wxCode = client.pollForCode(qrReq.uuid, deadlineMs = 90_000L) { status ->
                    println("[EinkPoll] 当前状态: $status")
                    if (status == WeReadEinkQrStatus.CONFIRMED) {
                        println("[EinkPoll] 手机端已确认授权！")
                    }
                }
            } catch (e: Exception) {
                println("[EinkPoll] 轮询异常: ${e.message}")
            }
        }
        pollThread.start()
        pollThread.join(95_000L)

        // 阅后即焚清理临时二维码
        adb("shell rm -f $targetPath")
        adb("shell content delete --uri content://media/external/images/media --where \"_data='$targetPath'\"")
        qrFile.delete()

        assertTrue(wxCode.isNotBlank(), "未成功获取到微信授权码 wxCode")
        println(">>> [换票] 使用 wxCode 换取 Eink 凭证: $wxCode")
        val credentials = client.exchange(wxCode)
        println(">>> [成功] Eink 凭证获取成功: vid=${credentials.vid}, accessToken=${credentials.accessToken?.take(16)}...")
    }
}
