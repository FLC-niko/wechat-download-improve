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
import java.util.concurrent.ExecutionException

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

    @Test
    fun `concurrent failed refreshes open one backoff circuit`() {
        var refreshRequests = 0
        var failureCallbacks = 0
        val session = WeReadEinkSession(
            initialCredentials = credentials(),
            transport = WeReadEinkTransport {
                synchronized(this) { refreshRequests++ }
                WeReadEinkResponse(401, "{\"errCode\":-2012}")
            },
            clock = { 1_000L },
            refreshRetryInitialDelayMs = 100L,
            refreshRetryMaxDelayMs = 1_000L,
            onRefreshFailure = { synchronized(this) { failureCallbacks++ } },
        )
        val executor = Executors.newFixedThreadPool(4)

        try {
            val failures = (1..4).map {
                executor.submit<WeReadEinkCredentials> {
                    session.refreshIfAccessTokenWas("access-1")
                }
            }.map { future ->
                assertFailsWith<ExecutionException> { future.get(5, TimeUnit.SECONDS) }.cause
            }

            assertEquals(1, refreshRequests)
            assertEquals(1, failureCallbacks)
            assertTrue(failures.any { it is WeReadEinkApiException })
            assertTrue(failures.any { it is WeReadEinkRefreshBackoffException })
            assertTrue(session.refreshStatus().authExpired)
            assertEquals(1, session.refreshStatus().consecutiveFailures)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `request credentials honor refresh backoff before making another API request`() {
        var refreshRequests = 0
        val session = WeReadEinkSession(
            initialCredentials = credentials().copy(accessTokenExpiresAt = 900L),
            transport = WeReadEinkTransport {
                refreshRequests++
                WeReadEinkResponse(503, "service unavailable")
            },
            baseUrl = "https://eink.example.test",
            clock = { 1_000L },
            refreshRetryInitialDelayMs = 100L,
            refreshRetryMaxDelayMs = 1_000L,
        )

        assertFailsWith<WeReadEinkApiException> { session.refresh() }
        assertFailsWith<WeReadEinkRefreshBackoffException> { session.credentialsForRequest() }
        assertEquals(1, refreshRequests)
    }

    @Test
    fun `retired session drops pending persistence instead of writing old credentials`() {
        val root = Files.createTempDirectory("weread-eink-retired-session-").toFile()
        val credentialFile = File(root, "credentials.json")
        val store = WeReadEinkCredentialStore(credentialFile)
        val current = credentials().copy(accessToken = "new-access", refreshToken = "new-refresh")
        store.save(current)
        val retired = WeReadEinkSession(
            initialCredentials = credentials(),
            transport = WeReadEinkTransport {
                WeReadEinkResponse(200, "{\"accessToken\":\"old-access\",\"refreshToken\":\"old-refresh\"}")
            },
            credentialStore = store,
            baseUrl = "https://eink.example.test",
            isCurrentSession = { false },
        )

        try {
            retired.refresh()

            assertEquals(current, store.load())
            assertFalse(retired.hasPendingPersistence())
        } finally {
            root.deleteRecursively()
        }
    }

    /**
     * 锁序契约回归测试（2026-09 死锁事故）：
     *
     * `persistPendingCredentialsLocked` 会在 `persistenceLock`（运行时传入的
     * credentialWriteLock）临界区内回调 `isCurrentSession`。运行时侧的
     * `reload()` 以 runtimeLock → credentialWriteLock 顺序持锁，因此校验器
     * 一旦在此回调里再获取 runtimeLock，两条路径就会以相反顺序互相等待，
     * 构成 AB-BA 死锁并挂死整个 E-Ink 通道与状态接口。
     *
     * 本用例断言该回调确实在 persistenceLock 临界区内被调用——以此固定
     * “isCurrentSession 必须无锁、非阻塞”的契约，防止未来实现再次引入
     * 反向锁序。
     */
    @Test
    fun `isCurrentSession is invoked under persistenceLock and therefore must stay lock-free`() {
        val persistenceLock = Any()
        var callbackHeldPersistenceLock = false
        var persisted = 0
        val session = WeReadEinkSession(
            initialCredentials = credentials(),
            transport = WeReadEinkTransport {
                WeReadEinkResponse(200, "{\"accessToken\":\"access-2\",\"refreshToken\":\"refresh-2\"}")
            },
            credentialStore = object : WeReadEinkCredentialSink {
                override fun load(): WeReadEinkCredentials? = null

                override fun save(credentials: WeReadEinkCredentials) {
                    persisted++
                }
            },
            baseUrl = "https://eink.example.test",
            persistenceLock = persistenceLock,
            isCurrentSession = {
                callbackHeldPersistenceLock = Thread.holdsLock(persistenceLock)
                true
            },
        )

        val refreshed = session.refresh()

        assertEquals("access-2", refreshed.accessToken)
        assertTrue(callbackHeldPersistenceLock, "isCurrentSession 必须在 persistenceLock 临界区内被调用")
        assertEquals(1, persisted, "校验通过后待持久化凭证必须恰好写回一次")
    }

    private fun credentials() = WeReadEinkCredentials(
        vid = "vid-1",
        accessToken = "access-1",
        refreshToken = "refresh-1",
        deviceId = "device-1",
    )
}
