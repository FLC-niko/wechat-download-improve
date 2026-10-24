package com.wamp.download.weread.eink

import org.slf4j.LoggerFactory
import kotlin.math.min

class WeReadEinkRefreshBackoffException(
    val retryAt: Long?,
    cause: Throwable?,
) : RuntimeException(
    if (retryAt == null) {
        "E-Ink refresh is blocked until credentials are replaced"
    } else {
        "E-Ink refresh is in backoff until $retryAt"
    },
    cause,
)

data class WeReadEinkRefreshStatus(
    val lastAttemptAt: Long = 0L,
    val lastSuccessAt: Long = 0L,
    val consecutiveFailures: Int = 0,
    val lastErrorKind: String? = null,
    val lastError: String? = null,
    val nextRetryAt: Long? = null,
    val authExpired: Boolean = false,
)

/**
 * Owns one mutable E-Ink credential set and serializes refresh-token rotation.
 * A rotated snapshot becomes active in memory immediately and is persisted atomically.
 * Failed persistence remains pending and is retried without falling back to an invalidated token.
 */
class WeReadEinkSession(
    initialCredentials: WeReadEinkCredentials,
    private val transport: WeReadEinkTransport = OkHttpWeReadEinkTransport(),
    private val credentialStore: WeReadEinkCredentialSink? = null,
    baseUrl: String = DEFAULT_BASE_URL,
    private val profile: WeReadEinkProfile = DefaultWeReadEinkProfile,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val isCurrentSession: () -> Boolean = { true },
    private val persistenceLock: Any? = null,
    private val refreshRetryInitialDelayMs: Long = DEFAULT_REFRESH_RETRY_INITIAL_DELAY_MS,
    private val refreshRetryMaxDelayMs: Long = DEFAULT_REFRESH_RETRY_MAX_DELAY_MS,
    private val refreshRequest: (String, () -> WeReadEinkCredentials) -> WeReadEinkCredentials = { _, block -> block() },
    private val onRefreshFailure: (Throwable) -> Unit = {},
    private val onRefreshSuccess: () -> Unit = {},
) {
    private val logger = LoggerFactory.getLogger(WeReadEinkSession::class.java)
    private val refreshLock = Any()
    private val loginClient = WeReadEinkLoginClient(
        transport = transport,
        baseUrl = baseUrl,
        profile = profile,
    )

    @Volatile
    private var currentCredentials = initialCredentials

    @Volatile
    private var credentialsPendingPersistence: WeReadEinkCredentials? = null

    @Volatile
    private var persistenceError: String? = null

    private var nextPersistenceAttemptAt: Long = 0L

    @Volatile
    private var lastRefreshAttemptAt: Long = 0L

    @Volatile
    private var lastRefreshSuccessAt: Long = 0L

    @Volatile
    private var consecutiveRefreshFailures: Int = 0

    @Volatile
    private var lastRefreshErrorKind: String? = null

    @Volatile
    private var lastRefreshError: String? = null

    @Volatile
    private var nextRefreshAttemptAt: Long = 0L

    @Volatile
    private var refreshAuthExpired: Boolean = false

    private var lastRefreshFailure: Throwable? = null

    init {
        require(refreshRetryInitialDelayMs > 0L) { "refreshRetryInitialDelayMs must be positive" }
        require(refreshRetryMaxDelayMs >= refreshRetryInitialDelayMs) {
            "refreshRetryMaxDelayMs must not be less than refreshRetryInitialDelayMs"
        }
    }

    fun credentials(): WeReadEinkCredentials = currentCredentials

    fun directoryClient(baseUrl: String = DEFAULT_BASE_URL): com.wamp.download.weread.WeReadDirectoryClient =
        com.wamp.download.weread.WeReadDirectoryClient(
            baseUrl = baseUrl,
            auth = EinkDirectoryAuth(this, profile),
            transport = transport,
        )

    fun hasPendingPersistence(): Boolean = credentialsPendingPersistence != null

    fun lastPersistenceError(): String? = persistenceError

    fun refreshStatus(): WeReadEinkRefreshStatus = WeReadEinkRefreshStatus(
        lastAttemptAt = lastRefreshAttemptAt,
        lastSuccessAt = lastRefreshSuccessAt,
        consecutiveFailures = consecutiveRefreshFailures,
        lastErrorKind = lastRefreshErrorKind,
        lastError = lastRefreshError,
        nextRetryAt = nextRefreshAttemptAt.takeIf { it in 1 until Long.MAX_VALUE },
        authExpired = refreshAuthExpired,
    )

    /** Uses an explicit server expiry when present; unknown expiry remains server-rejection driven. */
    fun credentialsForRequest(): WeReadEinkCredentials {
        retryPendingPersistence()
        val now = clock()
        if (refreshAuthExpired) {
            throw WeReadEinkRefreshBackoffException(retryAt = null, cause = lastRefreshFailure)
        }
        if (nextRefreshAttemptAt > now) {
            throw WeReadEinkRefreshBackoffException(nextRefreshAttemptAt, lastRefreshFailure)
        }
        val snapshot = currentCredentials
        if (!snapshot.accessTokenNeedsRefresh(now)) return snapshot
        return refreshIfAccessTokenWas(snapshot.accessToken)
    }

    /**
     * Refreshes only if the caller failed with the session's current token.
     * A concurrent caller that observes a rotated token reuses it instead.
     */
    fun refreshIfAccessTokenWas(failedAccessToken: String?): WeReadEinkCredentials = synchronized(refreshLock) {
        retryPendingPersistenceLocked(force = false)
        val currentAccessToken = currentCredentials.accessToken
        if (failedAccessToken == null && !currentAccessToken.isNullOrBlank()) return@synchronized currentCredentials
        if (!failedAccessToken.isNullOrBlank() && currentAccessToken != failedAccessToken) {
            return@synchronized currentCredentials
        }

        refreshLocked()
    }

    fun refresh(): WeReadEinkCredentials = synchronized(refreshLock) {
        retryPendingPersistenceLocked(force = false)
        refreshLocked()
    }

    /** Records a token rejected again after a successful refresh and opens the auth circuit. */
    fun reportAuthenticationFailure(error: WeReadEinkApiException) = synchronized(refreshLock) {
        if (error.kind != WeReadEinkResponseKind.AUTH_EXPIRED || refreshAuthExpired) return@synchronized
        recordRefreshFailureLocked(error, clock(), authFailure = true)
    }

    /**
     * Retries a credential snapshot that the server already rotated but the local store could not save.
     * The new in-memory token remains usable while persistence is degraded.
     */
    fun retryPendingPersistence(force: Boolean = false): Boolean = synchronized(refreshLock) {
        retryPendingPersistenceLocked(force)
    }

    private fun refreshLocked(): WeReadEinkCredentials {
        val now = clock()
        if (refreshAuthExpired) {
            throw WeReadEinkRefreshBackoffException(retryAt = null, cause = lastRefreshFailure)
        }
        if (nextRefreshAttemptAt > now) {
            throw WeReadEinkRefreshBackoffException(nextRefreshAttemptAt, lastRefreshFailure)
        }

        lastRefreshAttemptAt = now
        return try {
            val refreshed = refreshRequest(currentCredentials.vid) {
                loginClient.refresh(currentCredentials)
            }
            val installed = installRefreshedCredentials(refreshed)
            lastRefreshSuccessAt = now
            consecutiveRefreshFailures = 0
            lastRefreshErrorKind = null
            lastRefreshError = null
            nextRefreshAttemptAt = 0L
            refreshAuthExpired = false
            lastRefreshFailure = null
            runCatching(onRefreshSuccess)
                .onFailure { callbackError ->
                    logger.warn("E-Ink 刷新成功回调异常: ${callbackError.message}")
                }
            installed
        } catch (error: Exception) {
            recordRefreshFailureLocked(error, now)
            throw error
        }
    }

    private fun recordRefreshFailureLocked(
        error: Throwable,
        now: Long,
        authFailure: Boolean = error is WeReadEinkApiException &&
            error.kind == WeReadEinkResponseKind.AUTH_EXPIRED,
    ) {
        consecutiveRefreshFailures++
        lastRefreshFailure = error
        lastRefreshErrorKind = when (error) {
            is WeReadEinkApiException -> error.kind.name
            is WeReadEinkParseException -> "PARSE_ERROR"
            else -> error.javaClass.simpleName
        }
        lastRefreshError = error.message.orEmpty().take(500)
        refreshAuthExpired = authFailure
        nextRefreshAttemptAt = if (authFailure) {
            Long.MAX_VALUE
        } else {
            val exponent = (consecutiveRefreshFailures - 1).coerceIn(0, 20)
            val multiplier = 1L shl exponent
            val delay = runCatching { Math.multiplyExact(refreshRetryInitialDelayMs, multiplier) }
                .getOrDefault(refreshRetryMaxDelayMs)
            now + min(delay, refreshRetryMaxDelayMs)
        }
        runCatching { onRefreshFailure(error) }
            .onFailure { callbackError ->
                logger.warn("E-Ink 刷新失败回调异常: ${callbackError.message}")
            }
    }

    private fun installRefreshedCredentials(refreshed: WeReadEinkCredentials): WeReadEinkCredentials {
        // The server may invalidate the old refreshToken as soon as /login succeeds. Keep the
        // rotated snapshot in memory even if disk persistence fails, then retry persistence later.
        currentCredentials = refreshed
        if (credentialStore == null) return refreshed

        credentialsPendingPersistence = refreshed
        persistPendingCredentialsLocked()
        return refreshed
    }

    private fun retryPendingPersistenceLocked(force: Boolean): Boolean {
        if (credentialsPendingPersistence == null) return true
        if (!force && clock() < nextPersistenceAttemptAt) return false
        return persistPendingCredentialsLocked()
    }

    private fun persistPendingCredentialsLocked(): Boolean {
        val pending = credentialsPendingPersistence ?: return true
        val store = credentialStore ?: run {
            credentialsPendingPersistence = null
            persistenceError = null
            return true
        }
        return try {
            synchronized(persistenceLock ?: this) {
                // A QR login may have replaced this session while an old task is still winding
                // down. The old snapshot must never be written over the newly installed one.
                if (!isCurrentSession()) {
                    credentialsPendingPersistence = null
                    persistenceError = null
                    nextPersistenceAttemptAt = 0L
                } else {
                    store.save(pending)
                    if (credentialsPendingPersistence == pending) {
                        credentialsPendingPersistence = null
                        persistenceError = null
                        nextPersistenceAttemptAt = 0L
                    }
                }
            }
            true
        } catch (error: Exception) {
            persistenceError = error.message ?: error.javaClass.simpleName
            nextPersistenceAttemptAt = clock() + PERSISTENCE_RETRY_DELAY_MS
            logger.error(
                "E-Ink 新凭证已在内存生效，但持久化失败；将在后台重试，期间请勿重启服务: ${error.message}",
            )
            false
        }
    }

    private companion object {
        const val DEFAULT_BASE_URL = "https://i.weread.qq.com"
        const val PERSISTENCE_RETRY_DELAY_MS = 30_000L
        const val DEFAULT_REFRESH_RETRY_INITIAL_DELAY_MS = 30_000L
        const val DEFAULT_REFRESH_RETRY_MAX_DELAY_MS = 15 * 60_000L
    }
}
