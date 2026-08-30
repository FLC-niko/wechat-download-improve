package com.wamp.download.auth

import com.google.gson.Gson
import com.wamp.download.config.AppConfig
import com.wamp.download.task.ChannelStateManager
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant

/**
 * 微信凭证数据
 */
data class WechatCredentials(
    val token: String = "",
    val cookie: String = "",
    val fakeid: String = "",
    val nickname: String = "",
    val expireTime: Long = 0L,  // 毫秒时间戳，对齐 Python 版
)

/**
 * 凭证管理器 — 对齐 Python 版 auth_manager.py
 *
 * 双存储策略：JSON 文件（优先）+ 内存缓存。
 */
object AuthManager {
    private val logger = LoggerFactory.getLogger(AuthManager::class.java)
    private val gson = Gson()

    @Volatile
    private var credentials = WechatCredentials()

    init {
        load()
    }

    fun load() {
        val file = File(AppConfig.credentialsFile)
        if (file.exists()) {
            try {
                credentials = gson.fromJson(file.readText(), WechatCredentials::class.java)
                logger.info("凭证已加载: ${file.absolutePath}")
            } catch (e: Exception) {
                logger.warn("凭证文件解析失败: ${e.message}")
            }
        }
    }

    fun save(creds: WechatCredentials) {
        val previous = credentials
        credentials = creds
        val file = File(AppConfig.credentialsFile)
        try {
            file.parentFile?.mkdirs()
            val temporary = File(file.parentFile, ".${file.name}.tmp")
            temporary.writeText(gson.toJson(creds))
            Files.move(
                temporary.toPath(),
                file.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            logger.info("凭证已保存: ${file.absolutePath}")
        } catch (e: Exception) {
            logger.error("凭证保存失败: ${e.message}")
        }
        if (creds.token != previous.token || creds.cookie != previous.cookie) {
            ChannelStateManager.resetMainChannelAfterCredentialUpdate()
        }
    }

    fun get(): WechatCredentials = credentials

    fun isLoggedIn(): Boolean =
        credentials.token.isNotEmpty() && credentials.cookie.isNotEmpty()

    /**
     * 检查是否已过期
     */
    fun isExpired(): Boolean {
        if (credentials.expireTime <= 0) return false
        val now = System.currentTimeMillis()
        return now > credentials.expireTime
    }

    /**
     * 获取剩余有效时间（秒），已过期返回 0
     */
    fun timeLeftSeconds(): Long {
        if (credentials.expireTime <= 0) return Long.MAX_VALUE
        val left = credentials.expireTime - System.currentTimeMillis()
        return if (left > 0) left / 1000 else 0
    }

    fun clear() {
        credentials = WechatCredentials()
        val file = File(AppConfig.credentialsFile)
        if (file.exists()) file.delete()
        logger.info("凭证已清除")
    }
}
