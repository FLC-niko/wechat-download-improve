package com.wamp.download.auth

import com.google.gson.Gson
import com.wamp.download.notify.WebhookNotifier
import kotlinx.coroutines.delay
import okhttp3.*
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 微信公众号登录服务 — 服务端主动扫码登录
 *
 * 流程（对齐 Python 版 login.py）：
 * 1. bizlogin?action=startlogin → 初始化会话
 * 2. scanloginqrcode?action=getqrcode → 获取二维码图片
 * 3. 推送二维码到通知机器人
 * 4. 轮询 scanloginqrcode?action=ask → 等待扫码
 * 5. bizlogin?action=login → 完成登录，提取 token
 * 6. 用 token + cookie 获取昵称和 fakeid
 * 7. 保存凭证
 */
class LoginService(
    private val webhookNotifier: WebhookNotifier = WebhookNotifier,
) {
    private val logger = LoggerFactory.getLogger(LoginService::class.java)
    private val gson = Gson()

    private val mpBaseUrl = "https://mp.weixin.qq.com"
    private val bizLoginUrl = "$mpBaseUrl/cgi-bin/bizlogin"
    private val qrEndpoint = "$mpBaseUrl/cgi-bin/scanloginqrcode"

    private val userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    @Volatile
    private var loginInProgress = false

    /**
     * 发起一次完整的登录流程（阻塞直到成功或超时）
     *
     * @param qrcodeDir 二维码图片保存目录
     * @param maxWaitSeconds 最大等待扫码时间
     * @return 登录是否成功
     */
    suspend fun startLogin(qrcodeDir: String = "qrcode", maxWaitSeconds: Int = 600): Boolean {
        if (loginInProgress) {
            logger.warn("登录流程已在进行中，跳过")
            return false
        }
        loginInProgress = true

        try {
            // 每次登录用独立的 cookie jar，避免旧会话干扰
            val cookieJar = InMemoryCookieJar()
            val client = OkHttpClient.Builder()
                .cookieJar(cookieJar)
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .followRedirects(true)
                .build()

            // 1. 初始化会话
            logger.info("【登录】正在初始化微信登录会话...")
            if (!startLoginSession(client)) {
                logger.error("【登录】初始化会话失败")
                return false
            }

            // 2. 获取二维码
            logger.info("【登录】正在获取二维码...")
            val qrBytes = getQrcodeImage(client)
            if (qrBytes == null || qrBytes.isEmpty()) {
                logger.error("【登录】获取二维码失败")
                return false
            }

            // 保存二维码到本地
            val qrDir = File(qrcodeDir).apply { mkdirs() }
            val qrFile = File(qrDir, "wx_qrcode.png")
            qrFile.writeBytes(qrBytes)
            logger.info("【登录】二维码已保存: ${qrFile.absolutePath}")

            // 3. 推送二维码到通知机器人
            webhookNotifier.pushQrcode(qrBytes, "请使用微信扫码登录公众号后台")

            // 4. 轮询扫码状态
            logger.info("【登录】等待扫码（超时: ${maxWaitSeconds}秒）...")
            val startTime = System.currentTimeMillis()
            var scanned = false

            while (System.currentTimeMillis() - startTime < maxWaitSeconds * 1000L) {
                delay(3000)
                val status = checkScanStatus(client)
                when (status) {
                    ScanStatus.SCANNED -> {
                        if (!scanned) {
                            logger.info("【登录】检测到已扫码，等待确认...")
                            scanned = true
                        }
                    }
                    ScanStatus.CONFIRMED -> {
                        logger.info("【登录】扫码确认成功，正在完成登录...")
                        // 5. 完成登录
                        return completeLogin(client, cookieJar)
                    }
                    ScanStatus.EXPIRED -> {
                        logger.warn("【登录】二维码已过期")
                        return false
                    }
                    ScanStatus.WAITING -> { /* 继续等待 */ }
                }
            }

            logger.warn("【登录】${maxWaitSeconds}秒内未完成扫码")
            return false
        } catch (e: Exception) {
            logger.error("【登录】登录流程异常: ${e.message}", e)
            return false
        } finally {
            loginInProgress = false
        }
    }

    private fun startLoginSession(client: OkHttpClient): Boolean {
        val formBody = FormBody.Builder()
            .add("userlang", "zh_CN")
            .add("redirect_url", "")
            .add("login_type", "3")
            .add("token", "")
            .add("lang", "zh_CN")
            .add("f", "json")
            .add("ajax", "1")
            .build()

        val request = Request.Builder()
            .url("$bizLoginUrl?action=startlogin")
            .header("User-Agent", userAgent)
            .header("Referer", mpBaseUrl)
            .header("Origin", mpBaseUrl)
            .post(formBody)
            .build()

        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                logger.error("startlogin HTTP ${resp.code}")
                return false
            }
            val body = resp.body?.string().orEmpty()
            val result = gson.fromJson(body, Map::class.java)
            val ret = (result?.get("base_resp") as? Map<*, *>)?.get("ret")
            logger.debug("startlogin 响应: $body")
            return ret == 0.0 || ret == 0 || ret == "0"
        }
    }

    private fun getQrcodeImage(client: OkHttpClient): ByteArray? {
        val request = Request.Builder()
            .url("$qrEndpoint?action=getqrcode&random=${System.currentTimeMillis()}")
            .header("User-Agent", userAgent)
            .header("Referer", mpBaseUrl)
            .get()
            .build()

        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                logger.error("getqrcode HTTP ${resp.code}")
                return null
            }
            val contentType = resp.header("Content-Type", "")
            val bytes = resp.body?.bytes()
            // 检查是否为图片
            if (bytes != null && (contentType?.contains("image") == true || bytes.startsWithPNG())) {
                return bytes
            }
            logger.warn("getqrcode 返回非图片: Content-Type=$contentType, 长度=${bytes?.size}")
            return null
        }
    }

    private enum class ScanStatus { WAITING, SCANNED, CONFIRMED, EXPIRED }

    private fun checkScanStatus(client: OkHttpClient): ScanStatus {
        val request = Request.Builder()
            .url("$qrEndpoint?action=ask&token=&lang=zh_CN&f=json&ajax=1")
            .header("User-Agent", userAgent)
            .header("Referer", mpBaseUrl)
            .get()
            .build()

        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return ScanStatus.WAITING
            val body = resp.body?.string().orEmpty()
            val result = gson.fromJson(body, Map::class.java) ?: return ScanStatus.WAITING
            val baseResp = result["base_resp"] as? Map<*, *>
            val ret = baseResp?.get("ret")
            if (ret != 0.0 && ret != 0 && ret != "0") {
                return ScanStatus.WAITING
            }
            val status = (result["status"] as? Number)?.toInt() ?: 0
            return when (status) {
                1 -> ScanStatus.CONFIRMED
                4, 6 -> ScanStatus.SCANNED
                else -> ScanStatus.WAITING
            }
        }
    }

    private suspend fun completeLogin(client: OkHttpClient, cookieJar: InMemoryCookieJar): Boolean {
        val formBody = FormBody.Builder()
            .add("userlang", "zh_CN")
            .add("redirect_url", "")
            .add("cookie_forbidden", "0")
            .add("cookie_cleaned", "0")
            .add("plugin_used", "0")
            .add("login_type", "3")
            .add("token", "")
            .add("lang", "zh_CN")
            .add("f", "json")
            .add("ajax", "1")
            .build()

        val request = Request.Builder()
            .url("$bizLoginUrl?action=login")
            .header("User-Agent", userAgent)
            .header("Referer", mpBaseUrl)
            .header("Origin", mpBaseUrl)
            .post(formBody)
            .build()

        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                logger.error("bizlogin?action=login HTTP ${resp.code}")
                return false
            }
            val body = resp.body?.string().orEmpty()
            val result = gson.fromJson(body, Map::class.java)
            val baseResp = result?.get("base_resp") as? Map<*, *>
            val ret = baseResp?.get("ret")
            if (ret != 0.0 && ret != 0 && ret != "0") {
                val errMsg = baseResp?.get("err_msg") ?: "未知错误"
                logger.error("登录失败: ret=$ret, err_msg=$errMsg")
                return false
            }

            // 从 redirect_url 提取 token
            val redirectUrl = result?.get("redirect_url") as? String
            if (redirectUrl.isNullOrEmpty()) {
                logger.error("未获取到 redirect_url")
                return false
            }
            val token = extractToken(redirectUrl)
            if (token.isNullOrEmpty()) {
                logger.error("未从 redirect_url 提取到 token: $redirectUrl")
                return false
            }

            // 构建 cookie 字符串
            val cookieStr = cookieJar.toCookieString()
            logger.info("【登录】获取到 token: ${token.take(20)}...")

            // 获取公众号信息
            val (nickname, fakeid) = fetchAccountInfo(client, token, cookieStr)

            // 计算过期时间（4 天后）
            val expireTime = (System.currentTimeMillis() + 4L * 24 * 3600 * 1000)

            // 保存凭证
            AuthManager.save(
                WechatCredentials(
                    token = token,
                    cookie = cookieStr,
                    fakeid = fakeid,
                    nickname = nickname,
                    expireTime = expireTime,
                )
            )

            logger.info("【登录】登录成功: $nickname (fakeid: $fakeid)")

            // 通知登录成功
            webhookNotifier.notify(
                "login_success",
                mapOf("nickname" to nickname, "fakeid" to fakeid)
            )

            return true
        }
    }

    /**
     * 用 token + cookie 获取公众号昵称和 fakeid
     */
    private fun fetchAccountInfo(
        client: OkHttpClient,
        token: String,
        cookieStr: String,
    ): Pair<String, String> {
        val headers = Headers.Builder()
            .add("User-Agent", userAgent)
            .add("Cookie", cookieStr)
            .build()

        // 获取昵称
        var nickname = "公众号"
        try {
            val homeRequest = Request.Builder()
                .url("$mpBaseUrl/cgi-bin/home?t=home/index&token=$token&lang=zh_CN")
                .headers(headers)
                .get()
                .build()

            client.newCall(homeRequest).execute().use { resp ->
                val html = resp.body?.string().orEmpty()
                // 从 HTML 中提取 nick_name
                val nickRegex = Regex("""nick_name\s*[:=]\s*["']([^"']+)["']""")
                nickRegex.find(html)?.let { nickname = decodeHtmlEntities(it.groupValues[1]) }
            }
        } catch (e: Exception) {
            logger.warn("获取昵称失败: ${e.message}")
        }

        // 搜索获取 fakeid
        var fakeid = ""
        try {
            val searchUrl = HttpUrl.Builder()
                .scheme("https")
                .host("mp.weixin.qq.com")
                .addPathSegment("cgi-bin")
                .addPathSegment("searchbiz")
                .addQueryParameter("action", "search_biz")
                .addQueryParameter("token", token)
                .addQueryParameter("lang", "zh_CN")
                .addQueryParameter("f", "json")
                .addQueryParameter("ajax", "1")
                .addQueryParameter("random", System.currentTimeMillis().toString())
                .addQueryParameter("query", nickname)
                .addQueryParameter("begin", "0")
                .addQueryParameter("count", "5")
                .build()

            val searchRequest = Request.Builder()
                .url(searchUrl)
                .headers(headers)
                .get()
                .build()

            client.newCall(searchRequest).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                val result = gson.fromJson(body, Map::class.java)
                val baseResp = result?.get("base_resp") as? Map<*, *>
                if (baseResp?.get("ret") == 0.0 || baseResp?.get("ret") == 0) {
                    val list = result?.get("list") as? List<*>
                    list?.forEach { account ->
                        val acc = account as? Map<*, *>
                        val accNickname = acc?.get("nickname") as? String
                        val accFakeid = acc?.get("fakeid") as? String
                        if (accNickname == nickname && !accFakeid.isNullOrEmpty()) {
                            fakeid = accFakeid
                            return@forEach
                        }
                    }
                    // 没有精确匹配就用第一个
                    if (fakeid.isEmpty()) {
                        fakeid = (list?.firstOrNull() as? Map<*, *>)?.get("fakeid") as? String ?: ""
                    }
                }
            }
        } catch (e: Exception) {
            logger.warn("获取 FakeID 失败: ${e.message}")
        }

        return nickname to fakeid
    }

    private fun extractToken(redirectUrl: String): String? {
        // redirect_url 格式: /cgi-bin/home?t=home/index&lang=zh_CN&token=123456
        val tokenRegex = Regex("""token=(\d+)""")
        return tokenRegex.find(redirectUrl)?.groupValues?.get(1)
    }

    /**
     * 解码常见 HTML 实体（微信 HTML 中 nick_name 常包含 &nbsp; 等）
     */
    private fun decodeHtmlEntities(text: String): String {
        return text
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            // 数字实体 &#160; &#xA0; 等
            .replace(Regex("&#(\\d+);")) { m ->
                m.groupValues[1].toIntOrNull()?.let { it.toChar().toString() } ?: m.value
            }
            .replace(Regex("&#x([0-9a-fA-F]+);")) { m ->
                m.groupValues[1].toIntOrNull(16)?.let { it.toChar().toString() } ?: m.value
            }
            .trim()
    }

    private fun ByteArray.startsWithPNG(): Boolean =
        size >= 4 && this[0] == 0x89.toByte() && this[1] == 0x50.toByte() && this[2] == 0x4E.toByte() && this[3] == 0x47.toByte()
}

/**
 * 内存 Cookie Jar — 用于管理一次登录会话的 cookie
 */
class InMemoryCookieJar : CookieJar {
    private val cookies = ConcurrentHashMap<String, Cookie>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        cookies.forEach { cookie ->
            this.cookies[cookie.name] = cookie
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> = cookies.values.toList()

    /**
     * 将所有 cookie 拼接为 "key=value; key2=value2" 格式
     */
    fun toCookieString(): String =
        cookies.values.joinToString("; ") { "${it.name}=${it.value}" }
}
