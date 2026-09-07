package com.wamp.download.task

import com.wamp.download.adb.AdbDeviceSupervisor
import com.wamp.download.adb.AdbUserOperations
import com.wamp.download.config.AppConfig
import com.wamp.download.store.Database
import com.wamp.download.weread.WeReadAccountPool
import com.wamp.download.weread.WeReadWebAuth
import com.wamp.download.weread.WeReadWebClient
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.client.j2se.MatrixToImageWriter
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.FileSystems
import kotlin.test.assertTrue

@Disabled("需要真实 ADB 设备，仅供人工验收")
class SupervisedLiveQrTest {

    @Test
    fun `在线拉取最新二维码并全自动引导微信扫码等待授权`() {
        AppConfig.load()
        Database.init()
        WeReadAccountPool.loadAll()

        println("==================================================")
        println(">>> [1/6] 探查在线设备与槽位 <<<")
        println("==================================================")
        val supervisor = AdbDeviceSupervisor()
        val snapshot = supervisor.refresh()
        val slot = supervisor.availableSlots().firstOrNull { it.serial == "M7Z5HABUMJT8BUO7" && it.userId == 0 }
            ?: supervisor.availableSlots().firstOrNull()
        assertTrue(slot != null, "未找到可用的 ADB 设备槽位")
        println("使用设备槽位: ${slot.slotKey}, wechatVersion=${slot.wechatVersion}, sdk=${slot.sdkVersion}")

        val operations = AdbUserOperations()
        val maxAttempts = 3
        var loginSucceed = false

        for (attempt in 1..maxAttempts) {
            println("==================================================")
            println(">>> [2/6] 请求全新微信读书 Web 登录二维码 (第 $attempt/$maxAttempts 次) <<<")
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

            val pushResult = operations.pushQrCode(slot, qrFile)
            assertTrue(pushResult.success, "推送二维码到手机失败")
            println("二维码已推送到手机: ${pushResult.destinationPath}")

            try {
                println("==================================================")
                println(">>> [3/6] 引导微信调起扫一扫与点击授权 <<<")
                println("==================================================")
                val scanResult = operations.performWeReadScanAndConfirm(slot, isWeb = true)
                if (scanResult == com.wamp.download.adb.ScanConfirmResult.LOGIN_FAILED_PAGE) {
                    println("识别到微信提示【登录失败，请重新扫码登录】，退出并立即重来一次！")
                    Thread.sleep(1000)
                    continue
                }
                assertTrue(scanResult == com.wamp.download.adb.ScanConfirmResult.SUCCESS, "扫一扫流程调起失败")

                println("==================================================")
                println(">>> [4/6] 轮询微信扫码状态并完成换票 <<<")
                println("==================================================")
                var credentials = WeReadWebAuth.pollLoginCredentials(uid, timeoutSeconds = 15)
                if (credentials == null) {
                    for (i in 1..6) {
                        println("[补偿点击] 点击授权按钮第 $i 次...")
                        operations.tapRatio(slot, AdbUserOperations.RATIO_CONFIRM_BTN_X, AdbUserOperations.RATIO_CONFIRM_BTN_Y)
                        credentials = WeReadWebAuth.pollLoginCredentials(uid, timeoutSeconds = 5)
                        if (credentials != null) break
                    }
                }
                if (credentials != null) {
                    val saved = com.wamp.download.weread.WeReadCookieStore.setLoggedIn(
                        wrSkey = credentials.wrSkey,
                        wrVid = credentials.wrVid,
                        wrRt = "",
                        username = credentials.username,
                    )
                    println("\n>>> 最终授权换票结果: $saved, vid=${credentials.wrVid}, user=${credentials.username} <<<")
                    loginSucceed = true

                    println("==================================================")
                    println(">>> [5/6] 刷新账号池与持久化凭证 <<<")
                    println("==================================================")
                    WeReadAccountPool.loadAll()
                    WeReadAccountPool.list().forEach {
                        println(" -> 账号: vid=${it.vid}, username=${it.credentials.username}, status=${it.status}, skey=${it.credentials.wrSkey.take(12)}...")
                    }

                    println("==================================================")
                    println(">>> [6/6] 验证 Web 渠道登录后的真实有效抓取能力 <<<")
                    println("==================================================")
                    val testMpId = "MP_WXS_3295180586" // 广州海珠发布
                    println("正在尝试抓取公众号文章列表: mpId=$testMpId")
                    val client = WeReadWebClient()
                    val pageResult = client.fetchArticles(testMpId, offset = 0)
                    println("抓取结果: outcome=${pageResult.outcome}, 文章数=${pageResult.articles.size}")
                    pageResult.articles.take(5).forEachIndexed { index, art ->
                        println("  [$index] 标题: ${art.title}")
                        println("       链接: ${art.link}")
                        println("       时间: ${art.publishTime}")
                    }
                    assertTrue(pageResult.articles.isNotEmpty(), "Web 渠道抓取文章成功！")
                    break
                }
            } finally {
                println("=== 复位微信主界面并阅后即焚清理临时文件 ===")
                operations.dismissAuthorizationAndReset(slot)
                operations.cleanupQrCode(slot, pushResult.destinationPath)
                qrFile.delete()
            }
        }
        assertTrue(loginSucceed, "微信扫码授权成功完成！")
    }
}
