package com.wamp.download.weread.eink

import com.wamp.download.config.AppConfig
import com.wamp.download.notify.WebhookNotifier
import com.wamp.download.store.SubscriptionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * E-Ink 多账号运行时。
 *
 * - 每个账号（vid）拥有独立的 session/service、鉴权状态、探测历史与人工验证提醒窗口；
 * - 任务路径按 least_used 选号，`HUMAN_VERIFY_REQUIRED` / `REAUTH_*` 的账号自动熔断跳过，
 *   多账号之间互不拖累；
 * - 每个账号使用自己的设备身份（profile）请求，绝不共享指纹。
 */
object WeReadEinkRuntime {
    private val logger = LoggerFactory.getLogger(WeReadEinkRuntime::class.java)
    private val runtimeLock = Any()
    private val credentialWriteLock = Any()

    /** 全部账号运行时（vid → runtime）。 */
    private val accounts = ConcurrentHashMap<String, AccountRuntime>()

    /** 主账号：状态接口的兼容视图与验证流程的目标账号。 */
    @Volatile
    private var primaryVid: String? = null

    @Volatile
    private var recoveryScope: CoroutineScope? = null

    /** 最近一次被任务/后台路径选中的账号（无 service 参数回传时的兜底定位）。 */
    @Volatile
    private var lastSelectedVid: String? = null

    /** 同一封禁期间人工验证提醒最小间隔（3 小时）；恢复成功后再次 -2041 会立即提醒。 */
    private const val HUMAN_VERIFY_REMINDER_INTERVAL_MS = 3 * 60 * 60 * 1000L

    /** 每个账号独立的运行时状态。 */
    private class AccountRuntime(
        val record: WeReadEinkAccountRecord,
        val session: WeReadEinkSession,
        val service: WeReadEinkSyncService,
    ) {
        val vid: String get() = record.vid

        /** 每次 session 被替换时递增；用于让旧的异步回调失效。 */
        @Volatile
        var generation: Long = 1L

        @Volatile
        var authState: String = "UNKNOWN"

        @Volatile
        var lastProbeAt: Long = 0L

        @Volatile
        var lastProbeResult: String = "NEVER"

        @Volatile
        var lastProbeError: String? = null

        @Volatile
        var lastScheduledSyncAt: Long = 0L

        @Volatile
        var lastScheduledSyncResult: String = "NEVER"

        @Volatile
        var lastScheduledSyncTarget: String? = null

        @Volatile
        var lastUsedAt: Long = 0L

        val humanVerificationReported = AtomicBoolean(false)

        val lastHumanVerifyNotificationAt = AtomicLong(0L)
    }

    fun initialize(scope: CoroutineScope? = null): WeReadEinkSyncService? {
        if (scope != null) recoveryScope = scope
        return reload()
    }

    /** Backwards-compatible startup entry point. */
    fun createSyncService(): WeReadEinkSyncService? = initialize()

    /** 任务路径的选号入口：返回 least_used 可用账号的 service；全部熔断时返回 null。 */
    fun syncService(): WeReadEinkSyncService? = selectForTask()?.service

    /** 兼容访问：主账号 session（健康检查等旧调用点使用）。 */
    fun session(): WeReadEinkSession? = synchronized(runtimeLock) { primaryAccount()?.session }

    fun canAttemptTaskSync(): Boolean = synchronized(runtimeLock) {
        accounts.values.any { !taskSyncSuppressed(it.authState) }
    }

    fun requiresHumanVerification(): Boolean = synchronized(runtimeLock) {
        primaryAccount()?.authState == "HUMAN_VERIFY_REQUIRED"
    }

    /** Keeps automatic recovery detection, but backs off aggressively while human action is required. */
    fun shouldRunHealthProbe(now: Long = System.currentTimeMillis()): Boolean = synchronized(runtimeLock) {
        accounts.values.any {
            !healthProbeSuppressed(
                state = it.authState,
                lastProbeAt = it.lastProbeAt,
                now = now,
                humanVerifyProbeIntervalMs = AppConfig.wereadEinkHumanVerifyProbeInterval * 1000L,
            )
        }
    }

