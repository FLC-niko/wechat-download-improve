package com.wamp.download.weread

import kotlin.test.Test
import kotlin.test.assertTrue

class WeReadWebRecoveryPolicyTest {
    @Test
    fun `expired account is recoverable without obsolete refresh counter`() {
        assertTrue(
            WeReadWebAuth.reloginEligible(
                status = AccountStatus.EXPIRED,
                refreshFailCount = 0,
                threshold = 3,
                force = false,
            ),
        )
    }

    @Test
    fun `manual request can force qr recovery independently of account status`() {
        assertTrue(
            WeReadWebAuth.reloginEligible(
                status = AccountStatus.ACTIVE,
                refreshFailCount = 0,
                threshold = 3,
                force = true,
            ),
        )
    }

    @Test
    fun `automated recovery suppresses the duplicate expired text message`() {
        assertTrue(CredentialHealthChecker.shouldSuppressExpiredText(WeReadWebAuth.ReloginRequestResult.STARTED))
        assertTrue(CredentialHealthChecker.shouldSuppressExpiredText(WeReadWebAuth.ReloginRequestResult.ALREADY_RUNNING))
        assertTrue(CredentialHealthChecker.shouldSuppressExpiredText(WeReadWebAuth.ReloginRequestResult.BACKOFF))
        assertTrue(!CredentialHealthChecker.shouldSuppressExpiredText(WeReadWebAuth.ReloginRequestResult.AUTO_QR_DISABLED))
        assertTrue(!CredentialHealthChecker.shouldSuppressExpiredText(WeReadWebAuth.ReloginRequestResult.RECOVERY_UNAVAILABLE))
    }

    @Test
    fun `health notification only claims orchestration when recovery started`() {
        assertTrue(
            CredentialHealthChecker.recoveryMessage(WeReadWebAuth.ReloginRequestResult.STARTED)
                .contains("已启动"),
        )
        assertTrue(
            CredentialHealthChecker.recoveryMessage(WeReadWebAuth.ReloginRequestResult.AUTO_QR_DISABLED)
                .contains("已关闭"),
        )
    }
}
