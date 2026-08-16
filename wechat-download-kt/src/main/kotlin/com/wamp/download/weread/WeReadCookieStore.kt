package com.wamp.download.weread

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.wamp.download.config.AppConfig
import org.slf4j.LoggerFactory
import java.io.File
import java.time.Instant

/**
 * 微信读书官方 Web 端凭证（wr_skey 系列 cookie）
 *
 * 通过扫码登录获得，wr_skey 有效期 5400s（1.5h），靠主动刷新保活。
 * 文件路径：data/.weread_web_credentials.json（AppConfig.wereadCredentialsFile）
 */
data class WeReadWebCredentials(
    val wrSkey: String = "",           // 核心认证凭证，有效期 5400s
    val wrVid: String = "",            // 用户 ID
    val wrRt: String = "",             // refresh token（session/init 返回）
    val username: String = "",         // 显示名（便于识别账号）
    val loginTime: Long = 0L,          // 登录时间戳（毫秒）
    var lastRefreshTime: Long = 0L,    // 最后刷新时间（毫秒），主动刷新时更新
    var refreshFailCount: Int = 0,     // 连续刷新失败计数
) {
    /** 序列化为 Cookie 头（供请求携带） */
    fun toCookieString(): String = buildString {
        if (wrSkey.isNotEmpty()) append("wr_skey=$wrSkey; ")
        if (wrVid.isNotEmpty()) append("wr_vid=$wrVid; ")
        if (wrRt.isNotEmpty()) append("wr_rt=$wrRt")
        if (isEmpty()) append("")
    }.trimEnd(' ', ';')

    /** 是否需要主动刷新（距上次刷新超过阈值，默认 50 分钟） */
    fun needRefresh(): Boolean =
        wrSkey.isNotEmpty() &&
            (lastRefreshTime <= 0L || System.currentTimeMillis() - lastRefreshTime >= AppConfig.wereadRefreshInterval * 1000)

    private fun isEmpty(): Boolean = wrSkey.isEmpty() && wrVid.isEmpty() && wrRt.isEmpty()
}

/**
 * 微信读书官方凭证持久化（单账号，风格对齐 AuthManager）
 *
 * - load()：启动时从文件加载
 * - save()：写入文件（更新内存 + 落盘）
 * - updateFromSetCookie()：从响应 set-cookie 提取 wr_skey/wr_vid/wr_rt
 * - isLoggedIn()：是否已登录且未完全失效
 */
object WeReadCookieStore {
    private val logger = LoggerFactory.getLogger(WeReadCookieStore::class.java)
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    @Volatile
    private var credentials = WeReadWebCredentials()

    init {
        load()
    }

    fun load() {
        val file = File(AppConfig.wereadCredentialsFile)
        if (file.exists()) {
            try {
                credentials = gson.fromJson(file.readText(), WeReadWebCredentials::class.java)
                logger.info("微信读书官方凭证已加载: ${file.absolutePath} (vid=${credentials.wrVid})")
            } catch (e: Exception) {
                logger.warn("微信读书官方凭证解析失败: ${e.message}")
            }
        }
    }

    fun save(creds: WeReadWebCredentials) {
        credentials = creds
        val file = File(AppConfig.wereadCredentialsFile)
        try {
            file.parentFile?.mkdirs()
            file.writeText(gson.toJson(creds))
            logger.info("微信读书官方凭证已保存: ${file.absolutePath} (vid=${creds.wrVid})")
        } catch (e: Exception) {
            logger.error("微信读书官方凭证保存失败: ${e.message}")
        }
    }

    fun get(): WeReadWebCredentials = credentials

    fun isLoggedIn(): Boolean = credentials.wrSkey.isNotEmpty() && credentials.wrVid.isNotEmpty()

    fun clear() {
        credentials = WeReadWebCredentials()
        val file = File(AppConfig.wereadCredentialsFile)
        if (file.exists()) file.delete()
        logger.info("微信读书官方凭证已清除")
    }

    /**
     * 从响应 set-cookie 头更新凭证（主动刷新 / session/init 都用到）
     *
     * 注意：服务端刷新时可能只返回部分字段，因此采用"合并更新"而非整体替换。
     *
     * @return 是否发生变更（拿到至少一个有效字段）
     */
    fun updateFromSetCookie(setCookies: List<String>, username: String? = null): Boolean {
        if (setCookies.isEmpty()) return false

        var newSkey = credentials.wrSkey
        var newVid = credentials.wrVid
        var newRt = credentials.wrRt
        var changed = false

        for (header in setCookies) {
            val pair = header.substringBefore(';').trim()
            val idx = pair.indexOf('=')
            if (idx <= 0) continue
            val name = pair.substring(0, idx).trim()
            val value = pair.substring(idx + 1).trim()
            when (name) {
                "wr_skey" -> if (value.isNotEmpty()) { newSkey = value; changed = true }
                "wr_vid" -> if (value.isNotEmpty()) { newVid = value; changed = true }
                "wr_rt" -> if (value.isNotEmpty()) { newRt = value; changed = true }
            }
        }

        if (!changed) return false

        credentials = credentials.copy(
            wrSkey = newSkey,
            wrVid = newVid,
            wrRt = newRt,
            username = username ?: credentials.username,
            lastRefreshTime = System.currentTimeMillis(),
            refreshFailCount = 0,
        )
        save(credentials)
        return true
    }

    /** 主动刷新成功后调用：更新时间戳、清零失败计数（即使 set-cookie 未变也要更新 lastRefreshTime） */
    fun markRefreshed() {
        credentials = credentials.copy(
            lastRefreshTime = System.currentTimeMillis(),
            refreshFailCount = 0,
        )
        save(credentials)
    }

    /** 刷新失败时调用：递增连续失败计数 */
    fun markRefreshFailed(): Int {
        val count = credentials.refreshFailCount + 1
        credentials = credentials.copy(refreshFailCount = count)
        save(credentials)
        return count
    }

    /** 登录成功后设置完整凭证 */
    fun setLoggedIn(wrSkey: String, wrVid: String, wrRt: String, username: String) {
        credentials = WeReadWebCredentials(
            wrSkey = wrSkey,
            wrVid = wrVid,
            wrRt = wrRt,
            username = username,
            loginTime = Instant.now().toEpochMilli(),
            lastRefreshTime = Instant.now().toEpochMilli(),
            refreshFailCount = 0,
        )
        save(credentials)
    }
}
