package com.wamp.download.weread.eink

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 完全离线的自动验证冒烟测试。
 *
 * 不访问腾讯、不需要 Linux 或真实 crackTCaptcha：进程内 mock 只模拟 `/solve`
 * 契约。测试同时证明自动恢复主路径可用，以及 sidecar 崩溃时仍能得到有效的
 * 人工审核链接。这里不实际发送 Webhook，避免冒烟测试产生外部副作用。
 */
class WeReadEinkAutoRecoverySmokeTest {
    @Test
    fun `local solver result completes one-time ticket and recovers probe`() {
        val sidecar = startSuccessfulSidecar()
        try {
            lateinit var coordinator: WeReadEinkHumanVerificationCoordinator
            coordinator = WeReadEinkHumanVerificationCoordinator(
                nonceGenerator = { "smoke-success-nonce" },
                probe = {
                    coordinator.requestHeaders() == mapOf(
                        "wr_ticket" to "smoke-ticket",
                        "wr_randstr" to "@smoke-randstr",
                    )
                },
            )
            val vid = "smoke-success-vid"
            val challenge = coordinator.createOrReuse(vid, "http://127.0.0.1:8080")
            val client = solverClient(sidecar.address.port)

            val attempt = performEinkAutoRecovery(
                enabled = true,
                allowlist = setOf(vid),
                vid = vid,
                verification = challenge,
                solve = client::solve,
                complete = coordinator::complete,
            )

            assertEquals(WeReadEinkAutoRecoveryCode.RECOVERED, attempt?.code)
            assertEquals(
                WeReadEinkVerificationState.SUCCEEDED,
                coordinator.status(vid, "http://127.0.0.1:8080").state,
            )
            assertTrue(coordinator.requestHeaders().isEmpty(), "一次性票据必须在探测后清除")
            runBlocking {
                sendEinkManualFallback(
                    attempt,
                    currentVerification = { error("success must not create a manual challenge") },
                    notify = { error("success must not send a manual notification") },
                )
            }
        } finally {
            sidecar.stop(0)
        }
    }

    @Test
    fun `crashed solver keeps a valid manual review link`() {
        // 先取得一个随机空闲端口，关闭 socket 后不在该端口启动服务，用来模拟 sidecar 崩溃。
        val unavailablePort = ServerSocket(0).use { it.localPort }
        val vid = "smoke-fallback-vid"
        val nonce = "smoke-manual-nonce"
        val coordinator = WeReadEinkHumanVerificationCoordinator(
            nonceGenerator = { nonce },
            probe = { error("probe must not run when solver is unavailable") },
        )
        val challenge = coordinator.createOrReuse(vid, "http://127.0.0.1:8080")

        val attempt = performEinkAutoRecovery(
            enabled = true,
            allowlist = setOf(vid),
            vid = vid,
            verification = challenge,
            solve = solverClient(unavailablePort)::solve,
            complete = coordinator::complete,
        )

        assertEquals(WeReadEinkAutoRecoveryCode.SIDE_CAR_UNAVAILABLE, attempt?.code)
        var notification: WeReadEinkVerificationStatus? = null
        runBlocking {
            sendEinkManualFallback(
                attempt,
                currentVerification = { coordinator.createOrReuse(vid, "http://127.0.0.1:8080") },
                notify = { notification = it },
            )
        }
        val manualReview = assertNotNull(notification, "Solver 崩溃必须触发人工通知")
        val manualUrl = assertNotNull(manualReview.verifyUrl)
        assertEquals(WeReadEinkVerificationState.WAITING, manualReview.state)
        assertTrue(manualUrl.endsWith("/weread/eink/verify/$vid/$nonce"))
        assertTrue(coordinator.canOpen(vid, nonce), "Solver 崩溃后人工审核链接必须仍然有效")
    }

    @Test
    fun `probe crash notifies with a fresh nonce and human completion recovers`() {
        val sidecar = startSuccessfulSidecar()
        try {
            var nonceNumber = 0
            var humanApproved = false
            val vid = "smoke-probe-crash-vid"
            val base = "http://127.0.0.1:8080"
            val coordinator = WeReadEinkHumanVerificationCoordinator(
                nonceGenerator = { "smoke-nonce-${++nonceNumber}" },
                probe = {
                    if (!humanApproved) error("simulated probe crash")
                    true
                },
            )
            val initial = coordinator.createOrReuse(vid, base)
            val attempt = performEinkAutoRecovery(
                enabled = true,
                allowlist = setOf(vid),
                vid = vid,
                verification = initial,
                solve = solverClient(sidecar.address.port)::solve,
                complete = coordinator::complete,
            )
            assertEquals(WeReadEinkAutoRecoveryCode.PROBE_FAILED, attempt?.code)
            assertTrue(coordinator.requestHeaders().isEmpty())
            var notification: WeReadEinkVerificationStatus? = null
            runBlocking {
                sendEinkManualFallback(
                    attempt,
                    currentVerification = { coordinator.createOrReuse(vid, base) },
                    notify = { notification = it },
                )
            }
            val manualUrl = assertNotNull(assertNotNull(notification).verifyUrl)
            val freshNonce = manualUrl.substringAfterLast('/')
            assertTrue(manualUrl != initial.verifyUrl, "人工通知不得使用已消费的 nonce")
            assertTrue(coordinator.canOpen(vid, freshNonce))
            humanApproved = true
            val manualCompletion = coordinator.complete(vid, freshNonce, "human-ticket", "human-randstr")
            assertEquals("RECOVERED", manualCompletion.code)
            assertTrue(coordinator.requestHeaders().isEmpty())
            val snapshot = coordinator.status(vid, base).toString() + coordinator.evaluation(vid).toString()
            for (secret in listOf("smoke-ticket", "@smoke-randstr", "human-ticket", "human-randstr")) {
                assertTrue(!snapshot.contains(secret), "状态和评估不得泄露票据")
            }
        } finally {
            sidecar.stop(0)
        }
    }

    private fun solverClient(port: Int) = WeReadEinkCaptchaSolverClient(
        baseUrl = "http://127.0.0.1:$port",
        appId = "smoke-appid",
        secret = "smoke-secret",
        retries = 1,
        timeoutSeconds = 2,
    )

    private fun startSuccessfulSidecar(): HttpServer {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/solve") { exchange ->
            val response = """
                {"ok":true,"ticket":"smoke-ticket","randstr":"@smoke-randstr","attempts":1}
            """.trimIndent().toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        server.start()
        return server
    }
}
