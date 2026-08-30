package com.wamp.download.weread.eink

import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

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

    @Test
    fun `concurrent auth rejections share one refresh and rotated credentials`() {
        var refreshRequests = 0
        val session = WeReadEinkSession(
            initialCredentials = credentials(),
            transport = WeReadEinkTransport {
                synchronized(this) { refreshRequests++ }
                WeReadEinkResponse(
                    200,
                    "{\"accessToken\":\"access-2\",\"refreshToken\":\"refresh-2\"}",
                )
            },
            baseUrl = "https://eink.example.test",
        )
        val executor = Executors.newFixedThreadPool(4)

        try {
            val results = (1..4).map {
                executor.submit<WeReadEinkCredentials> {
                    session.refreshIfAccessTokenWas("access-1")
                }
            }.map { it.get(5, TimeUnit.SECONDS) }

            assertEquals(1, refreshRequests)
            assertTrue(results.all { it.accessToken == "access-2" })
            assertTrue(results.all { it.refreshToken == "refresh-2" })
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `rotated credentials stay active and are retried after persistence failure`() {
        val root = Files.createTempDirectory("weread-eink-persistence-").toFile()
        val blockedParent = File(root, "blocked").also { it.writeText("not a directory") }
        val credentialFile = File(blockedParent, "credentials.json")
        val store = WeReadEinkCredentialStore(credentialFile)
        val session = WeReadEinkSession(
            initialCredentials = credentials(),
            transport = WeReadEinkTransport {
                WeReadEinkResponse(
                    200,
                    "{\"accessToken\":\"access-2\",\"refreshToken\":\"refresh-2\"}",
                )
            },
            credentialStore = store,
            baseUrl = "https://eink.example.test",
            clock = { 1_000L },
        )

        try {
            val refreshed = session.refresh()

            assertEquals("access-2", refreshed.accessToken)
            assertEquals("refresh-2", session.credentials().refreshToken)
            assertTrue(session.hasPendingPersistence())
            assertTrue(session.lastPersistenceError().orEmpty().isNotBlank())

            assertTrue(blockedParent.delete())
            assertTrue(blockedParent.mkdir())
            assertTrue(session.retryPendingPersistence(force = true))
            assertFalse(session.hasPendingPersistence())
            assertEquals("refresh-2", store.load()?.refreshToken)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun credentials() = WeReadEinkCredentials(
        vid = "vid-1",
        accessToken = "access-1",
        refreshToken = "refresh-1",
        deviceId = "device-1",
    )
}
