package com.wamp.download.weread.eink

import com.google.gson.JsonParser
import io.ktor.serialization.gson.gson
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Uses only a local Ktor instance and a synthetic WAITING challenge. */
class WeReadEinkEvaluationBridgeIntegrationTest {
    @Test
    fun `controlled failed manual callback is preserved through HTTP and Python`() {
        val lab = System.getenv("SOLVER_LAB_PATH")
        assumeTrue(!lab.isNullOrBlank(), "set SOLVER_LAB_PATH for the local collector")
        withServer { base, vid ->
            val verifyUrl = WeReadEinkHumanVerification.status(vid, base).verifyUrl!!
            val nonce = verifyUrl.substringAfterLast('/')
            val request = Request.Builder().url("$base/api/weread/eink/verify/$vid/$nonce/complete")
                .post("""{"ticket":"mock-local-ticket","randstr":"mock-local-randstr"}""".toRequestBody("application/json".toMediaType()))
                .build()
            OkHttpClient().newCall(request).execute().use { response ->
                assertEquals(422, response.code)
                assertEquals("PROBE_FAILED", JsonParser.parseString(response.body!!.string()).asJsonObject["code"].asString)
            }
            val artifactRoot = System.getenv("EINK_EVALUATION_REPORT")?.let { File(it).parentFile }
                ?: Files.createTempDirectory("eink-failure-report-").toFile()
            val report = File(artifactRoot, "eink-controlled-failure.json")
            val log = File(artifactRoot, "eink-controlled-failure.log")
            artifactRoot.mkdirs()
            val builder = ProcessBuilder("python3", "-m", "solver_lab.eink", "--base-url", base,
                "--vid", vid, "--output", report.absolutePath)
                .directory(File(lab!!)).redirectErrorStream(true).redirectOutput(log)
            builder.environment()["PYTHONPATH"] = lab
            val process = builder.start()
            try {
                assertTrue(process.waitFor(15, TimeUnit.SECONDS))
                assertEquals(0, process.exitValue(), log.readText())
            } finally {
                if (process.isAlive) process.destroyForcibly()
            }
            val raw = report.readText()
            val summary = JsonParser.parseString(raw).asJsonObject["summary"].asJsonObject
            assertEquals(1, summary["failed"].asInt)
            assertEquals(2, summary["waiting"].asInt)
            assertEquals(0.0, summary["recovery_success_rate"].asDouble)
            assertFalse(raw.contains("mock-local-ticket"))
            assertFalse(raw.contains("mock-local-randstr"))
            assertFalse(raw.contains(nonce))
            if (System.getenv("EINK_EVALUATION_REPORT") == null) artifactRoot.deleteRecursively()
        }
    }

    @Test
    fun `export controlled page for local script regression`() {
        val lab = System.getenv("SOLVER_LAB_PATH")
        assumeTrue(!lab.isNullOrBlank(), "set SOLVER_LAB_PATH for script-fixture export")
        val fixture = File(lab!!, "fixtures/eink-human-page.html")
        fixture.parentFile.mkdirs()
        fixture.writeText(einkVerificationHtml(
            "controlled-account", "controlled-nonce",
            captchaScriptUrl = "http://127.0.0.1:9/not-running-sdk-fixture.js",
            captchaAppId = "local-fixture",
        ))
        assertTrue(fixture.readText().contains("const captchaAppId = \"local-fixture\""))
    }

    @Test
    fun `evaluation HTTP route exposes sanitized history`() {
        withServer { base, vid ->
            OkHttpClient().newCall(Request.Builder().url("$base/api/weread/eink/verify/$vid/evaluation").build())
                .execute().use { response ->
                    assertEquals(200, response.code)
                    assertTrue(response.header("Cache-Control").orEmpty().contains("no-store"))
                    val body = response.body!!.string()
                    val snapshot = JsonParser.parseString(body).asJsonObject
                    assertEquals(2, snapshot["schemaVersion"].asInt)
                    assertEquals("WAITING", snapshot["events"].asJsonArray[0].asJsonObject["state"].asString)
                    listOf(vid, "verifyUrl", "nonce", "ticket", "randstr").forEach {
                        assertFalse(body.contains(it), "response must not contain $it")
                    }
                }
        }
    }

    @Test
    fun `Python evaluator consumes the real local Ktor route`() {
        val lab = System.getenv("SOLVER_LAB_PATH")
        assumeTrue(!lab.isNullOrBlank(), "set SOLVER_LAB_PATH to run the Python bridge integration")
        val scratch = Files.createTempDirectory("eink-evaluation-bridge-").toFile()
        try {
            withServer { base, vid ->
                val report = System.getenv("EINK_EVALUATION_REPORT")?.let(::File) ?: File(scratch, "report.json")
                val log = File(scratch, "collector.log")
                val builder = ProcessBuilder(
                    "python3", "-m", "solver_lab.eink", "--base-url", base,
                    "--vid", vid, "--output", report.absolutePath,
                ).directory(File(lab!!)).redirectErrorStream(true).redirectOutput(log)
                builder.environment()["PYTHONPATH"] = lab
                val process = builder.start()
                try {
                    assertTrue(process.waitFor(15, TimeUnit.SECONDS), "Python collector did not finish")
                    assertEquals(0, process.exitValue(), log.readText())
                } finally {
                    if (process.isAlive) process.destroyForcibly()
                }
                val raw = report.readText()
                val parsed = JsonParser.parseString(raw).asJsonObject
                assertEquals("eink_human_verification_evaluation", parsed["kind"].asString)
                assertEquals(1, parsed["summary"].asJsonObject["waiting"].asInt)
                assertEquals(1, parsed["collection"].asJsonObject["successful_reads"].asInt)
                assertFalse(raw.contains(vid))
                assertFalse(raw.contains("verifyUrl"))
            }
        } finally {
            scratch.deleteRecursively()
        }
    }

    private fun withServer(block: (String, String) -> Unit) {
        WeReadEinkHumanVerification.reset()
        val server = embeddedServer(Netty, port = 0, host = "127.0.0.1") {
            install(ContentNegotiation) { gson() }
            routing { weReadEinkVerificationRoutes() }
        }
        server.start(wait = false)
        try {
            val port = runBlocking { server.resolvedConnectors().first().port }
            val base = "http://127.0.0.1:$port"
            val vid = "local-evaluation-account"
            WeReadEinkHumanVerification.createOrReuse(vid, base)
            block(base, vid)
        } finally {
            WeReadEinkHumanVerification.reset()
            server.stop(0, 0)
        }
    }
}
