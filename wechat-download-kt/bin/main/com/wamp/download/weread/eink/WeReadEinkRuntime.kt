package com.wamp.download.weread.eink

import com.wamp.download.config.AppConfig
import com.wamp.download.notify.WebhookNotifier
import com.wamp.download.store.SubscriptionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.io.File
import java.time.Instant

/** Owns the live E-Ink session and can reload it immediately after QR login. */
object WeReadEinkRuntime {
    private val logger = LoggerFactory.getLogger(WeReadEinkRuntime::class.java)
    private val runtimeLock = Any()
    private val credentialWriteLock = Any()

    @Volatile
    private var activeSession: WeReadEinkSession? = null

    @Volatile
    private var activeSyncService: WeReadEinkSyncService? = null

    /** Monotonically changes whenever the live session is replaced or invalidated. */
    @Volatile
    private var sessionGeneration: Long = 0L

    @Volatile
    private var recoveryScope: CoroutineScope? = null

    @Volatile
    private var authState: String = "UNINITIALIZED"

    @Volatile
    private var lastProbeAt: Long = 0L

    @Volatile
    private var lastProbeResult: String = "NEVER"

    @Volatile
    private var lastProbeError: String? = null

    @Volatile
    private var lastScheduledSyncAt: Long = 0L

    @Volatile
    private var lastScheduledSyncResult: String = "NEVER"

    @Volatile
    private var lastScheduledSyncTarget: String? = null

    fun initialize(scope: CoroutineScope? = null): WeReadEinkSyncService? {
        if (scope != null) recoveryScope = scope
        return reload()
    }

    /** Backwards-compatible startup entry point. */
    fun createSyncService(): WeReadEinkSyncService? = initialize()

    fun syncService(): WeReadEinkSyncService? = activeSyncService

    fun session(): WeReadEinkSession? = activeSession

    fun installCredentials(credentials: WeReadEinkCredentials) {
        // Invalidate the old session before writing the QR result. This closes the race where a
        // task holding the old session could persist its rotated snapshot after QR login.
        synchronized(runtimeLock) { sessionGeneration++ }
        synchronized(credentialWriteLock) {
            credentialStore().save(credentials)
        }
        checkNotNull(reload()) { "E-Ink credentials were saved but the runtime could not be reloaded" }
        authState = "HEALTHY"
        lastProbeAt = System.currentTimeMillis()
        lastProbeResult = "QR_LOGIN_SUCCEEDED"
        lastProbeError = null
    }

    fun reload(): WeReadEinkSyncService? = synchronized(runtimeLock) {
        val generation = ++sessionGeneration
        synchronized(credentialWriteLock) {
            reloadLocked(generation)
        }
    }

    private fun reloadLocked(generation: Long): WeReadEinkSyncService? {
        resetRuntimeHistory()
        if (!AppConfig.wereadEinkEnabled) {
            activeSession = null
            activeSyncService = null
            authState = "DISABLED"
            logger.info("E-Ink 同步通道未启用")
            return null
        }

        val credentials = try {
            credentialStore().load()
        } catch (error: Exception) {
            activeSession = null
            activeSyncService = null
            authState = "CREDENTIAL_LOAD_FAILED"
            logger.warn("E-Ink 凭证读取失败，仅使用本地缓存: ${error.message}")
            return null
        }
        if (credentials == null) {
            activeSession = null
            activeSyncService = null
            authState = "UNCONFIGURED"
            logger.warn("未找到 E-Ink 凭证文件；可调用 /api/weread/eink/login/start 扫码登录")
            return null
        }

        return try {
            val session = WeReadEinkSession(
                initialCredentials = credentials,
                credentialStore = credentialStore(),
                baseUrl = AppConfig.wereadEinkBaseUrl,
                isCurrentSession = { sessionGeneration == generation },
                persistenceLock = credentialWriteLock,
                refreshRetryInitialDelayMs = AppConfig.wereadEinkRefreshRetryInitial * 1000L,
                refreshRetryMaxDelayMs = AppConfig.wereadEinkRefreshRetryMax * 1000L,
                refreshRequest = { vid, block -> WeReadEinkRequestGate.execute(vid, block) },
                onRefreshFailure = { error ->
                    if (sessionGeneration == generation) handleRefreshFailure(error)
                },
                onRefreshSuccess = {
                    if (sessionGeneration == generation) authState = "HEALTHY"
                },
            )
            val service = WeReadEinkSyncService(
                session = session,
                baseUrl = AppConfig.wereadEinkBaseUrl,
                pageSize = AppConfig.wereadEinkPageSize,
                maxPages = AppConfig.wereadEinkMaxPages,
            )
            activeSession = session
            activeSyncService = service
            authState = if (session.refreshStatus().authExpired) "REAUTH_REQUIRED" else "UNKNOWN"
            logger.info(
                "E-Ink 同步通道已加载: vid=${credentials.vid}, " +
                    "expiry=${credentials.accessTokenExpiresAt?.let(Instant::ofEpochMilli) ?: "unknown/server-driven"}",
            )
            service
        } catch (error: Exception) {
            activeSession = null
            activeSyncService = null
            authState = "INITIALIZATION_FAILED"
            logger.warn("E-Ink 同步通道初始化失败，仅使用本地缓存: ${error.message}")
            null
        }
    }

