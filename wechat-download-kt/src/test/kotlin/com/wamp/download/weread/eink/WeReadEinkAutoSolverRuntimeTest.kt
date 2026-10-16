package com.wamp.download.weread.eink

import com.wamp.download.config.AppConfig
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.*

/** Exercise real runtime reload/status wiring without starting background tasks or using Tencent. */
class WeReadEinkAutoSolverRuntimeTest {
    @TempDir
    lateinit var temp: Path

    @Test
    fun `runtime status preserves diagnostics on reload and removed account cannot overwrite replacement`() {
        val configField = AppConfig::class.java.getDeclaredField("yamlConfig").apply { isAccessible = true }
        val accountsField = WeReadEinkRuntime::class.java.getDeclaredField("accounts").apply { isAccessible = true }
        val primaryField = WeReadEinkRuntime::class.java.getDeclaredField("primaryVid").apply { isAccessible = true }
        val savedConfig = configField.get(AppConfig)
        @Suppress("UNCHECKED_CAST")
        val accounts = accountsField.get(WeReadEinkRuntime) as MutableMap<String, Any>
        val savedAccounts = accounts.toMap()
        val savedPrimary = primaryField.get(WeReadEinkRuntime)
        val dir = temp.resolve("accounts").toFile()
        val store = WeReadEinkAccountStore(dir)
        val vid = "runtime-diagnostic-vid"
        val other = "runtime-other-vid"
        try {
            configField.set(AppConfig, mapOf(
                "weread" to mapOf("eink" to mapOf(
                    "enabled" to true,
                    "accounts_dir" to dir.absolutePath,
                    "credentials_file" to temp.resolve("no-legacy.json").toString(),
                    "base_url" to "http://127.0.0.1:1",
                    "human_verify" to mapOf("auto_solver" to mapOf(
                        "enabled" to true,
                        "secret" to "private-runtime-secret",
                        "vid_allowlist" to listOf(vid),
                    )),
                )),
            ))
            accounts.clear()
            for (id in listOf(vid, other)) {
                store.save(WeReadEinkAccountRecord.migrated(
                    WeReadEinkCredentials(id, "private-access", "private-refresh", "device-$id"),
                ))
            }
            assertNotNull(WeReadEinkRuntime.reload())
            fun tracker(id: String): WeReadEinkAutoSolverDiagnostics {
                val account = accounts.getValue(id)
                return account.javaClass.getDeclaredField("autoSolver").apply { isAccessible = true }
                    .get(account) as WeReadEinkAutoSolverDiagnostics
            }
            fun view(id: String): Map<*, *> {
                val rows = WeReadEinkRuntime.status()["accounts"] as List<*>
                val row = rows.map { it as Map<*, *> }.first { it["vid"] == id }
                return row["auto_solver"] as Map<*, *>
            }

            val old = tracker(vid)
            old.begin()
            old.transition(WeReadEinkAutoSolverPhase.SOLVING)
            assertEquals("SOLVING", view(vid)["phase"])
            assertEquals(true, view(vid)["eligible"])
            assertEquals(false, view(other)["allowlisted"])
            assertEquals("IDLE", view(other)["phase"])
            assertEquals(1, WeReadEinkRuntime.status()["auto_solver_diagnostics_version"])
            assertEquals(true, view(vid)["secret_configured"])
            assertTrue((view(vid)["recent_events"] as List<*>).isNotEmpty())

            WeReadEinkRuntime.reload()
            assertEquals("SESSION_CHANGED", view(vid)["result"])
            assertEquals("MANUAL_REQUIRED", view(vid)["phase"])
            old.finish(WeReadEinkAutoRecoveryAttempt(WeReadEinkAutoRecoveryCode.RECOVERED, 1))
            assertEquals("SESSION_CHANGED", view(vid)["result"])

            val current = tracker(vid)
            current.begin()
            current.finish(WeReadEinkAutoRecoveryAttempt(WeReadEinkAutoRecoveryCode.SOLVE_FAILED, 1))
            val finished = view(vid).filterKeys { it != "recent_events" }
            WeReadEinkRuntime.reload()
            assertEquals(finished, view(vid).filterKeys { it != "recent_events" },
                "completed diagnostics must survive runtime reload")

            assertTrue(dir.resolve("$vid.json").delete())
            WeReadEinkRuntime.reload()
            assertFalse(WeReadEinkRuntime.hasAccount(vid))
            store.save(WeReadEinkAccountRecord.migrated(
                WeReadEinkCredentials(vid, "new-access", "new-refresh", "new-device"),
            ))
            WeReadEinkRuntime.reload()
            current.finish(WeReadEinkAutoRecoveryAttempt(WeReadEinkAutoRecoveryCode.RECOVERED, 1))
            assertEquals("IDLE", view(vid)["phase"], "removed account cannot mutate a re-added vid")

            val exposed = view(vid).toString() + view(other)
            for (secret in listOf("private-runtime-secret", "private-access", "private-refresh")) {
                assertFalse(exposed.contains(secret))
            }
            assertFalse(dir.listFiles().orEmpty().any { it.readText().contains("auto_solver") },
                "diagnostics must not be persisted to credential records")
        } finally {
            configField.set(AppConfig, savedConfig)
            accounts.clear()
            accounts.putAll(savedAccounts)
            primaryField.set(WeReadEinkRuntime, savedPrimary)
            WeReadEinkHumanVerification.reset()
        }
    }
}