    /** 任务请求成功：把结果记到对应账号（service 精确定位，缺失时回退最近选号/主账号）。 */
    fun recordTaskRequestSuccess(service: WeReadEinkSyncService? = null) {
        val account = accountFor(service) ?: return
        updateAccount(account.vid) {
            it.authState = "HEALTHY"
            it.humanVerificationReported.set(false)
            it.lastHumanVerifyNotificationAt.set(0L)
        }
    }

    fun recordTaskRequestFailure(service: WeReadEinkSyncService?, error: Throwable) {
        val account = accountFor(service) ?: return
        val updated = updateAccount(account.vid) {
            it.authState = authStateAfterFailure(it.session.refreshStatus().authExpired, error)
        }
        if (!updated) return
        if (error is WeReadEinkApiException && error.kind == WeReadEinkResponseKind.HUMAN_VERIFY) {
            notifyHumanVerification(account, error)
        } else {
            ensureAutoRecovery()
        }
    }

    /**
     * 安装/更新一个 E-Ink 账号凭证。
     *
     * @param deviceModel 新账号首次登录时分配的设备型号（不共享 profile）；
     *   已存在的账号保持原设备身份，避免指纹漂移。
     */
    fun installCredentials(
        credentials: WeReadEinkCredentials,
        deviceModel: WeReadEinkDeviceModel? = null,
    ) {
        // Invalidate live sessions before writing the QR result. This closes the race where a
        // task holding an old session could persist its rotated snapshot after QR login.
        synchronized(runtimeLock) { accounts.values.forEach { it.generation++ } }
        synchronized(credentialWriteLock) {
            val store = accountStore()
            val existing = store.loadRecord(credentials.vid)
            val record = when {
                existing != null -> existing.copy(credentials = credentials)
                deviceModel != null -> WeReadEinkAccountRecord.forNewAccount(credentials, deviceModel)
                else -> WeReadEinkAccountRecord.migrated(credentials)
            }
            store.save(record)
        }
        val installedService = checkNotNull(reload()) {
            "E-Ink credentials were saved but the runtime could not be reloaded"
        }
        synchronized(runtimeLock) {
            val account = accounts.values.firstOrNull { it.service === installedService } ?: return@synchronized
            account.authState = "HEALTHY"
            account.humanVerificationReported.set(false)
            account.lastHumanVerifyNotificationAt.set(0L)
            account.lastProbeAt = System.currentTimeMillis()
            account.lastProbeResult = "QR_LOGIN_SUCCEEDED"
            account.lastProbeError = null
        }
    }

    fun reload(): WeReadEinkSyncService? = synchronized(runtimeLock) {
        WeReadEinkHumanVerification.reset()
        synchronized(credentialWriteLock) {
            reloadLocked()
        }
    }

    private fun reloadLocked(): WeReadEinkSyncService? {
        if (!AppConfig.wereadEinkEnabled) {
            accounts.clear()
            primaryVid = null
            logger.info("E-Ink 同步通道未启用")
            return null
        }

        val records = try {
            accountStore().loadAll()
        } catch (error: Exception) {
            accounts.clear()
            primaryVid = null
            logger.warn("E-Ink 凭证读取失败，仅使用本地缓存: ${error.message}")
            return null
        }
        if (records.isEmpty()) {
            accounts.clear()
            primaryVid = null
            logger.warn("未找到 E-Ink 凭证；可调用 /api/weread/eink/login/start 扫码登录")
            return null
        }

        val previousPrimary = primaryVid
        val rebuilt = LinkedHashMap<String, AccountRuntime>(records.size)
        for (record in records) {
            val runtime = buildRuntime(record) ?: continue
            rebuilt[record.vid] = runtime
        }
        if (rebuilt.isEmpty()) {
            accounts.clear()
            primaryVid = null
            logger.warn("E-Ink 账号全部初始化失败，仅使用本地缓存")
            return null
        }

        accounts.clear()
        accounts.putAll(rebuilt)
        primaryVid = when {
            previousPrimary != null && rebuilt.containsKey(previousPrimary) -> previousPrimary
            else -> rebuilt.keys.first()
        }
        val primary = rebuilt[primaryVid]!!
        logger.info(
            "E-Ink 同步通道已加载: active=${primary.vid}, model=${primary.record.deviceModel}, " +
                "accounts=${rebuilt.size}, " +
                "expiry=${primary.record.credentials.accessTokenExpiresAt?.let(Instant::ofEpochMilli) ?: "unknown/server-driven"}",
        )
        return primary.service
    }

