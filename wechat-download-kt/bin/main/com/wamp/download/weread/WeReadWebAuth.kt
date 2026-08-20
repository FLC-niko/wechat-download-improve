package com.wamp.download.weread

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.JsonObject
import com.wamp.download.config.AppConfig
import com.wamp.download.notify.WebhookNotifier
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 微信读书官方 Web 端认证（2026 新版接口）
 *
 * 注意：官方登录接口已在 2026 改版，社区 2022 文档（/web/login/getuid + wr_skey 5400s 刷新）已过时。
 * 当前实测的新流程：
 * 1. GET  /api/auth/getLoginUid                    -> { uid }
 * 2. 二维码 URL: https://weread.qq.com/web/confirm?uid={uid}（推送到企业微信/飞书）
 * 3. GET  /api/auth/getLoginInfo?uid={uid}&otp=    -> 长轮询（服务端 60s），扫码后返回
 *    { succeed: true, logicCode: "...", accessToken, webLoginVid }
 * 4. 保存凭证：wr_vid = webLoginVid, wr_skey = accessToken，cookie 有效期 365 天
 * 5. GET  /api/userInfo?userVid={vid}              -> { value: { name } } 获取昵称
 *
 * 认证方式：请求头 x-vid / x-skey（新版前端）或 cookie wr_vid / wr_skey（旧接口兼容）
 * 凭证持久化到文件（风格对齐 AuthManager），复用 WeReadCookieStore。
 */
object WeReadWebAuth {
    private val logger = LoggerFactory.getLogger(WeReadWebAuth::class.java)
    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(70, TimeUnit.SECONDS)  // getLoginInfo 长轮询最长 60s
        .build()

    private val baseUrl = AppConfig.wereadWebBaseUrl

    // --- 登录状态 ---

    fun isLoggedIn(): Boolean = WeReadCookieStore.isLoggedIn()

    /** 重新扫码进行中标志（防重入：按账号隔离，每账号同时只发起一次） */
    private val reloginInProgress = ConcurrentHashMap<String, Boolean>()

    /** 达到重新扫码阈值时异步发起登录（操作主账号，兼容入口） */
    fun requestReloginIfNeeded() {
        val vid = WeReadAccountPool.primaryAccount()?.vid ?: return
        requestReloginIfNeeded(vid)
    }

    /**
     * 达到重新扫码阈值时异步发起登录（防重入，按账号隔离）
     *
     * 在抓取链路（被动刷新失败）中调用，不阻塞当前协程。
     * 由独立线程执行扫码登录流程，结束自动复位标志。
     */
    fun requestReloginIfNeeded(vid: String) {
        if (reloginInProgress.getOrDefault(vid, false)) return
        if (!shouldRelogin(vid)) return
        reloginInProgress[vid] = true
        Thread {
            try {
                logger.warn("微信读书连续刷新失败达到阈值，异步触发重新扫码: vid=$vid")
                startLogin()
            } finally {
                reloginInProgress.remove(vid)
            }
        }.start()
    }

