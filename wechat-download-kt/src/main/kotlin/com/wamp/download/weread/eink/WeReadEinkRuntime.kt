package com.wamp.download.weread.eink

import com.wamp.download.config.AppConfig
import com.wamp.download.notify.WebhookNotifier
import com.wamp.download.store.SubscriptionStore
import com.wamp.download.weread.WeReadAccountPool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * E-Ink 多账号运行时。
 *
 * - 每个账号（vid）拥有独立的 session/service、鉴权状态、探测历史与人工验证提醒窗口；
 * - 任务路径按 least_used 选号，`HUMAN_VERIFY_REQUIRED` / `REAUTH_*` 的账号自动熔断跳过，
 *   多账号之间互不拖累；
 * - 每个账号使用自己的设备身份（profile）请求，绝不共享指纹。
 */
/** 调度选号的账号快照：只携带选号所需字段，配合 [WeReadEinkRuntime.leastUsedEinkAccount] 做纯函数决策。 */
internal data class WeReadEinkAccountCandidate(
    val vid: String,
    val lastUsedAt: Long,
    val authState: String,
)

internal data class WeReadEinkHealthProbeCandidate(
    val vid: String,
    val authState: String,
    val lastProbeAt: Long,
    val humanVerifyCycleCount: Int,
)

internal enum class WeReadEinkAutoRecoveryCode {
    RECOVERED,
    SIDE_CAR_UNAVAILABLE,
    SIDE_CAR_UNAUTHORIZED,
    SOLVE_FAILED,
    INVALID_RESULT,
    PROBE_FAILED,
    NOT_ALLOWLISTED,
}

internal data class WeReadEinkAutoRecoveryAttempt(
    val code: WeReadEinkAutoRecoveryCode,
    val attempts: Int? = null,
    val durationMs: Long = 0L,
)

internal fun isAutoSolverEligible(enabled: Boolean, allowlist: Set<String>, vid: String): Boolean =
    enabled && allowlist.isNotEmpty() && vid in allowlist

/** Resolve the current challenge only after the automatic attempt, which may have consumed it. */
internal suspend fun sendEinkManualFallback(
    attempt: WeReadEinkAutoRecoveryAttempt?,
    currentVerification: () -> WeReadEinkVerificationStatus,
    notify: suspend (WeReadEinkVerificationStatus) -> Unit,
) {
    if (attempt?.code == WeReadEinkAutoRecoveryCode.RECOVERED) return
    notify(currentVerification())
}