    /** 为单个账号构建运行时；复用既有账号的统计状态，仅替换 session/service 并递增 generation。 */
    private fun buildRuntime(record: WeReadEinkAccountRecord): AccountRuntime? {
        val existing = accounts[record.vid]
        val generation = (existing?.generation ?: 0L) + 1L
        val profile = record.profile()
        return try {
            lateinit var runtime: AccountRuntime
            val session = WeReadEinkSession(
                initialCredentials = record.credentials,
                credentialStore = accountStore().credentialSink(record.vid),
                profile = profile,
                baseUrl = AppConfig.wereadEinkBaseUrl,
                isCurrentSession = {
                    val current = synchronized(runtimeLock) { accounts[record.vid] }
                    current === runtime && runtime.generation == generation
                },
                persistenceLock = credentialWriteLock,
                refreshRetryInitialDelayMs = AppConfig.wereadEinkRefreshRetryInitial * 1000L,
                refreshRetryMaxDelayMs = AppConfig.wereadEinkRefreshRetryMax * 1000L,
                refreshRequest = { vid, block -> WeReadEinkRequestGate.execute(vid, block) },
                onRefreshFailure = { error -> handleRefreshFailure(record.vid, generation, error) },
                onRefreshSuccess = { updateAccount(record.vid, generation) { it.authState = "HEALTHY" } },
            )
            val service = WeReadEinkSyncService(
                session = session,
                baseUrl = AppConfig.wereadEinkBaseUrl,
                pageSize = AppConfig.wereadEinkPageSize,
                maxPages = AppConfig.wereadEinkMaxPages,
                // 每个账号使用自己的设备身份请求，绝不共享 profile。
                clientFactory = { activeSession, url ->
                    WeReadEinkChaptersClient(
                        credentials = activeSession.credentials(),
                        baseUrl = url,
                        session = activeSession,
                        profile = profile,
                    )
                },
            )
            AccountRuntime(record = record, session = session, service = service).also { built ->
                built.generation = generation
                built.authState = when {
                    existing != null -> existing.authState
                    session.refreshStatus().authExpired -> "REAUTH_REQUIRED"
                    else -> "UNKNOWN"
                }
                built.lastProbeAt = existing?.lastProbeAt ?: 0L
                built.lastProbeResult = existing?.lastProbeResult ?: "NEVER"
                built.lastProbeError = existing?.lastProbeError
                built.lastScheduledSyncAt = existing?.lastScheduledSyncAt ?: 0L
                built.lastScheduledSyncResult = existing?.lastScheduledSyncResult ?: "NEVER"
                built.lastScheduledSyncTarget = existing?.lastScheduledSyncTarget
                built.lastUsedAt = existing?.lastUsedAt ?: 0L
                runtime = built
            }
        } catch (error: Exception) {
            logger.warn("E-Ink 账号初始化失败，已跳过: vid=${record.vid}, ${error.message}")
            null
        }
    }