    /**
     * 发起扫码登录（获取 uid 与二维码 URL）
     *
     * @return Pair(uid, scanUrl)，失败返回 null
     */
    fun createLogin(): Pair<String, String>? {
        return try {
            val req = Request.Builder()
                .url("$baseUrl/api/auth/getLoginUid")
                .header("Referer", "$baseUrl/")
                .header("User-Agent", UA)
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    logger.error("获取微信读书二维码失败: HTTP ${resp.code}")
                    return null
                }
                val body = JsonParser.parseString(resp.body?.string().orEmpty()).asJsonObject
                val uid = body.get("uid")?.asString ?: return null
                val scanUrl = "$baseUrl/web/confirm?uid=$uid"
                logger.info("微信读书二维码已生成: uid=$uid")
                uid to scanUrl
            }
        } catch (e: Exception) {
            logger.error("获取微信读书二维码异常: ${e.message}")
            null
        }
    }

    /**
     * 轮询扫码结果（getLoginInfo 长轮询）
     *
     * @param uid 二维码 uid
     * @param timeoutSeconds 最长等待秒数（服务端单次长轮询约 60s）
     * @return true=登录成功并已保存凭证
     */
    fun pollLogin(uid: String, timeoutSeconds: Long = 120): Boolean {
        val start = System.currentTimeMillis()
        val deadline = start + timeoutSeconds * 1000
        while (System.currentTimeMillis() < deadline) {
            try {
                val url = "$baseUrl/api/auth/getLoginInfo".toHttpUrl()
                    .newBuilder()
                    .addQueryParameter("uid", uid)
                    .addQueryParameter("otp", "")
                    .build()
                val req = Request.Builder()
                    .url(url)
                    .header("Referer", "$baseUrl/")
                    .header("User-Agent", UA)
                    .get()
                    .build()
                client.newCall(req).execute().use { resp ->
                    val body = JsonParser.parseString(resp.body?.string().orEmpty())
                    if (!body.isJsonObject) return@use
                    val obj = body.asJsonObject
                    if (obj.get("succeed")?.asBoolean == true) {
                        val accessToken = obj.get("accessToken")?.asString
                        val webLoginVid = obj.get("webLoginVid")?.asString
                        if (!accessToken.isNullOrEmpty() && !webLoginVid.isNullOrEmpty()) {
                            val username = fetchUsername(webLoginVid)
                            WeReadCookieStore.setLoggedIn(
                                wrSkey = accessToken,
                                wrVid = webLoginVid,
                                wrRt = "",
                                username = username,
                            )
                            logger.info("微信读书登录成功: username=$username, vid=$webLoginVid")
                            return true
                        }
                    } else {
                        val logicCode = obj.get("logicCode")?.asString ?: ""
                        when (logicCode) {
                            "LOGIN_TIMEOUT" -> {
                                logger.warn("微信读书扫码等待超时")
                                return false
                            }
                            "NEED_OTP", "OTP_EXPIRED", "OTP_NOT_MATCH" -> {
                                logger.warn("微信读书需要双重验证(OTP): $logicCode，当前暂不支持自动处理")
                                return false
                            }
                            else -> logger.debug("getLoginInfo 未完成: logicCode=$logicCode")
                        }
                    }
                }
            } catch (e: Exception) {
                logger.warn("轮询微信读书扫码状态异常: ${e.message}")
            }
            Thread.sleep(2000)
        }
        logger.warn("微信读书扫码等待超时")
        return false
    }

    /**
     * 获取用户昵称（登录成功后调用）
     */
    private fun fetchUsername(vid: String): String {
        return try {
            val req = Request.Builder()
                .url("$baseUrl/api/userInfo".toHttpUrl().newBuilder()
                    .addQueryParameter("userVid", vid).build())
                .header("Referer", "$baseUrl/")
                .header("User-Agent", UA)
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
                val obj = JsonParser.parseString(resp.body?.string().orEmpty())
                if (obj.isJsonObject) {
                    obj.asJsonObject.get("value")?.asJsonObject?.get("name")?.asString ?: ""
                } else ""
            }
        } catch (e: Exception) {
            logger.warn("获取微信读书昵称失败: ${e.message}")
            ""
        }
    }

    /**
     * 发起完整扫码登录（获取二维码 + 生成图片推送机器人 + 轮询）
     *
     * @return true=登录成功
     */
    fun startLogin(): Boolean {
        val (uid, scanUrl) = createLogin() ?: return false
        // 将扫码链接生成二维码图片并推送到通知机器人
        val qrPng = generateQrPng(scanUrl)
        if (qrPng != null) {
            // 保存到 qrcode 目录供飞书卡片引用
            try {
                val dir = java.io.File("qrcode").apply { mkdirs() }
                java.io.File(dir, "wx_qrcode.png").writeBytes(qrPng)
            } catch (e: Exception) {
                logger.warn("保存二维码图片失败: ${e.message}")
            }
            kotlinx.coroutines.runBlocking {
                WebhookNotifier.pushQrcode(qrPng, "请使用微信扫码登录微信读书（公众号采集回退通道）")
            }
        } else {
            kotlinx.coroutines.runBlocking {
                WebhookNotifier.notify("weread_login_qr", mapOf(
                    "message" to "微信读书需要重新登录，请打开链接扫码",
                    "scan_url" to scanUrl,
                ))
            }
        }
        return pollLogin(uid)
    }

    /**
     * 主动刷新保活（操作主账号，兼容入口）
     */
    fun proactiveRefresh(): Boolean {
        val vid = WeReadAccountPool.primaryAccount()?.vid ?: return false
        return proactiveRefresh(vid)
    }

    /**
     * 主动刷新保活（wr_skey 双层刷新的"主动"层，按账号）
     *
     * 通过 HEAD 官网首页带旧 cookie 探测凭证状态：
     * - HTTP 401 → 凭证失效，标记失败
     * - 200 + 有效新 set-cookie → 刷新成功
     * - 200 + set-cookie 被清空（Max-Age=0 空值）→ 凭证已失效，标记失败
     * - 200 + 无 set-cookie → 凭证仍有效，仅更新时间戳
     *
     * @return true=仍保持登录，false=登录态已失效需重新扫码
     */
    fun proactiveRefresh(vid: String): Boolean {
        val account = WeReadAccountPool.get(vid) ?: return false
        val creds = account.credentials
        if (creds.wrSkey.isEmpty() || creds.wrVid.isEmpty()) return false
        return try {
            val req = Request.Builder()
                .url("$baseUrl/")
                .head()
                .header("Cookie", creds.toCookieString())
                .header("User-Agent", UA)
                .build()
            client.newCall(req).execute().use { resp ->
                if (resp.code == 401) {
                    WeReadAccountPool.markRefreshFailed(vid)
                    logger.warn("微信读书刷新失败（401），连续失败 ${WeReadAccountPool.get(vid)?.credentials?.refreshFailCount} 次: vid=$vid")
                    false
                } else {
                    val setCookies = resp.headers("set-cookie")
                    if (setCookies.isEmpty()) {
                        // 无 set-cookie：凭证仍有效，仅更新时间戳防频繁探测
                        WeReadAccountPool.markRefreshed(vid)
                        logger.debug("微信读书凭证仍有效（无 set-cookie）: vid=$vid")
                        true
                    } else if (WeReadAccountPool.updateFromSetCookie(vid, setCookies)) {
                        // 拿到有效新凭证
                        logger.debug("微信读书凭证刷新成功: vid=$vid")
                        true
                    } else {
                        // set-cookie 全是清空（wr_skey=; Max-Age=0）→ 服务端已登出
                        WeReadAccountPool.markRefreshFailed(vid)
                        logger.warn("微信读书凭证已被服务端清空（登录超时），连续失败 ${WeReadAccountPool.get(vid)?.credentials?.refreshFailCount} 次: vid=$vid")
                        false
                    }
                }
            }
        } catch (e: Exception) {
            WeReadAccountPool.markRefreshFailed(vid)
            logger.warn("微信读书刷新异常: ${e.message}: vid=$vid")
            false
        }
    }

    /**
     * 连续刷新失败是否达到需重新扫码的阈值（操作主账号，兼容入口）
     */
    fun shouldRelogin(): Boolean {
        val vid = WeReadAccountPool.primaryAccount()?.vid ?: return false
        return shouldRelogin(vid)
    }

    /** 连续刷新失败是否达到需重新扫码的阈值（按账号） */
    fun shouldRelogin(vid: String): Boolean =
        (WeReadAccountPool.get(vid)?.credentials?.refreshFailCount ?: 0) >= AppConfig.wereadRefreshFailThreshold

    /**
     * 清除登录状态
     */
    fun clear() {
        WeReadCookieStore.clear()
    }

    /**
     * 将文本编码为二维码 PNG 图片字节
     */
    fun generateQrPng(text: String, size: Int = 400): ByteArray? {
        return try {
            val hints = mapOf(
                com.google.zxing.EncodeHintType.CHARACTER_SET to "UTF-8",
                com.google.zxing.EncodeHintType.MARGIN to 1,
            )
            val matrix = com.google.zxing.qrcode.QRCodeWriter()
                .encode(text, com.google.zxing.BarcodeFormat.QR_CODE, size, size, hints)
            val img = java.awt.image.BufferedImage(size, size, java.awt.image.BufferedImage.TYPE_INT_RGB)
            for (x in 0 until size) {
                for (y in 0 until size) {
                    img.setRGB(x, y, if (matrix.get(x, y)) 0x000000 else 0xFFFFFF)
                }
            }
            val baos = java.io.ByteArrayOutputStream()
            javax.imageio.ImageIO.write(img, "png", baos)
            baos.toByteArray()
        } catch (e: Exception) {
            logger.error("生成二维码失败: ${e.message}")
            null
        }
    }

    private const val UA =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
}
