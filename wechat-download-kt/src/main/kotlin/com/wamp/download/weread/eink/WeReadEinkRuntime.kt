package com.wamp.download.weread.eink

import com.wamp.download.config.AppConfig
import com.wamp.download.notify.WebhookNotifier
import com.wamp.download.store.SubscriptionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.io.File
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

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

    private val humanVerificationReported = AtomicBoolean(false)

    private data class RuntimeSnapshot(
        val generation: Long,
        val session: WeReadEinkSession,
        val service: WeReadEinkSyncService,
    )

    fun initialize(scope: CoroutineScope? = null): WeReadEinkSyncService? {
        if (scope != null) recoveryScope = scope
        return reload()
    }

    /** Backwards-compatible startup entry point. */
    fun createSyncService(): WeReadEinkSyncService? = initialize()

    fun syncService(): WeReadEinkSyncService? = activeSyncService

    fun session(): WeReadEinkSession? = activeSession

    /** Task requests must not keep replaying an account-side challenge or an active re-login. */
    fun canAttemptTaskSync(): Boolean = synchronized(runtimeLock) {
        !taskSyncSuppressed(authState)
    }

    fun requiresHumanVerification(): Boolean = synchronized(runtimeLock) {
        authState == "HUMAN_VERIFY_REQUIRED"
    }

    /** Keeps automatic recovery detection, but backs off aggressively while human action is required. */
    fun shouldRunHealthProbe(now: Long = System.currentTimeMillis()): Boolean = synchronized(runtimeLock) {
        !healthProbeSuppressed(
            state = authState,
            lastProbeAt = lastProbeAt,
            now = now,
            humanVerifyProbeIntervalMs = AppConfig.wereadEinkHumanVerifyProbeInterval * 1000L,
        )
    }

    /** Feeds task-path outcomes back into the shared circuit breaker. */
    fun recordTaskRequestSuccess() {
        val snapshot = currentSnapshot() ?: return
        if (updateIfCurrent(snapshot) { authState = "HEALTHY" }) {
            humanVerificationReported.set(false)
        }
    }

    fun recordTaskRequestFailure(error: Throwable) {
        val snapshot = currentSnapshot() ?: return
        val updated = updateIfCurrent(snapshot) {
            authState = authStateAfterFailure(snapshot.session.refreshStatus().authExpired, error)
        }
        if (!updated) return
        if (error is WeReadEinkApiException && error.kind == WeReadEinkResponseKind.HUMAN_VERIFY) {
            notifyHumanVerification(error)
        } else {
            ensureAutoRecovery()
        }
    }

    fun installCredentials(credentials: WeReadEinkCredentials) {
        // Invalidate the old session before writing the QR result. This closes the race where a
        // task holding the old session could persist its rotated snapshot after QR login.
        synchronized(runtimeLock) { sessionGeneration++ }
        synchronized(credentialWriteLock) {
            credentialStore().save(credentials)
        }
        val installedService = checkNotNull(reload()) {
            "E-Ink credentials were saved but the runtime could not be reloaded"
        }
        synchronized(runtimeLock) {
            if (activeSyncService === installedService) {
                authState = "HEALTHY"
                humanVerificationReported.set(false)
                lastProbeAt = System.currentTimeMillis()
                lastProbeResult = "QR_LOGIN_SUCCEEDED"
                lastProbeError = null
            }
        }
    }

    fun reload(): WeReadEinkSyncService? = synchronized(runtimeLock) {
        WeReadEinkHumanVerification.reset()
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
                onRefreshFailure = { error -> handleRefreshFailure(error, generation) },
                onRefreshSuccess = {
                    updateAuthStateIfGeneration(generation, "HEALTHY")
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
        val snapshot = currentSnapshot() ?: return false
        val session = snapshot.session
        if (!session.retryPendingPersistence()) {
            if (isCurrent(snapshot)) {
                WebhookNotifier.notify(
                    "weread_eink_persistence_failed",
                    mapOf(
                        "message" to "微信读书 E-Ink 新凭证已在内存生效，但尚未写入磁盘；请检查磁盘空间和目录权限，期间不要重启服务",
                        "error" to session.lastPersistenceError(),
                    ),
                )
            }
        }
        if (!isCurrent(snapshot)) return false
        val credentials = session.credentials()
        if (session.refreshStatus().authExpired) {
            if (isCurrent(snapshot)) ensureAutoRecovery()
            return false
        }
        if (!credentials.accessTokenNeedsRefresh(now, skewMs)) return false
        return try {
            session.refresh()
            if (!updateIfCurrent(snapshot) { authState = "HEALTHY" }) return false
            humanVerificationReported.set(false)
            logger.info("E-Ink accessToken 已按服务端过期时间续签")
            true
        } catch (_: WeReadEinkRefreshBackoffException) {
            if (isCurrent(snapshot)) ensureAutoRecovery()
            false
        } catch (error: Exception) {
            if (isCurrent(snapshot)) {
                logger.warn("E-Ink accessToken 主动续签失败，将在鉴权拒绝时再次尝试: ${error.message}")
            }
            false
        }
    }

    /** Uses one known public-account id for a real authenticated probe without advancing sync state. */
    fun probe(): Boolean {
        val snapshot = currentSnapshot() ?: return false
        val service = snapshot.service
        val target = SubscriptionStore.list(limit = PROBE_SUBSCRIPTION_LIMIT).first
            .firstOrNull(service::canSync)
        if (target == null) {
            updateIfCurrent(snapshot) {
                lastProbeAt = System.currentTimeMillis()
                lastProbeResult = "NO_TARGET"
                lastProbeError = null
            }
            return false
        }

        return try {
            if (!isCurrent(snapshot)) return false
            service.probe(target)
            val updated = updateIfCurrent(snapshot) {
                authState = "HEALTHY"
                lastProbeAt = System.currentTimeMillis()
                lastProbeResult = "SUCCESS"
                lastProbeError = null
            }
            if (updated) humanVerificationReported.set(false)
            updated
        } catch (error: Exception) {
            val updated = updateIfCurrent(snapshot) {
                val refresh = snapshot.session.refreshStatus()
                authState = authStateAfterFailure(refresh.authExpired, error)
                lastProbeAt = System.currentTimeMillis()
                lastProbeResult = when (error) {
                    is WeReadEinkApiException -> error.kind.name
                    is WeReadEinkRefreshBackoffException -> "REFRESH_BACKOFF"
                    is WeReadEinkParseException -> "PARSE_ERROR"
                    else -> "ERROR"
                }
                lastProbeError = error.message.orEmpty().take(500)
            }
            if (updated) {
                if (error is WeReadEinkApiException && error.kind == WeReadEinkResponseKind.HUMAN_VERIFY) {
                    notifyHumanVerification(error)
                } else {
                    ensureAutoRecovery()
                }
            }
            false
        }
    }

    /** Synchronizes at most one due subscription per scheduler tick to avoid startup request bursts. */
    fun syncNextDueSubscription(
        now: Long = System.currentTimeMillis() / 1000L,
        backgroundWorkAllowed: () -> Boolean = { true },
    ): Boolean {
        // -2041 is an account-side challenge. Replaying a different subscription every minute
        // cannot solve it and only adds risk; the slower health probe remains responsible for
        // observing recovery after the user completes verification in the official client.
        if (scheduledSyncSuppressed(authState) || !backgroundWorkAllowed()) return false
        val snapshot = currentSnapshot() ?: return false
        val service = snapshot.service
        if (snapshot.session.refreshStatus().authExpired) {
            if (isCurrent(snapshot)) ensureAutoRecovery()
            return false
        }
        val target = SubscriptionStore.list(limit = SCHEDULED_SYNC_SUBSCRIPTION_LIMIT).first
            .asSequence()
            .filter(service::canSync)
            .firstOrNull { service.isSyncDue(it, AppConfig.wereadEinkSyncInterval, now) }
            ?: return false

        if (!updateIfCurrent(snapshot) {
                lastScheduledSyncAt = System.currentTimeMillis()
                lastScheduledSyncTarget = target.fakeid
            }
        ) return false
        return try {
            service.sync(target, backgroundWorkAllowed)
            val updated = updateIfCurrent(snapshot) {
                authState = "HEALTHY"
                lastScheduledSyncResult = "SUCCESS"
            }
            if (updated) humanVerificationReported.set(false)
            updated
        } catch (_: WeReadEinkBackgroundYieldException) {
            updateIfCurrent(snapshot) { lastScheduledSyncResult = "YIELDED_TO_REALTIME_TASK" }
            logger.info("E-Ink 后台增量同步已让出给实时任务")
            false
        } catch (error: Exception) {
            val updated = updateIfCurrent(snapshot) {
                if (error is WeReadEinkApiException && error.kind == WeReadEinkResponseKind.HUMAN_VERIFY) {
                    authState = "HUMAN_VERIFY_REQUIRED"
                }
                lastScheduledSyncResult = when (error) {
                    is WeReadEinkApiException -> error.kind.name
                    is WeReadEinkRefreshBackoffException -> "REFRESH_BACKOFF"
                    else -> "FAILED"
                }
            }
            if (updated) {
                if (error is WeReadEinkApiException && error.kind == WeReadEinkResponseKind.HUMAN_VERIFY) {
                    notifyHumanVerification(error)
                } else {
                    ensureAutoRecovery()
                }
                logger.warn("E-Ink 后台增量同步失败: fakeid=${target.fakeid.take(8)}..., ${error.message}")
            }
            false
        }
    }

    fun ensureAutoRecovery(): Boolean {
        val snapshot = currentSnapshot() ?: return false
        if (!snapshot.session.refreshStatus().authExpired) return false
        val scope = recoveryScope ?: return false
        return synchronized(runtimeLock) {
            if (!matchesCurrent(snapshot)) return@synchronized false
            authState = if (WeReadEinkLoginCoordinator.isInProgress()) {
                "REAUTH_IN_PROGRESS"
            } else {
                "REAUTH_REQUIRED"
            }
            if (!AppConfig.wereadEinkAutoQrNotificationEnabled) return@synchronized false
            val started = WeReadEinkLoginCoordinator.start(scope, automatic = true)
            if (started || WeReadEinkLoginCoordinator.isInProgress()) authState = "REAUTH_IN_PROGRESS"
            started
        }
    }

    private fun handleRefreshFailure(error: Throwable, generation: Long) {
        val authExpired = error is WeReadEinkApiException && error.kind == WeReadEinkResponseKind.AUTH_EXPIRED
        val humanVerify = error is WeReadEinkApiException && error.kind == WeReadEinkResponseKind.HUMAN_VERIFY
        if (!updateAuthStateIfGeneration(
                generation,
                when {
                    humanVerify -> "HUMAN_VERIFY_REQUIRED"
                    authExpired -> "REAUTH_REQUIRED"
                    else -> "REFRESH_BACKOFF"
                },
            )
        ) return
        if (humanVerify) {
            notifyHumanVerification(error as WeReadEinkApiException)
            return
        }
        if (authExpired) {
            val message = if (AppConfig.wereadEinkAutoQrNotificationEnabled) {
                "微信读书 E-Ink refreshToken 已失效，正在生成并推送人工扫码二维码"
            } else {
                "微信读书 E-Ink refreshToken 已失效，自动二维码通知已关闭，请调用 /api/weread/eink/login/start 人工恢复"
            }
            recoveryScope?.launch {
                if (sessionGeneration != generation) return@launch
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

    private fun notifyHumanVerification(error: WeReadEinkApiException) {
        if (!humanVerificationReported.compareAndSet(false, true)) return
        val verification = WeReadEinkHumanVerification.createOrReuse(AppConfig.siteUrl)
        recoveryScope?.launch {
            val textSent = WebhookNotifier.notify(
                "weread_eink_human_verify",
                mapOf(
                    "message" to "微信读书 E-Ink 触发 -2041；请点击验证链接或扫描随后发送的二维码",
                    "verify_url" to verification.verifyUrl,
                    "expires_at" to verification.expiresAt?.let(Instant::ofEpochMilli)?.toString(),
                    "error" to error.message,
                ),
            )
            val qrSent = verification.verifyUrl?.let { verifyUrl ->
                runCatching {
                    val png = WeReadEinkHumanVerification.generateQrPng(verifyUrl)
                    val qrFile = File("qrcode/weread_eink_human_verify.png")
                    val saved = runCatching {
                        qrFile.parentFile?.mkdirs()
                        qrFile.writeBytes(png)
                    }.onFailure { saveError ->
                        logger.warn("E-Ink 人工验证二维码保存失败: ${saveError.message}")
                    }.isSuccess
                    WebhookNotifier.pushQrcode(
                        imageBytes = png,
                        message = "二维码 5 分钟内有效；扫码完成后服务器会自动探测并恢复 E-Ink 通道",
                        fileName = qrFile.name,
                        localFileAvailable = saved,
                        title = "微信读书 E-Ink 人工验证",
                        heading = "请扫描二维码完成人工验证",
                        targetUrl = verifyUrl,
                    ).anySuccess
                }.onFailure { qrError ->
                    logger.warn("E-Ink 人工验证二维码生成或推送失败: ${qrError.message}")
                }.getOrDefault(false)
            } ?: false
            if (!textSent && !qrSent) {
                humanVerificationReported.set(false)
            }
        } ?: run {
            humanVerificationReported.set(false)
            logger.warn("E-Ink 人工验证通知未发送：应用协程未配置")
        }
    }

    internal fun allowHumanVerificationNotificationRetry() {
        humanVerificationReported.set(false)
    }

    internal fun authStateAfterFailure(refreshAuthExpired: Boolean, error: Throwable): String = when {
        error is WeReadEinkApiException && error.kind == WeReadEinkResponseKind.HUMAN_VERIFY ->
            "HUMAN_VERIFY_REQUIRED"
        refreshAuthExpired -> "REAUTH_REQUIRED"
        error is WeReadEinkRefreshBackoffException -> "REFRESH_BACKOFF"
        else -> "DEGRADED"
    }

    internal fun scheduledSyncSuppressed(state: String): Boolean =
        state == "HUMAN_VERIFY_REQUIRED" || state == "REAUTH_IN_PROGRESS"

    internal fun taskSyncSuppressed(state: String): Boolean =
        state == "HUMAN_VERIFY_REQUIRED" ||
            state == "REAUTH_REQUIRED" ||
            state == "REAUTH_IN_PROGRESS"

    internal fun healthProbeSuppressed(
        state: String,
        lastProbeAt: Long,
        now: Long,
        humanVerifyProbeIntervalMs: Long,
    ): Boolean = state == "HUMAN_VERIFY_REQUIRED" &&
        lastProbeAt > 0L &&
        now >= lastProbeAt &&
        now - lastProbeAt < humanVerifyProbeIntervalMs

    fun status(now: Long = System.currentTimeMillis()): Map<String, Any?> {
        val snapshot = currentSnapshot()
        val credentials = snapshot?.session?.credentials()
            ?: runCatching { credentialStore().load() }.getOrNull()
        val refreshStatus = snapshot?.session?.refreshStatus()
        val currentAuthState = if (WeReadEinkLoginCoordinator.isInProgress()) {
            "REAUTH_IN_PROGRESS"
        } else {
            authState
        }
        val runtimeReady = snapshot != null
        val humanVerifyProbeIntervalMs = AppConfig.wereadEinkHumanVerifyProbeInterval * 1000L
        val probeSuppressed = healthProbeSuppressed(
            state = currentAuthState,
            lastProbeAt = lastProbeAt,
            now = now,
            humanVerifyProbeIntervalMs = humanVerifyProbeIntervalMs,
        )
        return mapOf(
            "enabled" to AppConfig.wereadEinkEnabled,
            "auto_qr_notification_enabled" to AppConfig.wereadEinkAutoQrNotificationEnabled,
            "configured" to (credentials != null),
            "runtime_ready" to runtimeReady,
            "operational" to (runtimeReady && currentAuthState == "HEALTHY"),
            "auth_state" to currentAuthState,
            "task_sync_suppressed" to taskSyncSuppressed(currentAuthState),
            "health_probe_suppressed" to probeSuppressed,
            "human_verify_probe_interval_seconds" to AppConfig.wereadEinkHumanVerifyProbeInterval,
            "human_verification" to WeReadEinkHumanVerification.status(AppConfig.siteUrl),
            "next_health_probe_at" to if (probeSuppressed) lastProbeAt + humanVerifyProbeIntervalMs else null,
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

    private fun currentSnapshot(): RuntimeSnapshot? = synchronized(runtimeLock) {
        val session = activeSession ?: return@synchronized null
        val service = activeSyncService ?: return@synchronized null
        RuntimeSnapshot(sessionGeneration, session, service)
    }

    private fun isCurrent(snapshot: RuntimeSnapshot): Boolean = synchronized(runtimeLock) {
        matchesCurrent(snapshot)
    }

    private fun updateIfCurrent(snapshot: RuntimeSnapshot, update: () -> Unit): Boolean =
        synchronized(runtimeLock) {
            if (!matchesCurrent(snapshot)) return@synchronized false
            update()
            true
        }

    private fun matchesCurrent(snapshot: RuntimeSnapshot): Boolean =
        sessionGeneration == snapshot.generation &&
            activeSession === snapshot.session &&
            activeSyncService === snapshot.service

    private fun updateAuthStateIfGeneration(generation: Long, state: String): Boolean =
        synchronized(runtimeLock) {
            if (sessionGeneration != generation || activeSession == null || activeSyncService == null) {
                return@synchronized false
            }
            authState = state
            true
        }

    private fun resetRuntimeHistory() {
        humanVerificationReported.set(false)
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