    /** Proactively refreshes only when WeRead supplied an actual expiry timestamp. */
    suspend fun refreshIfExpiryDue(
        now: Long = System.currentTimeMillis(),
        skewMs: Long = AppConfig.wereadEinkRefreshSkew * 1000L,
    ): Boolean {
        val session = activeSession ?: return false
        if (!session.retryPendingPersistence()) {
            WebhookNotifier.notify(
                "weread_eink_persistence_failed",
                mapOf(
                    "message" to "微信读书 E-Ink 新凭证已在内存生效，但尚未写入磁盘；请检查磁盘空间和目录权限，期间不要重启服务",
                    "error" to session.lastPersistenceError(),
                ),
            )
        }
        val credentials = session.credentials()
        if (session.refreshStatus().authExpired) {
            ensureAutoRecovery()
            return false
        }
        if (!credentials.accessTokenNeedsRefresh(now, skewMs)) return false
        return try {
            session.refresh()
            authState = "HEALTHY"
            logger.info("E-Ink accessToken 已按服务端过期时间续签")
            true
        } catch (_: WeReadEinkRefreshBackoffException) {
            ensureAutoRecovery()
            false
        } catch (error: Exception) {
            logger.warn("E-Ink accessToken 主动续签失败，将在鉴权拒绝时再次尝试: ${error.message}")
            false
        }
    }

    /** Uses one known public-account id for a real authenticated probe without advancing sync state. */
    fun probe(): Boolean {
        val service = activeSyncService ?: return false
        val target = SubscriptionStore.list(limit = PROBE_SUBSCRIPTION_LIMIT).first
            .firstOrNull(service::canSync)
        lastProbeAt = System.currentTimeMillis()
        if (target == null) {
            lastProbeResult = "NO_TARGET"
            lastProbeError = null
            return false
        }

        return try {
            service.probe(target)
            authState = "HEALTHY"
            lastProbeResult = "SUCCESS"
            lastProbeError = null
            true
        } catch (error: Exception) {
            val refresh = activeSession?.refreshStatus()
            authState = when {
                refresh?.authExpired == true -> "REAUTH_REQUIRED"
                error is WeReadEinkRefreshBackoffException -> "REFRESH_BACKOFF"
                else -> "DEGRADED"
            }
            lastProbeResult = when (error) {
                is WeReadEinkApiException -> error.kind.name
                is WeReadEinkRefreshBackoffException -> "REFRESH_BACKOFF"
                is WeReadEinkParseException -> "PARSE_ERROR"
                else -> "ERROR"
            }
            lastProbeError = error.message.orEmpty().take(500)
            ensureAutoRecovery()
            false
        }
    }

    /** Synchronizes at most one due subscription per scheduler tick to avoid startup request bursts. */
    fun syncNextDueSubscription(now: Long = System.currentTimeMillis() / 1000L): Boolean {
        val service = activeSyncService ?: return false
        if (activeSession?.refreshStatus()?.authExpired == true) {
            ensureAutoRecovery()
            return false
        }
        val target = SubscriptionStore.list(limit = SCHEDULED_SYNC_SUBSCRIPTION_LIMIT).first
            .asSequence()
            .filter(service::canSync)
            .firstOrNull { service.isSyncDue(it, AppConfig.wereadEinkSyncInterval, now) }
            ?: return false

        lastScheduledSyncAt = System.currentTimeMillis()
        lastScheduledSyncTarget = target.fakeid
        return try {
            service.sync(target)
            authState = "HEALTHY"
            lastScheduledSyncResult = "SUCCESS"
            true
        } catch (error: Exception) {
            lastScheduledSyncResult = when (error) {
                is WeReadEinkApiException -> error.kind.name
                is WeReadEinkRefreshBackoffException -> "REFRESH_BACKOFF"
                else -> "FAILED"
            }
            ensureAutoRecovery()
            logger.warn("E-Ink 后台增量同步失败: fakeid=${target.fakeid.take(8)}..., ${error.message}")
            false
        }
    }

