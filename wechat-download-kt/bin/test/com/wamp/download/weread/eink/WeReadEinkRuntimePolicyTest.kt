package com.wamp.download.weread.eink

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WeReadEinkRuntimePolicyTest {
    @Test
    fun `人工验证状态阻止任务继续请求 E-Ink`() {
        assertTrue(WeReadEinkRuntime.taskSyncSuppressed("HUMAN_VERIFY_REQUIRED"))
        assertTrue(WeReadEinkRuntime.taskSyncSuppressed("REAUTH_REQUIRED"))
        assertFalse(WeReadEinkRuntime.taskSyncSuppressed("HEALTHY"))
        assertFalse(WeReadEinkRuntime.taskSyncSuppressed("UNKNOWN"))
    }

    @Test
    fun `人工验证探测在长退避到期前不再联网`() {
        assertTrue(
            WeReadEinkRuntime.healthProbeSuppressed(
                state = "HUMAN_VERIFY_REQUIRED",
                lastProbeAt = 1_000L,
                now = 1_500L,
                humanVerifyProbeIntervalMs = 1_000L,
            ),
        )
        assertFalse(
            WeReadEinkRuntime.healthProbeSuppressed(
                state = "HUMAN_VERIFY_REQUIRED",
                lastProbeAt = 1_000L,
                now = 2_000L,
                humanVerifyProbeIntervalMs = 1_000L,
            ),
        )
        assertFalse(
            WeReadEinkRuntime.healthProbeSuppressed(
                state = "HEALTHY",
                lastProbeAt = 1_000L,
                now = 1_500L,
                humanVerifyProbeIntervalMs = 1_000L,
            ),
        )
    }
}
