package com.wamp.download.weread.eink

/**
 * Owns one mutable E-Ink credential set and serializes refresh-token rotation.
 * The session updates memory only after the new snapshot has been persisted.
 */
class WeReadEinkSession(
    initialCredentials: WeReadEinkCredentials,
    transport: WeReadEinkTransport = OkHttpWeReadEinkTransport(),
    private val credentialStore: WeReadEinkCredentialStore? = null,
    baseUrl: String = DEFAULT_BASE_URL,
    profile: WeReadEinkProfile = DefaultWeReadEinkProfile,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val refreshLock = Any()
    private val loginClient = WeReadEinkLoginClient(
        transport = transport,
        baseUrl = baseUrl,
        profile = profile,
    )

    @Volatile
    private var currentCredentials = initialCredentials

    fun credentials(): WeReadEinkCredentials = currentCredentials

    /** Uses an explicit server expiry when present; unknown expiry remains server-rejection driven. */
    fun credentialsForRequest(): WeReadEinkCredentials {
        val snapshot = currentCredentials
        if (!snapshot.accessTokenNeedsRefresh(clock())) return snapshot
        return refreshIfAccessTokenWas(snapshot.accessToken)
    }

    /**
     * Refreshes only if the caller failed with the session's current token.
     * A concurrent caller that observes a rotated token reuses it instead.
     */
    fun refreshIfAccessTokenWas(failedAccessToken: String?): WeReadEinkCredentials = synchronized(refreshLock) {
        val currentAccessToken = currentCredentials.accessToken
        if (failedAccessToken == null && !currentAccessToken.isNullOrBlank()) return@synchronized currentCredentials
        if (!failedAccessToken.isNullOrBlank() && currentAccessToken != failedAccessToken) {
            return@synchronized currentCredentials
        }

        val refreshed = loginClient.refresh(currentCredentials)
        credentialStore?.save(refreshed)
        currentCredentials = refreshed
        refreshed
    }

    fun refresh(): WeReadEinkCredentials = synchronized(refreshLock) {
        val refreshed = loginClient.refresh(currentCredentials)
        credentialStore?.save(refreshed)
        currentCredentials = refreshed
        refreshed
    }

    private companion object {
        const val DEFAULT_BASE_URL = "https://i.weread.qq.com"
    }
}
