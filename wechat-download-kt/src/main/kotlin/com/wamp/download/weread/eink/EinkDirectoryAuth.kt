package com.wamp.download.weread.eink

import com.wamp.download.weread.WeReadDirectoryAuth

class EinkDirectoryAuth(
    private val session: WeReadEinkSession,
    private val profile: WeReadEinkProfile,
    private val verificationHeaders: () -> Map<String, String> = WeReadEinkHumanVerification::requestHeaders,
) : WeReadDirectoryAuth {
    override fun vid(): String = session.credentials().vid

    override fun headers(): Map<String, String> {
        val credentials = session.credentialsForRequest()
        return profile.versionHeaders + profile.authHeaders(credentials) + verificationHeaders()
    }
}
