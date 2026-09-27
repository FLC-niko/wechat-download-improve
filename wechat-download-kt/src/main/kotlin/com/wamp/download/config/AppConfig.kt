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
    val authAutoRecoveryEnabled: Boolean get() = getBool("auth.auto_recovery_enabled", false)

    // 数据库
    val dbPath: String get() = getString("store.db_path", "data/rss.db")

    // RSS 轮询
    val rssEnabled: Boolean get() = getBool("rss.enabled", false)
    val rssPollInterval: Long get() = getLong("rss.poll_interval", 3600L)
    val articlesPerPoll: Int get() = getInt("rss.articles_per_poll", 10)

    // --- RabbitMQ 配置（与 Worker 一致） ---
    val rabbitmqHost: String get() = getString("rabbitmq.host", "localhost")
    val rabbitmqPort: Int get() = getInt("rabbitmq.port", 5672)
    val rabbitmqUsername: String get() = getString("rabbitmq.username", "guest")
    val rabbitmqPassword: String get() = getString("rabbitmq.password", "guest")
    val rabbitmqProVirtualHost: String get() = getString("rabbitmq.pro_virtual_host", "pro")
    val rabbitmqTestVirtualHost: String get() = getString("rabbitmq.test_virtual_host", "test")
    val rabbitmqXdagVirtualHost: String get() = getString("rabbitmq.xdag_virtual_host", "xdag")
    val rabbitmqThdagVirtualHost: String get() = getString("rabbitmq.thdag_virtual_host", "thdag")
    val rabbitmqReconnectInitialDelayMs: Long
        get() = getLong("rabbitmq.reconnect_initial_delay_ms", 5_000L).coerceAtLeast(1_000L)
    val rabbitmqReconnectMaxDelayMs: Long
        get() = getLong("rabbitmq.reconnect_max_delay_ms", 60_000L)
            .coerceAtLeast(rabbitmqReconnectInitialDelayMs)
    val rabbitmqConsumerPrefetch: Int
        get() = getInt("rabbitmq.consumer_prefetch", 4).coerceIn(1, 32)

    // --- RabbitMQ 任务策略 ---
    val maxTaskRetry: Int get() = getInt("rabbitmq.max_task_retry", 5)
    val taskRetryDelayMs: Long get() = getLong("rabbitmq.task_retry_delay_ms", 60_000L)

    // 通知配置
    val wecomWebhookUrl: String get() = getString("notification.wecom_webhook_url", "")
    val feishuWebhookUrl: String get() = getString("notification.feishu_webhook_url", "")
    val webhookNotificationInterval: Long get() = getLong("notification.interval", 300L)

    // 适配壳鉴权密钥
    val adapterApiKey: String get() = getString("adapter.api_key", "")

    // --- 微信读书回退通道（官方直连 weread.qq.com） ---
    val wereadWebBaseUrl: String get() = getString("weread.web_base_url", "https://weread.qq.com")
    val wereadCredentialsFile: String get() = getString("weread.credentials_file", "data/.weread_web_credentials.json")

    // 微信读书 E-Ink 增量通道
    val wereadEinkEnabled: Boolean get() = getBool("weread.eink.enabled", true)
    val wereadEinkBaseUrl: String get() = getString("weread.eink.base_url", "https://i.weread.qq.com")
    val wereadEinkCredentialsFile: String
        get() = getString("weread.eink.credentials_file", "data/.weread_eink_credentials.json")
    val wereadEinkSyncInterval: Long
        get() = getLong("weread.eink.sync_interval", 86_400L).coerceAtLeast(60L)
    val wereadEinkPageSize: Int
        get() = getInt("weread.eink.page_size", 50).coerceIn(1, 100)
    val wereadEinkMaxPages: Int
        get() = getInt("weread.eink.max_pages", 1000).coerceAtLeast(1)
    val wereadEinkRefreshCheckInterval: Long
        get() = getLong("weread.eink.refresh_check_interval", 60L).coerceAtLeast(10L)
    val wereadEinkHealthCheckInterval: Long
        get() = getLong("weread.eink.health_check_interval", 300L).coerceAtLeast(30L)
    val wereadEinkHumanVerifyProbeInterval: Long
        get() = getLong("weread.eink.human_verify_probe_interval", 3_600L)
            .coerceAtLeast(wereadEinkHealthCheckInterval)
    val wereadEinkRefreshSkew: Long
        get() = getLong("weread.eink.refresh_skew", 300L).coerceAtLeast(0L)
    val wereadEinkRefreshRetryInitial: Long
        get() = getLong("weread.eink.refresh_retry_initial", 30L).coerceAtLeast(1L)
    val wereadEinkRefreshRetryMax: Long
        get() = getLong("weread.eink.refresh_retry_max", 900L).coerceAtLeast(wereadEinkRefreshRetryInitial)
    val wereadEinkSyncRetryBackoff: Long
        get() = getLong("weread.eink.sync_retry_backoff", 300L).coerceAtLeast(10L)
    val wereadEinkMaxTransientRetries: Int
        get() = getInt("weread.eink.max_transient_retries", 2).coerceIn(0, 5)
    val wereadEinkRetryBaseDelay: Long
        get() = getLong("weread.eink.retry_base_delay", 2L).coerceAtLeast(1L)
    val wereadEinkMaxConcurrentPerAccount: Int
        get() = getInt("weread.eink.max_concurrent_per_account", 1).coerceAtLeast(1)
    val wereadEinkMinRequestInterval: Long
        get() = getLong("weread.eink.min_request_interval", 1L).coerceAtLeast(0L)
    /** 自动生成并通过机器人投递人工扫码二维码；与 ADB 无人值守扫码互不依赖。 */
    val wereadEinkAutoQrNotificationEnabled: Boolean
        get() = getBool("weread.eink.auto_qr_notification_enabled", true)
    val wereadEinkReloginRetryBackoff: Long
        get() = getLong("weread.eink.relogin_retry_backoff", 300L).coerceAtLeast(30L)
    val wereadWebHealthCheckInterval: Long
        get() = getLong("weread.health_check_interval", 300L).coerceAtLeast(30L)
    val wereadWebProbeFreshness: Long
        get() = getLong("weread.web_probe_freshness", wereadWebHealthCheckInterval * 2L)
            .coerceAtLeast(wereadWebHealthCheckInterval)

    // 多账号池：账号凭证存储目录 + 频控冷却时长
    val wereadAccountDir: String get() = getString("weread.account_dir", "data/weread_accounts")
    val wereadFreqControlCooldown: Long get() = getLong("weread.freq_control_cooldown", 300L)

    // wr_skey 双层刷新保活（wr_skey 有效期 5400s，刷新间隔必须 < 5400s）
    val wereadRefreshInterval: Long get() = getLong("weread.refresh_interval", 3000L)   // 50 分钟
    val wereadRefreshFailThreshold: Int get() = getInt("weread.refresh_fail_threshold", 3)
    /** Web 凭证失效后自动生成并通过机器人投递人工扫码二维码。 */
    val wereadAutoQrNotificationEnabled: Boolean
        get() = getBool("weread.auto_qr_notification_enabled", true)
    val wereadReloginRetryBackoff: Long
        get() = getLong("weread.relogin_retry_backoff", 300L).coerceAtLeast(30L)

    // 翻页控制
    val wereadContentInterval: Long get() = getLong("weread.content_interval", 5L)      // 翻页基础间隔（秒）
    val wereadContentJitter: Long get() = getLong("weread.content_jitter", 5L)          // 随机抖动（秒）
    val wereadMaxPages: Int get() = getInt("weread.max_pages", 50)
    val wereadPageSize: Int get() = getInt("weread.page_size", 10)

    // 请求节流（防多任务并发叠加触发风控）
    val wereadMaxConcurrentPerAccount: Int get() = getInt("weread.max_concurrent_per_account", 1)  // 单账号同时在途请求数
    val wereadMinRequestInterval: Long get() = getLong("weread.min_request_interval", 2L)          // 同一账号两次请求最小间隔（秒）

    // 空页重试
    val wereadEmptyRetryMax: Int get() = getInt("weread.empty_retry_max", 3)
    val wereadEmptyRetryBackoff: Long get() = getLong("weread.empty_retry_backoff", 15L)

    // ADB 自动恢复设备探查
    val adbPollIntervalMs: Long
        get() = getLong("adb.poll_interval_ms", 15_000L).coerceAtLeast(1_000L)
    val adbEnabled: Boolean get() = getBool("adb.enabled", false)
    val adbRecoveryMaxDelayMs: Long
        get() = getLong("adb.recovery_max_delay_ms", 15 * 60_000L).coerceAtLeast(adbPollIntervalMs)

    /** 微信读书登录优先由 AAOS 无障碍责任链承接（服务端只推码 + 下发任务），关闭则回退服务端 ADB 坐标管线。 */
    val adbAaosLoginEnabled: Boolean get() = getBool("adb.aaos_login_enabled", true)

    /** AAOS 登录链的凭证等待超时（秒），需覆盖扫码页加载、选图与授权确认。 */
    val adbAaosLoginPollTimeout: Long
        get() = getLong("adb.aaos_login_poll_timeout", 120L).coerceAtLeast(60L)

    // 预热（拉文章前先调公众号信息接口触发关联）
    val wereadWarmupEnabled: Boolean get() = getBool("weread.warmup_enabled", true)

    // --- 主通道（appmsgpublish）重试策略 ---
    val mainEnabled: Boolean get() = getBool("wx.main.enabled", false)
    val mainRetryMax: Int get() = getInt("wx.main.retry_max", 3)
    val mainBackoffBase: Long get() = getLong("wx.main.backoff_base", 5L)
    val mainBackoffMax: Long get() = getLong("wx.main.backoff_max", 60L)
    val mainDegradedDuration: Long get() = getLong("wx.main.degraded_duration", 60L)    // 未知临时限流的降级时间

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

    private fun getBool(key: String, default: Boolean): Boolean =
        getRaw(key)?.toString()?.toBooleanStrictOrNull() ?: default
}
