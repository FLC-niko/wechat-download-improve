package com.wamp.download.weread.eink

import com.wamp.download.config.AppConfig
import org.slf4j.LoggerFactory
import java.io.File
import java.time.Instant

/** Owns the live E-Ink session and can reload it immediately after QR login. */
object WeReadEinkRuntime {
    private val logger = LoggerFactory.getLogger(WeReadEinkRuntime::class.java)
    private val runtimeLock = Any()

    @Volatile
    private var activeSession: WeReadEinkSession? = null

    @Volatile
    private var activeSyncService: WeReadEinkSyncService? = null

    fun initialize(): WeReadEinkSyncService? = reload()

    /** Backwards-compatible startup entry point. */
    fun createSyncService(): WeReadEinkSyncService? = initialize()

    fun syncService(): WeReadEinkSyncService? = activeSyncService

    fun session(): WeReadEinkSession? = activeSession

    fun installCredentials(credentials: WeReadEinkCredentials) {
        credentialStore().save(credentials)
        reload()
    }

    fun reload(): WeReadEinkSyncService? = synchronized(runtimeLock) {
        if (!AppConfig.wereadEinkEnabled) {
            activeSession = null
            activeSyncService = null
            logger.info("E-Ink 同步通道未启用")
            return@synchronized null
        }

        val credentials = try {
            credentialStore().load()
        } catch (error: Exception) {
            activeSession = null
            activeSyncService = null
            logger.warn("E-Ink 凭证读取失败，仅使用本地缓存: ${error.message}")
            return@synchronized null
        }
        if (credentials == null) {
            activeSession = null
            activeSyncService = null
            logger.warn("未找到 E-Ink 凭证文件；可调用 /api/weread/eink/login/start 扫码登录")
            return@synchronized null
        }

        return@synchronized try {
            val session = WeReadEinkSession(
                initialCredentials = credentials,
                credentialStore = credentialStore(),
                baseUrl = AppConfig.wereadEinkBaseUrl,
            )
            val service = WeReadEinkSyncService(
                session = session,
                baseUrl = AppConfig.wereadEinkBaseUrl,
                pageSize = AppConfig.wereadEinkPageSize,
                maxPages = AppConfig.wereadEinkMaxPages,
            )
            activeSession = session
            activeSyncService = service
            logger.info(
                "E-Ink 同步通道已加载: vid=${credentials.vid}, " +
                    "expiry=${credentials.accessTokenExpiresAt?.let(Instant::ofEpochMilli) ?: "unknown/server-driven"}",
            )
            service
        } catch (error: Exception) {
            activeSession = null
            activeSyncService = null
            logger.warn("E-Ink 同步通道初始化失败，仅使用本地缓存: ${error.message}")
            null
        }
    }

    /** Proactively refreshes only when WeRead supplied an actual expiry timestamp. */
    fun refreshIfExpiryDue(
        now: Long = System.currentTimeMillis(),
        skewMs: Long = AppConfig.wereadEinkRefreshSkew * 1000L,
    ): Boolean {
        val session = activeSession ?: return false
        val credentials = session.credentials()
        if (!credentials.accessTokenNeedsRefresh(now, skewMs)) return false
        return try {
            session.refresh()
            logger.info("E-Ink accessToken 已按服务端过期时间续签")
            true
        } catch (error: Exception) {
            logger.warn("E-Ink accessToken 主动续签失败，将在鉴权拒绝时再次尝试: ${error.message}")
            false
        }
    }

    fun status(now: Long = System.currentTimeMillis()): Map<String, Any?> {
        val credentials = activeSession?.credentials()
            ?: runCatching { credentialStore().load() }.getOrNull()
        return mapOf(
            "enabled" to AppConfig.wereadEinkEnabled,
            "configured" to (credentials != null),
            "runtime_ready" to (activeSyncService != null),
            "vid" to credentials?.vid,
            "has_access_token" to credentials?.hasAccessToken(),
            "has_refresh_token" to (credentials?.refreshToken?.isNotBlank() == true),
            "access_token_issued_at" to credentials?.accessTokenIssuedAt,
            "access_token_expires_at" to credentials?.accessTokenExpiresAt,
            "access_token_expires_at_iso" to credentials?.accessTokenExpiresAt?.let(Instant::ofEpochMilli)?.toString(),
            "seconds_until_expiry" to credentials?.secondsUntilExpiry(now),
            "expiry_source" to if (credentials?.accessTokenExpiresAt == null) "server-rejection" else "server-response",
        )
    }

    private fun credentialStore(): WeReadEinkCredentialStore =
        WeReadEinkCredentialStore(File(AppConfig.wereadEinkCredentialsFile))
}