    /** Proactively refreshes only when WeRead supplied an actual expiry timestamp. */
    suspend fun refreshIfExpiryDue(
        now: Long = System.currentTimeMillis(),
        skewMs: Long = AppConfig.wereadEinkRefreshSkew * 1000L,
    ): Boolean {
        val targets = synchronized(runtimeLock) { accounts.values.toList() }
        var refreshedAny = false
        for (account in targets) {
            val session = account.session
            if (!session.retryPendingPersistence()) {
                WebhookNotifier.notify(
                    "weread_eink_persistence_failed",
                    mapOf(
                        "message" to "微信读书 E-Ink 新凭证已在内存生效，但尚未写入磁盘；请检查磁盘空间和目录权限，期间不要重启服务",
                        "vid" to account.vid,
                        "error" to session.lastPersistenceError(),
                    ),
                )
            }
            val credentials = session.credentials()
            if (session.refreshStatus().authExpired) {
                ensureAutoRecovery()
                continue
            }
            if (!credentials.accessTokenNeedsRefresh(now, skewMs)) continue
            try {
                session.refresh()
                updateAccount(account.vid) {
                    it.authState = "HEALTHY"
                    it.humanVerificationReported.set(false)
                    it.lastHumanVerifyNotificationAt.set(0L)
                }
                logger.info("E-Ink accessToken 已按服务端过期时间续签: vid=${account.vid}")
                refreshedAny = true
            } catch (_: WeReadEinkRefreshBackoffException) {
                ensureAutoRecovery()
            } catch (error: Exception) {
                logger.warn("E-Ink accessToken 主动续签失败，将在鉴权拒绝时再次尝试: vid=${account.vid}, ${error.message}")
            }
        }
        return refreshedAny
    }

    /** Uses one known public-account id for a real authenticated probe without advancing sync state. */
    fun probe(): Boolean {
        val verificationProbe = WeReadEinkRequestGate.isVerificationProbeRequest()
        val targets = synchronized(runtimeLock) {
            if (verificationProbe) {
                // 人工验证票据只属于主账号：验证后只探测目标账号，避免票据被其他账号消耗。
                listOfNotNull(primaryAccount())
            } else {
                accounts.values.sortedBy { it.vid }
            }
        }
        if (targets.isEmpty()) return false
        var anySucceeded = false
        for (account in targets) {
            if (probeAccount(account)) anySucceeded = true
        }
        return anySucceeded
    }

