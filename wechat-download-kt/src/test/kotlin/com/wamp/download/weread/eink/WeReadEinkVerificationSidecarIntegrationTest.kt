package com.wamp.download.weread.eink

import com.google.gson.JsonParser
import com.sun.net.httpserver.HttpServer
import io.ktor.serialization.gson.gson
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 本地 sidecar 联调（不涉及腾讯云端页、真实站点和真实验证码）：
 * mock sidecar 按 crackTCaptcha `POST /solve` 契约返回 ticket/randstr，
 * 再走我们真实的 complete/status 路由与 Coordinator 状态机。
 *
 * 进程内没有 E-Ink 账号，恢复探测会安全地返回 false，从而验证
 * PROBE_FAILED + nextUrl 的续期路径，全程不产生外部网络请求。
 */
class WeReadEinkVerificationSidecarIntegrationTest {
    private val client = OkHttpClient()

    @Test
    fun `sidecar ticket flows through complete route and probe failure returns a renew link`() {
        val sidecar = startMockSidecar()
        val server = embeddedServer(Netty, port = 0, host = "127.0.0.1") {
            install(ContentNegotiation) { gson() }
            routing { weReadEinkVerificationRoutes() }
        }
        server.start(wait = false)
        try {
            val port = runBlocking { server.resolvedConnectors().first().port }
            val base = "http://127.0.0.1:$port"
            val vid = "vid-sidecar-test"
            val challenge = WeReadEinkHumanVerification.createOrReuse(vid, base)
            val verifyUrl = challenge.verifyUrl
            assertTrue(verifyUrl != null, "challenge must expose a verify url")
            val nonce = verifyUrl!!.substringAfterLast('/')

            val (pageStatus, pageBody, pageHeaders) = get(verifyUrl)
            assertEquals(200, pageStatus)
            assertTrue(pageBody.contains("new TencentCaptcha(captchaAppId"))
            assertTrue(pageBody.contains("/api/weread/eink/verify/$vid/$nonce/complete"))
            val csp = pageHeaders["Content-Security-Policy"].orEmpty()
            assertTrue(csp.contains("script-src 'self' 'unsafe-inline' https: https://ssl.captcha.qq.com:443"), csp)
            assertTrue(csp.contains("connect-src 'self' https: https://ssl.captcha.qq.com:443"), csp)

            val (solveStatus, solveBody) = postJson(
                "http://127.0.0.1:${sidecar.address.port}/solve",
                """{"appid":"2044038556"}""",
            )
            assertEquals(200, solveStatus)
            val solveJson = JsonParser.parseString(solveBody).asJsonObject
            val ticket = solveJson["ticket"].asString
            val randstr = solveJson["randstr"].asString
            assertTrue(ticket.isNotBlank())
            assertTrue(randstr.isNotBlank())

            val (completeStatus, completeBody) = postJson(
                "$base/api/weread/eink/verify/$vid/$nonce/complete",
                """{"ticket":"$ticket","randstr":"$randstr"}""",
            )
            assertEquals(422, completeStatus, completeBody)
            val completeJson = JsonParser.parseString(completeBody).asJsonObject
            assertEquals("PROBE_FAILED", completeJson["code"].asString)
            val nextUrl = completeJson["nextUrl"].asString
            assertTrue(nextUrl.contains("/weread/eink/verify/$vid/"))
            assertFalse(nextUrl.endsWith("$vid/$nonce"), "renew link must use a fresh nonce")

            val (statusCode, statusBody, _) = get("$base/api/weread/eink/verify/$vid/status")
            assertEquals(200, statusCode)
            val statusJson = JsonParser.parseString(statusBody).asJsonObject
            // PROBE_FAILED 后 Coordinator 会立刻生成替换挑战，状态接口暴露的是新挑战。
            assertEquals("WAITING", statusJson["state"].asString)
            assertEquals(nextUrl, statusJson["verifyUrl"].asString)
            assertFalse(statusBody.contains(ticket), "captcha ticket must never leak into status")
            assertFalse(statusBody.contains(randstr), "captcha randstr must never leak into status")
        } finally {
            WeReadEinkHumanVerification.reset()
            server.stop(0, 0)
            sidecar.stop(0)
        }
    }

    private fun startMockSidecar(): HttpServer {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/solve") { exchange ->
            val body = """
                {"ok":true,"ticket":"sidecar-ticket-1","randstr":"@sidecar1","error":"","attempts":1}
            """.trimIndent().toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        return server
    }

    private fun get(url: String): Triple<Int, String, okhttp3.Headers> {
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { response ->
            return Triple(response.code, response.body?.string() ?: "", response.headers)
        }
    }

    private fun postJson(url: String, json: String): Pair<Int, String> {
        val request = Request.Builder()
            .url(url)
            .post(json.toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(request).execute().use { response ->
            return response.code to (response.body?.string() ?: "")
        }
    }
}
