package com.wamp.download.weread.eink

import org.slf4j.LoggerFactory

/**
 * Owns one mutable E-Ink credential set and serializes refresh-token rotation.
 * A rotated snapshot becomes active in memory immediately and is persisted atomically.
 * Failed persistence remains pending and is retried without falling back to an invalidated token.
 */
class WeReadEinkSession(
    initialCredentials: WeReadEinkCredentials,
    transport: WeReadEinkTransport = OkHttpWeReadEinkTransport(),
    private val credentialStore: WeReadEinkCredentialStore? = null,
    baseUrl: String = DEFAULT_BASE_URL,
    profile: WeReadEinkProfile = DefaultWeReadEinkProfile,
    private val clock: () -> Long = { System.currentTimeMillis() },
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

    fun credentials(): WeReadEinkCredentials = currentCredentials

    fun hasPendingPersistence(): Boolean = credentialsPendingPersistence != null

    fun lastPersistenceError(): String? = persistenceError

    /** Uses an explicit server expiry when present; unknown expiry remains server-rejection driven. */
    fun credentialsForRequest(): WeReadEinkCredentials {
        retryPendingPersistence()
        val snapshot = currentCredentials
        if (!snapshot.accessTokenNeedsRefresh(clock())) return snapshot
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

        val refreshed = loginClient.refresh(currentCredentials)
        installRefreshedCredentials(refreshed)
    }

    fun refresh(): WeReadEinkCredentials = synchronized(refreshLock) {
        retryPendingPersistenceLocked(force = false)
        val refreshed = loginClient.refresh(currentCredentials)
        installRefreshedCredentials(refreshed)
    }

    /**
     * Retries a credential snapshot that the server already rotated but the local store could not save.
     * The new in-memory token remains usable while persistence is degraded.
     */
    fun retryPendingPersistence(force: Boolean = false): Boolean = synchronized(refreshLock) {
        retryPendingPersistenceLocked(force)
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
            store.save(pending)
            if (credentialsPendingPersistence == pending) {
                credentialsPendingPersistence = null
                persistenceError = null
                nextPersistenceAttemptAt = 0L
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
    }
}
