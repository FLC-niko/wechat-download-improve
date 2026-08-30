package com.wamp.download.weread.eink

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WeReadEinkSessionTest {
    @Test
    fun `known expired token is refreshed before an authenticated request`() {
        val session = WeReadEinkSession(
            initialCredentials = credentials().copy(accessTokenExpiresAt = 1_500L),
            transport = WeReadEinkTransport {
                WeReadEinkResponse(200, "{\"accessToken\":\"access-2\"}")
            },
            baseUrl = "https://eink.example.test",
            clock = { 2_000L },
        )

        assertEquals("access-2", session.credentialsForRequest().accessToken)
    }

    @Test
    fun `unknown expiry does not trigger guessed time based refresh`() {
        var requests = 0
        val credentials = credentials().copy(accessTokenIssuedAt = 1L, accessTokenExpiresAt = null)
        val session = WeReadEinkSession(
            initialCredentials = credentials,
            transport = WeReadEinkTransport {
                requests++
                error("must not refresh without an explicit expiry or auth rejection")
            },
            clock = { Long.MAX_VALUE },
        )

        assertEquals(credentials, session.credentialsForRequest())
        assertEquals(0, requests)
    }

    @Test
    fun `refresh failure keeps the previous credentials in memory and on disk`() {
        val credentials = credentials()
        val credentialFile = Files.createTempFile("weread-eink-credentials-", ".json").toFile()
        val store = WeReadEinkCredentialStore(credentialFile)
        store.save(credentials)
        val session = WeReadEinkSession(
            initialCredentials = credentials,
            transport = WeReadEinkTransport {
                WeReadEinkResponse(503, "service unavailable")
            },
            credentialStore = store,
            baseUrl = "https://eink.example.test",
        )

        try {
            assertFailsWith<WeReadEinkApiException> { session.refresh() }

            assertEquals(credentials, session.credentials())
            assertEquals(credentials, store.load())
        } finally {
            File(credentialFile.absolutePath).delete()
        }
    }

    private fun credentials() = WeReadEinkCredentials(
        vid = "vid-1",
        accessToken = "access-1",
        refreshToken = "refresh-1",
        deviceId = "device-1",
    )
}
