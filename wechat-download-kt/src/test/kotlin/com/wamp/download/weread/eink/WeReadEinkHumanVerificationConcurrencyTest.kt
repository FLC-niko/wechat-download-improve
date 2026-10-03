package com.wamp.download.weread.eink

import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WeReadEinkHumanVerificationConcurrencyTest {
    @Test
    fun `queued account cannot overwrite active probe credentials`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        lateinit var coordinator: WeReadEinkHumanVerificationCoordinator
        coordinator = WeReadEinkHumanVerificationCoordinator(
            nonceGenerator = { "local-nonce" },
            probe = { vid ->
                if (vid == "a") {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                }
                coordinator.requestHeaders()["wr_ticket"] == "mock-ticket-$vid"
            },
        )
        coordinator.createOrReuse("a", "http://localhost")
        coordinator.createOrReuse("b", "http://localhost")
        val pool = Executors.newFixedThreadPool(2)
        try {
            val first = pool.submit<WeReadEinkVerificationResult> {
                coordinator.complete("a", "local-nonce", "mock-ticket-a", "mock-randstr-a")
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val second = pool.submit<WeReadEinkVerificationResult> {
                coordinator.complete("b", "local-nonce", "mock-ticket-b", "mock-randstr-b")
            }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (coordinator.status("b", "http://localhost").state != WeReadEinkVerificationState.VERIFYING &&
                System.nanoTime() < deadline) Thread.sleep(5)
            assertEquals(WeReadEinkVerificationState.VERIFYING, coordinator.status("b", "http://localhost").state)
            assertEquals("mock-ticket-a", coordinator.requestHeaders()["wr_ticket"])
            release.countDown()
            assertTrue(first.get(5, TimeUnit.SECONDS).ok)
            assertTrue(second.get(5, TimeUnit.SECONDS).ok)
            assertTrue(coordinator.requestHeaders().isEmpty())
        } finally {
            release.countDown()
            pool.shutdownNow()
            pool.awaitTermination(5, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `another account expiry cannot clear active probe credentials`() {
        val now = AtomicLong(1000)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        lateinit var coordinator: WeReadEinkHumanVerificationCoordinator
        coordinator = WeReadEinkHumanVerificationCoordinator(
            ttlMs = 100,
            clock = now::get,
            nonceGenerator = { "local-nonce" },
            probe = {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                coordinator.requestHeaders()["wr_ticket"] == "mock-ticket-a"
            },
        )
        coordinator.createOrReuse("a", "http://localhost")
        coordinator.createOrReuse("b", "http://localhost")
        val pool = Executors.newSingleThreadExecutor()
        try {
            val result = pool.submit<WeReadEinkVerificationResult> {
                coordinator.complete("a", "local-nonce", "mock-ticket-a", "mock-randstr-a")
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            now.set(1200)
            assertEquals(WeReadEinkVerificationState.EXPIRED, coordinator.evaluation("b").events.last().state)
            assertEquals("mock-ticket-a", coordinator.requestHeaders()["wr_ticket"])
            release.countDown()
            assertTrue(result.get(5, TimeUnit.SECONDS).ok)
        } finally {
            release.countDown()
            pool.shutdownNow()
            pool.awaitTermination(5, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `simultaneous duplicate completion probes once`() {
        val count = AtomicInteger()
        val coordinator = WeReadEinkHumanVerificationCoordinator(
            nonceGenerator = { "local-nonce" }, probe = { count.incrementAndGet(); true },
        )
        coordinator.createOrReuse("a", "http://localhost")
        val pool = Executors.newFixedThreadPool(16)
        val barrier = CyclicBarrier(16)
        try {
            val calls = (1..16).map {
                pool.submit<WeReadEinkVerificationResult> {
                    barrier.await(5, TimeUnit.SECONDS)
                    coordinator.complete("a", "local-nonce", "mock-ticket", "mock-randstr")
                }
            }.map { it.get(10, TimeUnit.SECONDS) }
            assertEquals(1, calls.count { it.ok })
            assertEquals(15, calls.count { it.code == "ALREADY_USED" })
            assertEquals(1, count.get())
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `800 controlled completions retain isolation and bounded history`() {
        val nonce = AtomicInteger()
        val mismatches = AtomicInteger()
        val active = AtomicInteger()
        val concurrentProbes = AtomicInteger()
        lateinit var coordinator: WeReadEinkHumanVerificationCoordinator
        coordinator = WeReadEinkHumanVerificationCoordinator(
            nonceGenerator = { "local-${nonce.incrementAndGet()}" },
            probe = { vid ->
                if (active.incrementAndGet() > 1) concurrentProbes.incrementAndGet()
                try {
                    Thread.yield()
                    val headers = coordinator.requestHeaders()
                    if (headers["wr_ticket"] != "mock-ticket-$vid" || headers["wr_randstr"] != "mock-randstr-$vid") {
                        mismatches.incrementAndGet()
                    }
                    vid.toInt() % 4 != 0
                } finally {
                    active.decrementAndGet()
                }
            },
        )
        val pool = Executors.newFixedThreadPool(16)
        try {
            val runs = (0 until 16).map { account ->
                pool.submit {
                    val vid = account.toString()
                    repeat(50) {
                        val link = coordinator.createOrReuse(vid, "http://localhost").verifyUrl!!
                        val result = coordinator.complete(vid, link.substringAfterLast('/'), "mock-ticket-$vid", "mock-randstr-$vid")
                        assertEquals(account % 4 != 0, result.ok)
                    }
                }
            }
            runs.forEach { it.get(20, TimeUnit.SECONDS) }
            assertEquals(0, mismatches.get())
            assertEquals(0, concurrentProbes.get())
            assertTrue(coordinator.requestHeaders().isEmpty())
            repeat(16) {
                val history = coordinator.evaluation(it.toString())
                assertEquals(20, history.events.size)
                assertEquals(150L, history.lastSequence)
                assertEquals(130L, history.droppedEventCount)
            }
        } finally {
            pool.shutdownNow()
        }
    }
}
