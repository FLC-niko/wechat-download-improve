package com.wamp.download.weread

import com.google.gson.Gson
import com.google.gson.JsonParser
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WeReadAccountRecoveryCooldownTest {
    @Test
    fun `verified human status recovers immediately and legacy fields do not reapply delay`() {
        withTemporaryAccountDirectory { directory ->
            val account = account("verified-test", AccountStatus.HUMAN_VERIFY_REQUIRED).apply {
                cooldownUntil = System.currentTimeMillis() + 60_000L
            }
            WeReadAccountPool.setForTest(account)

            assertTrue(WeReadAccountPool.clearHumanVerificationRequired(account.vid))
            val recovered = checkNotNull(WeReadAccountPool.get(account.vid))
            assertEquals(AccountStatus.ACTIVE, recovered.status)
            assertEquals(0L, recovered.cooldownUntil)
            assertEquals(account.vid, WeReadAccountPool.acquire()?.vid)
            WeReadAccountPool.release(account.vid)
            WeReadAccountPool.removeForTest(account.vid)

            val manuallyEnabled = account("manual-recovery-test", AccountStatus.HUMAN_VERIFY_REQUIRED).apply {
                cooldownUntil = System.currentTimeMillis() + 60_000L
            }
            WeReadAccountPool.setForTest(manuallyEnabled)
            assertTrue(WeReadAccountPool.setEnabled(manuallyEnabled.vid, enabled = true))
            assertEquals(manuallyEnabled.vid, WeReadAccountPool.acquire()?.vid)
            WeReadAccountPool.release(manuallyEnabled.vid)
            WeReadAccountPool.removeForTest(manuallyEnabled.vid)
            File(directory, "${manuallyEnabled.vid}.json").delete()

            // Simulate an older persisted account record. Gson ignores these removed fields.
            val file = File(directory, "${account.vid}.json")
            val legacyJson = JsonParser.parseString(file.readText()).asJsonObject.apply {
                addProperty("recoveryUntil", System.currentTimeMillis() + 3_600_000L)
                addProperty("recoveryProbeRequired", true)
            }
            file.writeText(Gson().toJson(legacyJson))
            WeReadAccountPool.loadDirectoryForTest(directory.absolutePath)

            val reloaded = checkNotNull(WeReadAccountPool.get(account.vid))
            assertEquals(AccountStatus.ACTIVE, reloaded.status)
            assertEquals(account.vid, WeReadAccountPool.acquire()?.vid)
            WeReadAccountPool.release(account.vid)

            // A new -2014 after recovery still creates the ordinary account cooldown.
            WeReadAccountPool.markFreqControl(account.vid, cooldownSec = 1200L)
            assertEquals(AccountStatus.COOLDOWN, WeReadAccountPool.get(account.vid)?.status)
            assertNull(WeReadAccountPool.acquire())
        }
    }

    @Test
    fun `legacy human verification JSON remains blocked after reload`() {
        withTemporaryAccountDirectory { directory ->
            val legacy = account("legacy-human-test", AccountStatus.HUMAN_VERIFY_REQUIRED).apply {
                cooldownUntil = System.currentTimeMillis() + 60_000L
            }
            val legacyJson = JsonParser.parseString(Gson().toJson(legacy)).asJsonObject.apply {
                addProperty("recoveryUntil", 9_999_999_999_999L)
                addProperty("recoveryProbeRequired", true)
            }
            File(directory, "${legacy.vid}.json").writeText(Gson().toJson(legacyJson))
            WeReadAccountPool.loadDirectoryForTest(directory.absolutePath)

            assertEquals(AccountStatus.HUMAN_VERIFY_REQUIRED, WeReadAccountPool.get(legacy.vid)?.status)
            assertNull(WeReadAccountPool.acquire())
        }
    }

    @Test
    fun `human verification expired and disabled accounts remain unavailable`() {
        withTemporaryAccountDirectory { directory ->
            for (status in listOf(
                AccountStatus.HUMAN_VERIFY_REQUIRED,
                AccountStatus.EXPIRED,
                AccountStatus.DISABLED,
            )) {
                WeReadAccountPool.loadDirectoryForTest(directory.absolutePath)
                val account = account("blocked-${status.name.lowercase()}", status)
                WeReadAccountPool.setForTest(account)
                assertFalse(account.isAvailable())
                assertNull(WeReadAccountPool.acquire())
            }
        }
    }

    @Test
    fun `ordinary newly logged in account is immediately available`() {
        val account = account("new-login-test", AccountStatus.ACTIVE)
        assertTrue(account.isAvailable())
        assertEquals(0L, account.cooldownUntil)
    }

    private fun account(vid: String, status: AccountStatus) = WeReadAccount(
        vid = vid,
        credentials = WeReadWebCredentials(wrVid = vid, wrSkey = "synthetic-offline-fixture"),
        status = status,
    )

    private fun withTemporaryAccountDirectory(block: (File) -> Unit) {
        val directory = Files.createTempDirectory("weread-recovery-account-test-").toFile()
        WeReadAccountPool.setAccountDirForTest(directory.absolutePath)
        try {
            WeReadAccountPool.loadDirectoryForTest(directory.absolutePath)
            block(directory)
        } finally {
            WeReadAccountPool.list().forEach { WeReadAccountPool.removeForTest(it.vid) }
            WeReadAccountPool.setAccountDirForTest(null)
            directory.deleteRecursively()
        }
    }
}
