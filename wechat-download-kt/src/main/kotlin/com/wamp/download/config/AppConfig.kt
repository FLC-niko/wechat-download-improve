package com.wamp.download.config

import org.slf4j.LoggerFactory
import org.yaml.snakeyaml.Yaml
import java.io.File

/**
 * 全局配置管理（YAML + 环境变量覆盖）
 *
 * 与 Worker 的 AppConfig 保持一致的风格：先读 YAML，再用环境变量覆盖。
 */
object AppConfig {
    private val logger = LoggerFactory.getLogger(AppConfig::class.java)
    private var yamlConfig: Map<String, Any> = emptyMap()

    // 服务配置
    val host: String get() = getString("server.host", "0.0.0.0")
    val port: Int get() = getInt("server.port", 5000)

    // 微信凭证文件
    val credentialsFile: String get() = getString("auth.credentials_file", "data/.credentials.json")

    // 数据库
    val dbPath: String get() = getString("store.db_path", "data/rss.db")

    // RSS 轮询
    val rssPollInterval: Long get() = getLong("rss.poll_interval", 3600L)
    val articlesPerPoll: Int get() = getInt("rss.articles_per_poll", 10)

    // 通知配置
    val wecomWebhookUrl: String get() = getString("notification.wecom_webhook_url", "")
    val feishuWebhookUrl: String get() = getString("notification.feishu_webhook_url", "")
    val webhookNotificationInterval: Long get() = getLong("notification.interval", 300L)

    // 适配壳鉴权密钥
    val adapterApiKey: String get() = getString("adapter.api_key", "")

    // 站点 URL（用于图片代理）
    val siteUrl: String get() = getString("server.site_url", "http://localhost:5000")

    fun load() {
        val configFile = File("config.yaml")
        if (configFile.exists()) {
            yamlConfig = Yaml().load(configFile.inputStream())
            logger.info("配置已加载: config.yaml")
        } else {
            logger.info("未找到 config.yaml，使用默认值 + 环境变量")
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun getRaw(key: String): Any? {
        // 优先环境变量（下划线分隔），再 YAML
        val envKey = key.uppercase().replace(".", "_").replace("-", "_")
        System.getenv(envKey)?.let { return it }

        val parts = key.split(".")
        var current: Any? = yamlConfig
        for (part in parts) {
            current = (current as? Map<String, Any>)?.get(part) ?: return null
        }
        return current
    }

    private fun getString(key: String, default: String): String =
        getRaw(key)?.toString() ?: default

    private fun getInt(key: String, default: Int): Int =
        getRaw(key)?.toString()?.toIntOrNull() ?: default

    private fun getLong(key: String, default: Long): Long =
        getRaw(key)?.toString()?.toLongOrNull() ?: default
}