    fun ensureAutoRecovery(): Boolean {
        val session = activeSession ?: return false
        if (!session.refreshStatus().authExpired) return false
        authState = if (WeReadEinkLoginCoordinator.isInProgress()) "REAUTH_IN_PROGRESS" else "REAUTH_REQUIRED"
        val scope = recoveryScope ?: return false
        if (!AppConfig.wereadEinkAutoReloginEnabled) return false
        val started = WeReadEinkLoginCoordinator.start(scope, automatic = true)
        if (started || WeReadEinkLoginCoordinator.isInProgress()) authState = "REAUTH_IN_PROGRESS"
        return started
    }

    private fun handleRefreshFailure(error: Throwable) {
        val authExpired = error is WeReadEinkApiException && error.kind == WeReadEinkResponseKind.AUTH_EXPIRED
        authState = if (authExpired) "REAUTH_REQUIRED" else "REFRESH_BACKOFF"
        if (authExpired) {
            val message = if (AppConfig.wereadEinkAutoReloginEnabled) {
                "微信读书 E-Ink refreshToken 已失效，正在请求重新扫码"
            } else {
                "微信读书 E-Ink refreshToken 已失效，自动扫码已关闭，请调用登录接口人工恢复"
            }
            recoveryScope?.launch {
                WebhookNotifier.notify(
                    "weread_eink_expired",
                    mapOf(
                        "message" to message,
                        "error" to error.message,
                    ),
                )
            } ?: logger.warn("E-Ink 凭证失效通知未发送：应用协程未配置")
            ensureAutoRecovery()
        }
    }

    fun status(now: Long = System.currentTimeMillis()): Map<String, Any?> {
        val credentials = activeSession?.credentials()
            ?: runCatching { credentialStore().load() }.getOrNull()
        val refreshStatus = activeSession?.refreshStatus()
        val currentAuthState = if (WeReadEinkLoginCoordinator.isInProgress()) {
            "REAUTH_IN_PROGRESS"
        } else {
            authState
        }
        return mapOf(
            "enabled" to AppConfig.wereadEinkEnabled,
            "configured" to (credentials != null),
            "runtime_ready" to (activeSyncService != null),
            "operational" to (currentAuthState == "HEALTHY"),
            "auth_state" to currentAuthState,
            "vid" to credentials?.vid,
            "has_access_token" to credentials?.hasAccessToken(),
            "has_refresh_token" to (credentials?.refreshToken?.isNotBlank() == true),
            "access_token_issued_at" to credentials?.accessTokenIssuedAt,
            "access_token_expires_at" to credentials?.accessTokenExpiresAt,
            "access_token_expires_at_iso" to credentials?.accessTokenExpiresAt?.let(Instant::ofEpochMilli)?.toString(),
            "seconds_until_expiry" to credentials?.secondsUntilExpiry(now),
            "expiry_source" to if (credentials?.accessTokenExpiresAt == null) "server-rejection" else "server-response",
            "credentials_persistence_pending" to (activeSession?.hasPendingPersistence() == true),
            "credentials_persistence_error" to activeSession?.lastPersistenceError(),
            "last_probe_at" to lastProbeAt.takeIf { it > 0L },
            "last_probe_result" to lastProbeResult,
            "last_probe_error" to lastProbeError,
            "last_refresh_attempt_at" to refreshStatus?.lastAttemptAt?.takeIf { it > 0L },
            "last_refresh_success_at" to refreshStatus?.lastSuccessAt?.takeIf { it > 0L },
            "refresh_consecutive_failures" to (refreshStatus?.consecutiveFailures ?: 0),
            "refresh_last_error_kind" to refreshStatus?.lastErrorKind,
            "refresh_last_error" to refreshStatus?.lastError,
            "refresh_next_retry_at" to refreshStatus?.nextRetryAt,
            "last_scheduled_sync_at" to lastScheduledSyncAt.takeIf { it > 0L },
            "last_scheduled_sync_result" to lastScheduledSyncResult,
            "last_scheduled_sync_target" to lastScheduledSyncTarget,
        )
    }

    private fun credentialStore(): WeReadEinkCredentialStore =
        WeReadEinkCredentialStore(File(AppConfig.wereadEinkCredentialsFile))

    private fun resetRuntimeHistory() {
        lastProbeAt = 0L
        lastProbeResult = "NEVER"
        lastProbeError = null
        lastScheduledSyncAt = 0L
        lastScheduledSyncResult = "NEVER"
        lastScheduledSyncTarget = null
    }

    private const val PROBE_SUBSCRIPTION_LIMIT = 1_000
    private const val SCHEDULED_SYNC_SUBSCRIPTION_LIMIT = 10_000
}
