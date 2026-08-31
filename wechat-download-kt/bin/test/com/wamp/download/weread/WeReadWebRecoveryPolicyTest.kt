package com.wamp.download.weread

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WeReadWebRecoveryPolicyTest {
    @Test
    fun `expired account is recoverable without obsolete refresh counter`() {
        assertTrue(
            WeReadWebAuth.reloginEligible(
                status = AccountStatus.EXPIRED,
                refreshFailCount = 0,
                threshold = 3,
                enabled = true,
                force = false,
            ),
        )
    }

    @Test
    fun `automatic recovery switch is authoritative`() {
        assertFalse(
            WeReadWebAuth.reloginEligible(
                status = AccountStatus.EXPIRED,
                refreshFailCount = 10,
                threshold = 3,
                enabled = false,
                force = true,
            ),
        )
    }
}
