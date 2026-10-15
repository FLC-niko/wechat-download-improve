package com.wamp.download.weread

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LoginListenerOrderTest {
    @Test
    fun `blocking HTTP listener does not prevent scan when caller uses IO`() = runBlocking {
        val authorized = java.util.concurrent.CountDownLatch(1)
        val result = withContext(Dispatchers.IO) {
            scanWhileListening(
                listen = { runInterruptible(Dispatchers.IO) {
                    authorized.await(2, java.util.concurrent.TimeUnit.SECONDS)
                } },
                scan = { authorized.countDown() },
            )
        }
        assertTrue(result, "HTTP listener blocked the scan until its timeout")
    }

    @Test
    fun `listener is active before phone authorization and returns its credentials`() = runBlocking {
        val listening = CompletableDeferred<Unit>()
        val confirmed = CompletableDeferred<Unit>()
        val result = scanWhileListening(
            listen = { listening.complete(Unit); confirmed.await(); "credentials" },
            scan = { assertTrue(listening.isCompleted); confirmed.complete(Unit) },
        )
        assertEquals("credentials", result)
    }

    @Test
    fun `failed scan cancels its listener`() = runBlocking {
        var cancelled = false
        assertFailsWith<IllegalStateException> {
            scanWhileListening(
                listen = { try { awaitCancellation() } finally { cancelled = true } },
                scan = { throw IllegalStateException("scan failed") },
            )
        }
        assertTrue(cancelled)
    }

    @Test
    fun `human verification keeps credentials but prevents scheduling`() {
        val credentials = WeReadWebCredentials(wrVid = "test", wrSkey = "valid")
        val account = WeReadAccount("test", credentials, status = AccountStatus.HUMAN_VERIFY_REQUIRED)
        assertTrue(account.hasWebCredentials())
        assertFalse(account.isAvailable())
        assertEquals(credentials, account.credentials)
    }
}
