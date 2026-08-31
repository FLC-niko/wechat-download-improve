package com.wamp.download.auth

import com.wamp.download.wx.WxApiClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LoginCheckerRecoveryTest {
    @Test
    fun `concurrent callers share one recovery and receive verified success`() = runBlocking {
        var attempts = 0
        val loginStarted = CompletableDeferred<Unit>()
        val releaseLogin = CompletableDeferred<Unit>()
        val checker = LoginChecker(
            loginRetryDelayMs = 0L,
            recoveryBackoffInitialMs = 1_000L,
            recoveryBackoffMaxMs = 1_000L,
            tokenStatusProvider = { WxApiClient.TokenStatus.VALID },
            loginAttempt = {
                attempts++
                loginStarted.complete(Unit)
                releaseLogin.await()
                true
            },
            mainEnabledProvider = { true },
            autoRecoveryEnabledProvider = { true },
        )

        val first = async { checker.startRecoveryAndWait() }
        loginStarted.await()
        val second = async { checker.startRecoveryAndWait() }
        releaseLogin.complete(Unit)

        assertTrue(first.await())
        assertTrue(second.await())
        assertEquals(1, attempts)
        assertFalse(checker.isRecovering)
    }

    @Test
    fun `failed recovery is propagated and immediate retry is suppressed`() = runBlocking {
        var attempts = 0
        val checker = LoginChecker(
            loginRetryDelayMs = 0L,
            recoveryBackoffInitialMs = 60_000L,
            recoveryBackoffMaxMs = 60_000L,
            tokenStatusProvider = { WxApiClient.TokenStatus.EXPIRED },
            loginAttempt = {
                attempts++
                false
            },
            mainEnabledProvider = { true },
            autoRecoveryEnabledProvider = { true },
        )

        assertFalse(checker.startRecoveryAndWait())
        assertEquals(3, attempts)
        assertFalse(checker.startRecoveryAndWait())
        assertEquals(3, attempts)
        assertEquals(1, checker.recoveryStatus()["failure_count"])
    }

    @Test
    fun `disabled automatic recovery never starts a login attempt`() = runBlocking {
        var attempts = 0
        val checker = LoginChecker(
            tokenStatusProvider = { WxApiClient.TokenStatus.EXPIRED },
            loginAttempt = {
                attempts++
                true
            },
            mainEnabledProvider = { true },
            autoRecoveryEnabledProvider = { false },
        )

        assertFalse(checker.startRecoveryAndWait())
        assertEquals(0, attempts)
        assertFalse(checker.isRecovering)
    }

    @Test
    fun `disabled main channel is not probed or auto recovered`() = runBlocking {
        var probes = 0
        var attempts = 0
        val checker = LoginChecker(
            tokenStatusProvider = {
                probes++
                WxApiClient.TokenStatus.EXPIRED
            },
            loginAttempt = {
                attempts++
                true
            },
            mainEnabledProvider = { false },
            autoRecoveryEnabledProvider = { true },
        )

        checker.checkNow()

        assertEquals(0, probes)
        assertEquals(0, attempts)
        assertEquals(WxApiClient.TokenStatus.UNAVAILABLE, checker.checkTokenStatus(forceRefresh = true))
    }

    @Test
    fun `post login verification outage does not repeat qr login or throw`() = runBlocking {
        var attempts = 0
        val checker = LoginChecker(
            loginRetryDelayMs = 0L,
            tokenStatusProvider = { error("temporary network outage") },
            loginAttempt = {
                attempts++
                true
            },
            mainEnabledProvider = { true },
            autoRecoveryEnabledProvider = { true },
        )

        assertFalse(checker.startRecoveryAndWait())
        assertEquals(1, attempts)
        assertFalse(checker.isRecovering)
    }
}
