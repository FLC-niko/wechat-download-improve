package com.wamp.download.weread.eink

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WeReadEinkRequestGateTest {

    @Test
    fun `verification probe takes the gate before yielding background requests`() {
        val vid = "gate-test-priority-${System.nanoTime()}"
        val order = Collections.synchronizedList(mutableListOf<String>())
        val backgroundStarted = CountDownLatch(1)
        val backgroundDone = CountDownLatch(1)

        WeReadEinkRequestGate.beginVerification(vid)
        try {
            val background = Thread {
                backgroundStarted.countDown()
                WeReadEinkRequestGate.execute(vid) { order.add("background") }
                backgroundDone.countDown()
            }
            background.start()
            assertTrue(backgroundStarted.await(2, TimeUnit.SECONDS))
            Thread.sleep(200L) // let the background request park in the verification wait

            WeReadEinkRequestGate.asVerificationProbe {
                WeReadEinkRequestGate.execute(vid) { order.add("probe") }
            }
            assertEquals(listOf("probe"), order.toList())
        } finally {
            WeReadEinkRequestGate.endVerification(vid)
        }

        assertTrue(backgroundDone.await(10, TimeUnit.SECONDS))
        assertEquals(listOf("probe", "background"), order.toList())
    }

    @Test
    fun `verification probe skips the minimum request interval`() {
        val vid = "gate-test-skip-${System.nanoTime()}"
        WeReadEinkRequestGate.execute(vid) { /* consumes the first request slot */ }

        val startedAt = System.currentTimeMillis()
        WeReadEinkRequestGate.asVerificationProbe {
            WeReadEinkRequestGate.execute(vid) { /* probe request */ }
        }
        val elapsed = System.currentTimeMillis() - startedAt
        assertTrue(elapsed < 1_000L, "probe should skip the 2s throttle, elapsed=${elapsed}ms")
    }

    @Test
    fun `captcha headers are only readable from the verification probe thread`() {
        assertTrue(WeReadEinkHumanVerification.requestHeaders().isEmpty())
        WeReadEinkRequestGate.asVerificationProbe {
            // The probe thread is the only one allowed to observe captcha headers once installed.
            assertTrue(WeReadEinkHumanVerification.requestHeaders().isEmpty())
        }
    }
}