internal fun performEinkAutoRecovery(
    enabled: Boolean,
    allowlist: Set<String>,
    vid: String,
    verification: WeReadEinkVerificationStatus,
    solve: (entryUrl: String) -> WeReadEinkCaptchaSolveResult,
    complete: (vid: String, nonce: String, ticket: String, randstr: String) -> WeReadEinkVerificationResult,
    maxProbeCycles: Int = 2,
    currentVerification: () -> WeReadEinkVerificationStatus = { verification },
    retryDelayMs: Long = 30_000L,
    probe: () -> Boolean = { false },
    probeRetries: Int = 0,
    probeDelayMs: Long = 15_000L,
): WeReadEinkAutoRecoveryAttempt? {
    if (!enabled) return null
    if (!isAutoSolverEligible(true, allowlist, vid)) {
        return WeReadEinkAutoRecoveryAttempt(WeReadEinkAutoRecoveryCode.NOT_ALLOWLISTED)
    }
    var totalAttempts = 0
    var totalDurationMs = 0L
    var last = WeReadEinkAutoRecoveryAttempt(WeReadEinkAutoRecoveryCode.SOLVE_FAILED)
    // PROBE_FAILED（票据有效但恢复探测仍 -2041）值得用新票据再打一轮：
    // 实测重试一次经常通过，避免直接落到人工兜底消息。
    repeat(maxProbeCycles.coerceAtLeast(1)) { cycle ->
        val current = if (cycle == 0) verification else currentVerification()
        if (cycle > 0) {
            if (probeRetries > 0) {
                // 票据已通过但恢复状态有传播延迟：先轮询探测，确认没恢复再换新票据重解，
                // 避免"已恢复却再解一次"（二次解题通常只得到 captcha iframe not found）。
                repeat(probeRetries) {
                    Thread.sleep(probeDelayMs.coerceAtLeast(0L))
                    if (probe()) {
                        return WeReadEinkAutoRecoveryAttempt(
                            WeReadEinkAutoRecoveryCode.RECOVERED, totalAttempts, totalDurationMs,
                        )
                    }
                }
            } else {
                // 给服务端状态传播留一点时间；IO 线程阻塞可接受。
                Thread.sleep(retryDelayMs.coerceAtLeast(0L))
            }
        }
        val verifyUrl = current.verifyUrl
            ?: return WeReadEinkAutoRecoveryAttempt(
                WeReadEinkAutoRecoveryCode.INVALID_RESULT, totalAttempts, totalDurationMs,
            )
        val nonce = verifyUrl.substringAfterLast('/').takeIf { it.isNotBlank() }
            ?: return WeReadEinkAutoRecoveryAttempt(
                WeReadEinkAutoRecoveryCode.INVALID_RESULT, totalAttempts, totalDurationMs,
            )
        val solved = runCatching { solve(verifyUrl) }.getOrElse {
            WeReadEinkCaptchaSolveResult(
                code = WeReadEinkCaptchaSolveCode.SIDE_CAR_UNAVAILABLE,
                durationMs = 0L,
            )
        }
        totalAttempts += solved.attempts ?: 0
        totalDurationMs += solved.durationMs
        val mapped = when (solved.code) {
            WeReadEinkCaptchaSolveCode.SIDE_CAR_UNAVAILABLE -> WeReadEinkAutoRecoveryCode.SIDE_CAR_UNAVAILABLE
            WeReadEinkCaptchaSolveCode.SIDE_CAR_UNAUTHORIZED -> WeReadEinkAutoRecoveryCode.SIDE_CAR_UNAUTHORIZED
            WeReadEinkCaptchaSolveCode.SOLVE_FAILED -> WeReadEinkAutoRecoveryCode.SOLVE_FAILED
            WeReadEinkCaptchaSolveCode.INVALID_RESULT -> WeReadEinkAutoRecoveryCode.INVALID_RESULT
            WeReadEinkCaptchaSolveCode.SOLVED -> null
        }
        if (mapped != null) {
            last = WeReadEinkAutoRecoveryAttempt(mapped, totalAttempts, totalDurationMs)
            // 仅解题侧偶发失败（SOLVE_FAILED）值得换新挑战再试；基础设施类错误直接返回。
            if (mapped != WeReadEinkAutoRecoveryCode.SOLVE_FAILED) return last
            return@repeat
        }
        val captcha = solved.captcha
            ?: return WeReadEinkAutoRecoveryAttempt(
                WeReadEinkAutoRecoveryCode.INVALID_RESULT, totalAttempts, totalDurationMs,
            )
        val completion = runCatching {
            complete(vid, nonce, captcha.ticket, captcha.randstr)
        }.getOrElse {
            last = WeReadEinkAutoRecoveryAttempt(
                WeReadEinkAutoRecoveryCode.PROBE_FAILED, totalAttempts, totalDurationMs,
            )
            return@repeat
        }
        last = WeReadEinkAutoRecoveryAttempt(
            code = when {
                completion.ok && completion.code == "RECOVERED" -> WeReadEinkAutoRecoveryCode.RECOVERED
                completion.code == "PROBE_FAILED" -> WeReadEinkAutoRecoveryCode.PROBE_FAILED
                else -> WeReadEinkAutoRecoveryCode.INVALID_RESULT
            },
            attempts = totalAttempts,
            durationMs = totalDurationMs,
        )
        if (last.code == WeReadEinkAutoRecoveryCode.RECOVERED) return last
        if (last.code != WeReadEinkAutoRecoveryCode.PROBE_FAILED) return last
    }
    return last
}

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

    /** 同一封禁期间人工验证提醒最小间隔；恢复成功后再次 -2041 会立即提醒。 */
    private val humanVerifyReminderIntervalMs: Long
        get() = AppConfig.wereadEinkHumanVerifyReminderInterval * 1000L

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

        /** 后台同步成功/失败计数：供状态面板展示每账号成功率（YIELDED 不算失败）。 */
        val scheduledSyncSuccessCount = AtomicLong(0L)

        val scheduledSyncFailureCount = AtomicLong(0L)

        val humanVerificationReported = AtomicBoolean(false)

        /** 自动解题/恢复是否进行中：并发的 -2041 事件只允许一个恢复流程在跑。 */
        val autoRecoveryInFlight = AtomicBoolean(false)

        val lastHumanVerifyNotificationAt = AtomicLong(0L)

        /** 上次自动验证尝试时间：同一封禁期间最多每 human_verify_probe_interval 尝试一次。 */
        val lastHumanVerifyAttemptAt = AtomicLong(0L)

        /** 连续被 -2041 的次数：探测退避按 1h→2h→4h→8h→12h 递增，成功后清零。 */
        @Volatile
        var humanVerifyCycleCount: Int = 0
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

    /** 任意账号等待人工验证即为 true（多账号下不再只看主账号）。 */
    fun requiresHumanVerification(): Boolean = synchronized(runtimeLock) {
        accounts.values.any { it.authState == "HUMAN_VERIFY_REQUIRED" }
    }

    fun requiresHumanVerification(vid: String): Boolean = synchronized(runtimeLock) {
        accounts[vid]?.authState == "HUMAN_VERIFY_REQUIRED"
    }

    /** 人工验证链接的定位：优先正在等待验证的账号，否则主账号。 */
    fun verificationTargetVid(): String? = synchronized(runtimeLock) {
        accounts.values.firstOrNull { it.authState == "HUMAN_VERIFY_REQUIRED" }?.vid ?: primaryVid
    }

    fun hasAccount(vid: String): Boolean = synchronized(runtimeLock) { accounts.containsKey(vid) }

    fun primaryVidOrNull(): String? = synchronized(runtimeLock) { primaryVid }

    /** 仅在至少一个账号的独立退避到期时执行健康检查。 */
    fun shouldRunHealthProbe(now: Long = System.currentTimeMillis()): Boolean = dueHealthProbeAccounts(now).isNotEmpty()

    private fun dueHealthProbeAccounts(now: Long): List<AccountRuntime> = synchronized(runtimeLock) {
        val ordered = accounts.values.sortedBy { it.vid }
        val dueVids = dueHealthProbeVids(ordered.map { account ->
            WeReadEinkHealthProbeCandidate(
                vid = account.vid,
                authState = account.authState,
                lastProbeAt = account.lastProbeAt,
                humanVerifyCycleCount = account.humanVerifyCycleCount,
            )
        }, now)
        ordered.filter { it.vid in dueVids }
    }

    internal fun dueHealthProbeVids(
        candidates: List<WeReadEinkHealthProbeCandidate>,
        now: Long,
    ): Set<String> = candidates.asSequence()
        .filterNot { account ->
            healthProbeSuppressed(
                state = account.authState,
                lastProbeAt = account.lastProbeAt,
                now = now,
                humanVerifyProbeIntervalMs = humanVerifyProbeIntervalMs(account.humanVerifyCycleCount),
            )
        }
        .mapTo(mutableSetOf()) { it.vid }

    /**
     * -2041 后探测退避：1h→2h→4h→8h→12h 递增。
     * 固定 1 小时探测会让已风控账号每小时都产生一次 -2041（以及重复验证通知），
     * 指数退避在保持恢复检测的同时显著减少无效请求与通知刷屏。
     */
    private fun humanVerifyProbeIntervalMs(cycle: Int): Long {
        val base = AppConfig.wereadEinkHumanVerifyProbeInterval * 1000L
        val multiplier = when {
            cycle <= 1 -> 1L
            cycle == 2 -> 2L
            cycle == 3 -> 4L
            cycle == 4 -> 8L
            else -> 12L
        }
        return base * multiplier
    }

    /** 任务请求成功：把结果记到对应账号（service 精确定位，缺失时回退最近选号/主账号）。 */
    fun recordTaskRequestSuccess(service: WeReadEinkSyncService? = null) {
        val account = accountFor(service) ?: return
        val previousState = account.authState
        updateAccount(account.vid) {
            it.authState = "HEALTHY"
            it.humanVerificationReported.set(false)
            it.lastHumanVerifyNotificationAt.set(
                humanVerifyNotificationAtAfterSuccess(previousState, it.lastHumanVerifyNotificationAt.get()),
            )
            it.humanVerifyCycleCount = 0
        }
        noteHumanVerifyRecovery(account, previousState)
    }

    /** 人工验证恢复后进入温和请求窗口，避免刚恢复就再次触发 -2041。 */
    private fun noteHumanVerifyRecovery(account: AccountRuntime, previousState: String) {
        if (previousState != "HUMAN_VERIFY_REQUIRED" || account.authState != "HEALTHY") return
        WeReadEinkRequestGate.markHumanVerifyRecovered(account.vid)
        // 新一轮封禁是新的验证事件：重置尝试冷却，确保下次 -2041 能立即再试。
        account.lastHumanVerifyAttemptAt.set(0L)
        // 账号级 -2041 同时熔断了 Web 采集：验证通过后恢复账号池状态，等待下一次探测确认。
        WeReadAccountPool.clearHumanVerificationRequired(account.vid)
        logger.info("E-Ink 账号人工验证已恢复，进入 30 分钟温和请求期: vid=${account.vid}")
    }

    /** 真正脱离人工验证状态后，下一次 -2041 是新的验证事件，应立即提醒。 */
    internal fun humanVerifyNotificationAtAfterSuccess(previousState: String, lastNotifiedAt: Long): Long =
        if (previousState == "HUMAN_VERIFY_REQUIRED") 0L else lastNotifiedAt

    internal fun humanVerifyNotificationSuppressed(
        lastNotifiedAt: Long,
        now: Long,
        intervalMs: Long = humanVerifyReminderIntervalMs,
    ): Boolean = intervalMs > 0L && lastNotifiedAt > 0L && now - lastNotifiedAt < intervalMs

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
            account.humanVerifyCycleCount = 0
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
            // 构建期句柄：isCurrentSession 闭包经 AtomicReference 读取构建结果，
            // 不捕获未初始化的 lateinit；构建完成前误触发只会安全地判为“非当前会话”，
            // 该会话的待写回凭证随即被跳过，绝不会抛未初始化异常。
            val runtimeRef = AtomicReference<AccountRuntime>()
            val session = WeReadEinkSession(
                initialCredentials = record.credentials,
                credentialStore = accountStore().credentialSink(record.vid),
                profile = profile,
                baseUrl = AppConfig.wereadEinkBaseUrl,
                isCurrentSession = {
                    // 无锁校验:该回调在 credentialWriteLock(persistenceLock)临界区内被回调,
                    // 绝不能再获取 runtimeLock——reload() 以 runtimeLock→credentialWriteLock
                    // 顺序持锁,反向嵌套会构成 AB-BA 死锁。accounts 是 ConcurrentHashMap,
                    // 单次 get 本身线程安全,generation 为 volatile。
                    val current = accounts[record.vid]
                    val self = runtimeRef.get()
                    current != null && current === self && self.generation == generation
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
                runtimeRef.set(built)
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

    /** 只探测各自退避已到期的账号，不因其中一个账号到期而提前请求其他账号。 */
    fun probe(now: Long = System.currentTimeMillis()): Boolean {
        val verificationProbe = WeReadEinkRequestGate.isVerificationProbeRequest()
        val targets = if (verificationProbe) {
            synchronized(runtimeLock) {
                // 无 vid 上下文的验证探测（旧调用）只允许主账号，避免票据被其他账号消耗。
                // 人工验证的正式路径使用 probeAccount(vid)，票据只交给目标账号。
                listOfNotNull(primaryAccount())
            }
        } else {
            dueHealthProbeAccounts(now)
        }
        if (targets.isEmpty()) return false
        var anySucceeded = false
        for (account in targets) {
            if (probeAccountInternal(account)) anySucceeded = true
        }
        return anySucceeded
    }

    /** 对指定账号执行恢复探测：人工验证票据按 vid 精确交付给对应账号。 */
    fun probeAccount(vid: String): Boolean {
        val account = synchronized(runtimeLock) { accounts[vid] } ?: return false
        return probeAccountInternal(account)
    }

    private fun probeAccountInternal(account: AccountRuntime): Boolean {
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
            val previousState = account.authState
            val updated = updateAccount(vid) {
                it.authState = "HEALTHY"
                it.lastProbeAt = System.currentTimeMillis()
                it.lastProbeResult = "SUCCESS"
                it.lastProbeError = null
                it.humanVerifyCycleCount = 0
                it.lastHumanVerifyNotificationAt.set(
                    humanVerifyNotificationAtAfterSuccess(previousState, it.lastHumanVerifyNotificationAt.get()),
                )
            }
            if (updated) {
                account.humanVerificationReported.set(false)
                noteHumanVerifyRecovery(account, previousState)
            }
            return updated
        } catch (error: Exception) {
            val humanVerify = error is WeReadEinkApiException &&
                error.kind == WeReadEinkResponseKind.HUMAN_VERIFY
            val updated = updateAccount(vid) {
                it.authState = authStateAfterFailure(it.session.refreshStatus().authExpired, error)
                if (humanVerify) it.humanVerifyCycleCount++
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
            pickLeastUsedAccount(::scheduledSyncSuppressed)?.also {
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
            account.scheduledSyncSuccessCount.incrementAndGet()
            val previousState = account.authState
            val updated = updateAccount(account.vid) {
                it.authState = "HEALTHY"
                it.lastScheduledSyncResult = "SUCCESS"
                it.humanVerifyCycleCount = 0
                it.lastHumanVerifyNotificationAt.set(
                    humanVerifyNotificationAtAfterSuccess(previousState, it.lastHumanVerifyNotificationAt.get()),
                )
            }
            if (updated) {
                account.humanVerificationReported.set(false)
                noteHumanVerifyRecovery(account, previousState)
            }
            updated
        } catch (_: WeReadEinkBackgroundYieldException) {
            updateAccount(account.vid) { it.lastScheduledSyncResult = "YIELDED_TO_REALTIME_TASK" }
            logger.info("E-Ink 后台增量同步已让出给实时任务: vid=${account.vid}")
            false
        } catch (error: Exception) {
            account.scheduledSyncFailureCount.incrementAndGet()
            val updated = updateAccount(account.vid) {
                if (error is WeReadEinkApiException && error.kind == WeReadEinkResponseKind.HUMAN_VERIFY) {
                    it.authState = "HUMAN_VERIFY_REQUIRED"
                    it.humanVerifyCycleCount++
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

    /**
     * Web 采集侧 -2041：账号级风控与 E-Ink 共用，转交浏览器自动验证。
     * 扫码登录不能解除 -2041，只有官方人工验证可以；这里复用同一套解题流程。
     */
    fun requestHumanVerificationRecovery(vid: String, reason: String): Boolean {
        val account = synchronized(runtimeLock) { accounts[vid] }
        if (account == null) {
            logger.info("Web 渠道 -2041：账号未配置 E-Ink 凭证，无法自动验证: vid={}, reason={}", vid, reason)
            return false
        }
        // 标记为等待人工验证：解题完成后的探测成功会以 previousState=HUMAN_VERIFY_REQUIRED
        // 触发 noteHumanVerifyRecovery，把 Web 账号池同步恢复为 ACTIVE。
        account.authState = "HUMAN_VERIFY_REQUIRED"
        logger.warn("Web 渠道 -2041，转交浏览器自动验证: vid={}, reason={}", vid, reason)
        notifyHumanVerification(account, reason)
        return true
    }

    private fun notifyHumanVerification(account: AccountRuntime, error: WeReadEinkApiException) {
        notifyHumanVerification(account, error.message.orEmpty())
    }

    private fun notifyHumanVerification(account: AccountRuntime, reason: String) {
        // 提醒静默只抑制提醒消息本身，不再顺带跳过自动解题：否则一次失败会把自动恢复
        // 一并锁死到静默期（默认 3 小时）结束，账号只能等下一次应用重启才有机会恢复。
        if (!account.autoRecoveryInFlight.compareAndSet(false, true)) {
            logger.info("E-Ink 自动验证进行中，跳过重复触发: vid={}", account.vid)
            return
        }
        val now = System.currentTimeMillis()
        // 同一封禁期间限制尝试频率：Web 侧每 5 分钟的健康检查也会转交恢复请求，
        // 没有冷却会把解题器打成连续轰炸（每次最多两轮 290s 解题）。
        val attemptInterval = AppConfig.wereadEinkHumanVerifyProbeInterval * 1000L
        val lastAttempt = account.lastHumanVerifyAttemptAt.get()
        if (attemptInterval > 0L && lastAttempt > 0L && now - lastAttempt < attemptInterval) {
            account.autoRecoveryInFlight.set(false)
            logger.info(
                "E-Ink 自动验证冷却中（间隔 {} 秒），跳过本次: vid={}, 距上次 {} 分钟",
                AppConfig.wereadEinkHumanVerifyProbeInterval,
                account.vid,
                (now - lastAttempt) / 60_000,
            )
            return
        }
        account.lastHumanVerifyAttemptAt.set(now)
        // 同一次封禁期间按配置间隔提醒；成功恢复后再次 -2041 属于新事件。
        // CAS 保证并发下只有一条提醒占用名额；静默只决定提醒，不影响下方自动解题。
        var notifyAfterAttempt = false
        while (true) {
            val last = account.lastHumanVerifyNotificationAt.get()
            if (humanVerifyNotificationSuppressed(last, now)) {
                logger.info(
                    "E-Ink 人工验证提醒静默中（间隔 {} 秒），仅静默提醒、继续自动解题: vid={}, 距上次 {} 分钟",
                    AppConfig.wereadEinkHumanVerifyReminderInterval,
                    account.vid,
                    (now - last) / 60_000,
                )
                break
            }
            if (account.lastHumanVerifyNotificationAt.compareAndSet(last, now)) {
                notifyAfterAttempt = true
                break
            }
        }
        account.humanVerificationReported.set(true)
        val verification = WeReadEinkHumanVerification.createOrReuse(account.vid, AppConfig.siteUrl)
        val scope = recoveryScope
        if (scope == null) {
            account.autoRecoveryInFlight.set(false)
            account.humanVerificationReported.set(false)
            logger.warn("E-Ink 人工验证通知未发送：应用协程未配置")
            return
        }
        scope.launch {
            try {
                val automaticAttempt = attemptAutomaticHumanVerification(account.vid, verification)
                if (automaticAttempt != null) {
                    logger.info(
                        "E-Ink 自动验证结束: vid=${account.vid}, code=${automaticAttempt.code}, " +
                            "attempts=${automaticAttempt.attempts ?: 0}, duration_ms=${automaticAttempt.durationMs}",
                    )
                }
                if (!notifyAfterAttempt) return@launch
                // The automatic probe may consume the challenge and create a replacement. Always
                // re-read/create the current challenge before notifying so an old nonce is never sent.
                sendEinkManualFallback(
                    attempt = automaticAttempt,
                    currentVerification = {
                        WeReadEinkHumanVerification.createOrReuse(account.vid, AppConfig.siteUrl)
                    },
                    notify = { sendHumanVerificationNotification(account, reason, it) },
                )
            } finally {
                account.autoRecoveryInFlight.set(false)
            }
        }
    }

    private suspend fun attemptAutomaticHumanVerification(
        vid: String,
        verification: WeReadEinkVerificationStatus,
    ): WeReadEinkAutoRecoveryAttempt? = withContext(Dispatchers.IO) {
        performEinkAutoRecovery(
            enabled = AppConfig.wereadEinkAutoSolverEnabled,
            allowlist = AppConfig.wereadEinkAutoSolverVidAllowlist,
            vid = vid,
            verification = verification,
            solve = { entryUrl -> WeReadEinkCaptchaSolverClient.fromConfig().solve(entryUrl) },
            complete = WeReadEinkHumanVerification::complete,
            currentVerification = { WeReadEinkHumanVerification.createOrReuse(vid, AppConfig.siteUrl) },
            probe = { probeAccount(vid) },
            probeRetries = 3,
            probeDelayMs = 15_000L,
        )
    }

    private suspend fun sendHumanVerificationNotification(
        account: AccountRuntime,
        reason: String,
        verification: WeReadEinkVerificationStatus,
    ) {
        val textSent = WebhookNotifier.notify(
                "weread_eink_human_verify",
                mapOf(
                    "message" to "微信读书账号触发 -2041（E-Ink/Web 共用）；请点击验证链接或扫描随后发送的二维码",
                    "vid" to account.vid,
                    "verify_url" to verification.verifyUrl,
                    "expires_at" to verification.expiresAt?.let(Instant::ofEpochMilli)?.toString(),
                    "error" to reason.take(500),
                ),
            )
        val qrSent = verification.verifyUrl?.let { verifyUrl ->
                runCatching {
                    val png = WeReadEinkHumanVerification.generateQrPng(verifyUrl)
                    // 账号隔离：每个账号的二维码存成独立文件，通知并发时不会相互覆盖。
                    val qrFile = File("qrcode/weread_eink_human_verify_${account.vid}.png")
                    val saved = runCatching {
                        qrFile.parentFile?.mkdirs()
                        qrFile.writeBytes(png)
                    }.onFailure { saveError ->
                        logger.warn("E-Ink 人工验证二维码保存失败: ${saveError.message}")
                    }.isSuccess
                    WebhookNotifier.pushQrcode(
                        imageBytes = png,
                        message = "二维码 5 分钟内有效；扫码完成后服务器会自动探测并恢复该账号的 E-Ink 通道",
                        fileName = qrFile.name,
                        localFileAvailable = saved,
                        title = "微信读书 E-Ink 人工验证",
                        heading = "请扫描二维码完成人工验证（账号 ${account.vid}）",
                        targetUrl = verifyUrl,
                    ).anySuccess
                }.onFailure { qrError ->
                    logger.warn("E-Ink 人工验证二维码生成或推送失败: ${qrError.message}")
                }.getOrDefault(false)
        } ?: false
        if (!textSent && !qrSent) {
            account.humanVerificationReported.set(false)
        }
    }

    internal fun allowHumanVerificationNotificationRetry() {
        synchronized(runtimeLock) {
            primaryAccount()?.humanVerificationReported?.set(false)
        }
    }

    internal fun allowHumanVerificationNotificationRetry(vid: String) {
        synchronized(runtimeLock) {
            accounts[vid]?.humanVerificationReported?.set(false)
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

    /**
     * 调度选号核心（纯函数，便于单测）：过滤熔断账号后取 least_used；
     * lastUsedAt 相同时按 vid 升序保证确定性。任务路径与后台同步共用，
     * 差异只在传入的熔断判定（taskSyncSuppressed / scheduledSyncSuppressed）。
     */
    internal fun leastUsedEinkAccount(
        candidates: List<WeReadEinkAccountCandidate>,
        suppressed: (String) -> Boolean,
    ): WeReadEinkAccountCandidate? = candidates
        .filter { !suppressed(it.authState) }
        .minWithOrNull(compareBy<WeReadEinkAccountCandidate> { it.lastUsedAt }.thenBy { it.vid })

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
            "human_verification" to WeReadEinkHumanVerification.statusForVidOrIdle(primary?.vid, AppConfig.siteUrl),
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

    private fun accountStatus(account: AccountRuntime, now: Long): Map<String, Any?> {
        val successCount = account.scheduledSyncSuccessCount.get()
        val failureCount = account.scheduledSyncFailureCount.get()
        val total = successCount + failureCount
        return mapOf(
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
            "human_verify_cycle" to account.humanVerifyCycleCount,
            "scheduled_sync_success_count" to successCount,
            "scheduled_sync_failure_count" to failureCount,
            "scheduled_sync_success_rate" to if (total == 0L) null else {
                (successCount.toDouble() / total * 1000).toLong() / 1000.0
            },
            "seconds_until_expiry" to account.session.credentials().secondsUntilExpiry(now),
        )
    }

    /** 多账号存储：旧单文件仅作为首次迁移来源。 */
    private fun accountStore(): WeReadEinkAccountStore =
        WeReadEinkAccountStore(
            dir = File(AppConfig.wereadEinkAccountsDir),
            legacyFile = File(AppConfig.wereadEinkCredentialsFile),
        )

    private fun primaryAccount(): AccountRuntime? = primaryVid?.let { accounts[it] }

    /** 任务路径选号：过滤熔断账号后取 least_used，保证多账号分摊与故障隔离。 */
    private fun selectForTask(): AccountRuntime? = synchronized(runtimeLock) {
        pickLeastUsedAccount(::taskSyncSuppressed)?.also {
            it.lastUsedAt = System.currentTimeMillis()
            lastSelectedVid = it.vid
        }
    }

    /** 在 accounts 视图上执行选号；调用方必须已持有 runtimeLock。 */
    private fun pickLeastUsedAccount(suppressed: (String) -> Boolean): AccountRuntime? =
        leastUsedEinkAccount(
            accounts.values.map { WeReadEinkAccountCandidate(it.vid, it.lastUsedAt, it.authState) },
            suppressed,
        )?.let { pick -> accounts[pick.vid] }

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