    private fun probeAccount(account: AccountRuntime): Boolean {
        val vid = account.vid
        val verificationProbe = WeReadEinkRequestGate.isVerificationProbeRequest()
        if (verificationProbe) WeReadEinkRequestGate.beginVerification(vid)
        try {
            val target = SubscriptionStore.list(limit = PROBE_SUBSCRIPTION_LIMIT).first
                .firstOrNull(account.service::canSync)
            if (target == null) {
                updateAccount(vid) {
                    it.lastProbeAt = System.currentTimeMillis()
                    it.lastProbeResult = "NO_TARGET"
                    it.lastProbeError = null
                }
                return false
            }
            account.service.probe(target)
            val updated = updateAccount(vid) {
                it.authState = "HEALTHY"
                it.lastProbeAt = System.currentTimeMillis()
                it.lastProbeResult = "SUCCESS"
                it.lastProbeError = null
            }
            if (updated) {
                account.humanVerificationReported.set(false)
                account.lastHumanVerifyNotificationAt.set(0L)
            }
            return updated
        } catch (error: Exception) {
            val updated = updateAccount(vid) {
                it.authState = authStateAfterFailure(it.session.refreshStatus().authExpired, error)
                it.lastProbeAt = System.currentTimeMillis()
                it.lastProbeResult = when (error) {
                    is WeReadEinkApiException -> error.kind.name
                    is WeReadEinkRefreshBackoffException -> "REFRESH_BACKOFF"
                    is WeReadEinkParseException -> "PARSE_ERROR"
                    else -> "ERROR"
                }
                it.lastProbeError = error.message.orEmpty().take(500)
            }
            if (updated) {
                if (error is WeReadEinkApiException && error.kind == WeReadEinkResponseKind.HUMAN_VERIFY) {
                    notifyHumanVerification(account, error)
                } else {
                    ensureAutoRecovery()
                }
            }
            return false
        } finally {
            if (verificationProbe) WeReadEinkRequestGate.endVerification(vid)
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
        if (!backgroundWorkAllowed()) return false
        val account = synchronized(runtimeLock) {
            accounts.values
                .filter { !scheduledSyncSuppressed(it.authState) }
                .minWithOrNull(compareBy<AccountRuntime> { it.lastUsedAt }.thenBy { it.vid })
                ?.also {
                    it.lastUsedAt = System.currentTimeMillis()
                    lastSelectedVid = it.vid
                }
        } ?: return false
        if (account.session.refreshStatus().authExpired) {
            ensureAutoRecovery()
            return false
        }
        val service = account.service
        val target = SubscriptionStore.list(limit = SCHEDULED_SYNC_SUBSCRIPTION_LIMIT).first
            .asSequence()
            .filter(service::canSync)
            .firstOrNull { service.isSyncDue(it, AppConfig.wereadEinkSyncInterval, now) }
            ?: return false

        updateAccount(account.vid) {
            it.lastScheduledSyncAt = System.currentTimeMillis()
            it.lastScheduledSyncTarget = target.fakeid
        }
        return try {
            service.sync(target, backgroundWorkAllowed)
            val updated = updateAccount(account.vid) {
                it.authState = "HEALTHY"
                it.lastScheduledSyncResult = "SUCCESS"
            }
            if (updated) {
                account.humanVerificationReported.set(false)
                account.lastHumanVerifyNotificationAt.set(0L)
            }
            updated
        } catch (_: WeReadEinkBackgroundYieldException) {
            updateAccount(account.vid) { it.lastScheduledSyncResult = "YIELDED_TO_REALTIME_TASK" }
            logger.info("E-Ink 后台增量同步已让出给实时任务: vid=${account.vid}")
            false
        } catch (error: Exception) {
            val updated = updateAccount(account.vid) {
                if (error is WeReadEinkApiException && error.kind == WeReadEinkResponseKind.HUMAN_VERIFY) {
                    it.authState = "HUMAN_VERIFY_REQUIRED"
                }
                it.lastScheduledSyncResult = when (error) {
                    is WeReadEinkApiException -> error.kind.name
                    is WeReadEinkRefreshBackoffException -> "REFRESH_BACKOFF"
                    else -> "FAILED"
                }
            }
            if (updated) {
                if (error is WeReadEinkApiException && error.kind == WeReadEinkResponseKind.HUMAN_VERIFY) {
                    notifyHumanVerification(account, error)
                } else {
                    ensureAutoRecovery()
                }
                logger.warn("E-Ink 后台增量同步失败: vid=${account.vid}, fakeid=${target.fakeid.take(8)}..., ${error.message}")
            }
            false
        }
    }

    fun ensureAutoRecovery(): Boolean {
        val scope = recoveryScope ?: return false
        return synchronized(runtimeLock) {
            val expired = accounts.values.firstOrNull { it.session.refreshStatus().authExpired }
                ?: return@synchronized false
            expired.authState = if (WeReadEinkLoginCoordinator.isInProgress()) {
                "REAUTH_IN_PROGRESS"
            } else {
                "REAUTH_REQUIRED"
            }
            if (!AppConfig.wereadEinkAutoQrNotificationEnabled) return@synchronized false
            val started = WeReadEinkLoginCoordinator.start(scope, automatic = true)
            if (started || WeReadEinkLoginCoordinator.isInProgress()) expired.authState = "REAUTH_IN_PROGRESS"
            started
        }
    }

    private fun handleRefreshFailure(vid: String, generation: Long, error: Throwable) {
        val authExpired = error is WeReadEinkApiException && error.kind == WeReadEinkResponseKind.AUTH_EXPIRED
        val humanVerify = error is WeReadEinkApiException && error.kind == WeReadEinkResponseKind.HUMAN_VERIFY
        val updated = updateAccount(vid, generation) {
            it.authState = when {
                humanVerify -> "HUMAN_VERIFY_REQUIRED"
                authExpired -> "REAUTH_REQUIRED"
                else -> "REFRESH_BACKOFF"
            }
        }
        if (!updated) return
        val account = synchronized(runtimeLock) { accounts[vid] } ?: return
        if (humanVerify) {
            notifyHumanVerification(account, error as WeReadEinkApiException)
            return
        }
        if (authExpired) {
            val message = if (AppConfig.wereadEinkAutoQrNotificationEnabled) {
                "微信读书 E-Ink refreshToken 已失效，正在生成并推送人工扫码二维码"
            } else {
                "微信读书 E-Ink refreshToken 已失效，自动二维码通知已关闭，请调用 /api/weread/eink/login/start 人工恢复"
            }
            recoveryScope?.launch {
                val current = synchronized(runtimeLock) { accounts[vid] }
                if (current !== account) return@launch
                WebhookNotifier.notify(
                    "weread_eink_expired",
                    mapOf(
                        "message" to message,
                        "vid" to vid,
                        "error" to error.message,
                    ),
                )
            } ?: logger.warn("E-Ink 凭证失效通知未发送：应用协程未配置")
            ensureAutoRecovery()
        }
    }

    private fun notifyHumanVerification(account: AccountRuntime, error: WeReadEinkApiException) {
        // 同一封禁期间最多每 3 小时提醒一次（防止机器人刷屏）；恢复成功后窗口清零，
        // 新的 -2041 封禁会立即提醒。CAS 保证并发下只有一条提醒发出。
        val now = System.currentTimeMillis()
        while (true) {
            val last = account.lastHumanVerifyNotificationAt.get()
            if (last > 0L && now - last < HUMAN_VERIFY_REMINDER_INTERVAL_MS) {
                logger.info(
                    "E-Ink 人工验证提醒静默中（每 3 小时最多一条），跳过本次: vid=${account.vid}, 距上次 ${(now - last) / 60_000} 分钟",
                )
                return
            }
            if (account.lastHumanVerifyNotificationAt.compareAndSet(last, now)) break
        }
        account.humanVerificationReported.set(true)
        val verification = WeReadEinkHumanVerification.createOrReuse(AppConfig.siteUrl)
        recoveryScope?.launch {
            val textSent = WebhookNotifier.notify(
                "weread_eink_human_verify",
                mapOf(
                    "message" to "微信读书 E-Ink 触发 -2041；请点击验证链接或扫描随后发送的二维码",
                    "vid" to account.vid,
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
                account.humanVerificationReported.set(false)
            }
        } ?: run {
            account.humanVerificationReported.set(false)
            logger.warn("E-Ink 人工验证通知未发送：应用协程未配置")
        }
    }

    internal fun allowHumanVerificationNotificationRetry() {
        synchronized(runtimeLock) {
            primaryAccount()?.humanVerificationReported?.set(false)
        }
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
        val primary = synchronized(runtimeLock) { primaryAccount() }
        val accountViews = synchronized(runtimeLock) {
            accounts.values.sortedBy { it.vid }.map { accountStatus(it, now) }
        }
        val credentials = primary?.session?.credentials()
            ?: runCatching { accountStore().loadAll().firstOrNull()?.credentials }.getOrNull()
        val refreshStatus = primary?.session?.refreshStatus()
        val currentAuthState = when {
            primary == null -> "UNINITIALIZED"
            WeReadEinkLoginCoordinator.isInProgress() -> "REAUTH_IN_PROGRESS"
            else -> primary.authState
        }
        val runtimeReady = primary != null
        val humanVerifyProbeIntervalMs = AppConfig.wereadEinkHumanVerifyProbeInterval * 1000L
        val probeSuppressed = primary?.let {
            healthProbeSuppressed(
                state = currentAuthState,
                lastProbeAt = it.lastProbeAt,
                now = now,
                humanVerifyProbeIntervalMs = humanVerifyProbeIntervalMs,
            )
        } ?: false
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
            "next_health_probe_at" to if (probeSuppressed) {
                (primary?.lastProbeAt ?: 0L) + humanVerifyProbeIntervalMs
            } else {
                null
            },
            "vid" to credentials?.vid,
            "active_vid" to primary?.vid,
            "accounts" to accountViews,
            "has_access_token" to credentials?.hasAccessToken(),
            "has_refresh_token" to (credentials?.refreshToken?.isNotBlank() == true),
            "access_token_issued_at" to credentials?.accessTokenIssuedAt,
            "access_token_expires_at" to credentials?.accessTokenExpiresAt,
            "access_token_expires_at_iso" to credentials?.accessTokenExpiresAt?.let(Instant::ofEpochMilli)?.toString(),
            "seconds_until_expiry" to credentials?.secondsUntilExpiry(now),
            "expiry_source" to if (credentials?.accessTokenExpiresAt == null) "server-rejection" else "server-response",
            "credentials_persistence_pending" to (primary?.session?.hasPendingPersistence() == true),
            "credentials_persistence_error" to primary?.session?.lastPersistenceError(),
            "last_probe_at" to primary?.lastProbeAt?.takeIf { it > 0L },
            "last_probe_result" to (primary?.lastProbeResult ?: "NEVER"),
            "last_probe_error" to primary?.lastProbeError,
            "last_refresh_attempt_at" to refreshStatus?.lastAttemptAt?.takeIf { it > 0L },
            "last_refresh_success_at" to refreshStatus?.lastSuccessAt?.takeIf { it > 0L },
            "refresh_consecutive_failures" to (refreshStatus?.consecutiveFailures ?: 0),
            "refresh_last_error_kind" to refreshStatus?.lastErrorKind,
            "refresh_last_error" to refreshStatus?.lastError,
            "refresh_next_retry_at" to refreshStatus?.nextRetryAt,
            "last_scheduled_sync_at" to primary?.lastScheduledSyncAt?.takeIf { it > 0L },
            "last_scheduled_sync_result" to (primary?.lastScheduledSyncResult ?: "NEVER"),
            "last_scheduled_sync_target" to primary?.lastScheduledSyncTarget,
        )
    }

    private fun accountStatus(account: AccountRuntime, now: Long): Map<String, Any?> = mapOf(
        "vid" to account.vid,
        "device_model" to account.record.deviceModel,
        "auth_state" to account.authState,
        "operational" to (account.authState == "HEALTHY"),
        "task_sync_suppressed" to taskSyncSuppressed(account.authState),
        "last_used_at" to account.lastUsedAt.takeIf { it > 0L },
        "last_probe_at" to account.lastProbeAt.takeIf { it > 0L },
        "last_probe_result" to account.lastProbeResult,
        "last_probe_error" to account.lastProbeError,
        "last_scheduled_sync_result" to account.lastScheduledSyncResult,
        "seconds_until_expiry" to account.session.credentials().secondsUntilExpiry(now),
    )

    /** 多账号存储：旧单文件仅作为首次迁移来源。 */
    private fun accountStore(): WeReadEinkAccountStore =
        WeReadEinkAccountStore(
            dir = File(AppConfig.wereadEinkAccountsDir),
            legacyFile = File(AppConfig.wereadEinkCredentialsFile),
        )

    private fun primaryAccount(): AccountRuntime? = primaryVid?.let { accounts[it] }

    /** 任务路径选号：过滤熔断账号后取 least_used，保证多账号分摊与故障隔离。 */
    private fun selectForTask(): AccountRuntime? = synchronized(runtimeLock) {
        accounts.values
            .filter { !taskSyncSuppressed(it.authState) }
            .minWithOrNull(compareBy<AccountRuntime> { it.lastUsedAt }.thenBy { it.vid })
            ?.also {
                it.lastUsedAt = System.currentTimeMillis()
                lastSelectedVid = it.vid
            }
    }

    /** 通过 service 实例精确定位账号；缺失时回退最近选号或主账号。 */
    private fun accountFor(service: WeReadEinkSyncService?): AccountRuntime? = synchronized(runtimeLock) {
        if (service != null) {
            accounts.values.firstOrNull { it.service === service }?.let { return@synchronized it }
        }
        lastSelectedVid?.let { accounts[it] } ?: primaryAccount()
    }

    private fun updateAccount(
        vid: String,
        generation: Long? = null,
        update: (AccountRuntime) -> Unit,
    ): Boolean = synchronized(runtimeLock) {
        val account = accounts[vid] ?: return@synchronized false
        if (generation != null && account.generation != generation) return@synchronized false
        update(account)
        true
    }

    private const val PROBE_SUBSCRIPTION_LIMIT = 1_000
    private const val SCHEDULED_SYNC_SUBSCRIPTION_LIMIT = 10_000
}
