package com.wamp.download.weread.eink

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WeReadEinkRuntimePolicyTest {
    @Test
    fun `人工验证状态阻止任务继续请求 E-Ink`() {
        assertTrue(WeReadEinkRuntime.taskSyncSuppressed("HUMAN_VERIFY_REQUIRED"))
        assertTrue(WeReadEinkRuntime.taskSyncSuppressed("REAUTH_REQUIRED"))
        assertTrue(WeReadEinkRuntime.taskSyncSuppressed("REAUTH_IN_PROGRESS"))
        assertFalse(WeReadEinkRuntime.taskSyncSuppressed("HEALTHY"))
        assertFalse(WeReadEinkRuntime.taskSyncSuppressed("UNKNOWN"))
    }

    @Test
    fun `后台同步只熔断人工验证与重登进行中的账号`() {
        // REAUTH_REQUIRED 的账号仍允许后台同步选中后走自动恢复/续签路径
        assertTrue(WeReadEinkRuntime.scheduledSyncSuppressed("HUMAN_VERIFY_REQUIRED"))
        assertTrue(WeReadEinkRuntime.scheduledSyncSuppressed("REAUTH_IN_PROGRESS"))
        assertFalse(WeReadEinkRuntime.scheduledSyncSuppressed("REAUTH_REQUIRED"))
        assertFalse(WeReadEinkRuntime.scheduledSyncSuppressed("HEALTHY"))
        assertFalse(WeReadEinkRuntime.scheduledSyncSuppressed("UNKNOWN"))
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

    @Test
    fun `least_used 选号过滤熔断账号并取最久未用`() {
        val candidates = listOf(
            WeReadEinkAccountCandidate("vid-a", lastUsedAt = 300L, authState = "HEALTHY"),
            WeReadEinkAccountCandidate("vid-b", lastUsedAt = 100L, authState = "HUMAN_VERIFY_REQUIRED"),
            WeReadEinkAccountCandidate("vid-c", lastUsedAt = 200L, authState = "HEALTHY"),
        )

        val picked = WeReadEinkRuntime.leastUsedEinkAccount(candidates, WeReadEinkRuntime::taskSyncSuppressed)

        assertEquals("vid-c", picked?.vid, "必须跳过熔断账号并选中最久未用的 vid-c")
    }

    @Test
    fun `lastUsedAt 相同时按 vid 升序保证确定性`() {
        val candidates = listOf(
            WeReadEinkAccountCandidate("vid-c", lastUsedAt = 100L, authState = "HEALTHY"),
            WeReadEinkAccountCandidate("vid-b", lastUsedAt = 100L, authState = "HEALTHY"),
            WeReadEinkAccountCandidate("vid-a", lastUsedAt = 100L, authState = "HEALTHY"),
        )

        val picked = WeReadEinkRuntime.leastUsedEinkAccount(candidates, WeReadEinkRuntime::taskSyncSuppressed)

        assertEquals("vid-a", picked?.vid)
    }

    @Test
    fun `全部账号熔断时选号返回 null`() {
        val taskCandidates = listOf(
            WeReadEinkAccountCandidate("vid-a", lastUsedAt = 100L, authState = "HUMAN_VERIFY_REQUIRED"),
            WeReadEinkAccountCandidate("vid-b", lastUsedAt = 50L, authState = "REAUTH_REQUIRED"),
        )
        assertNull(
            WeReadEinkRuntime.leastUsedEinkAccount(taskCandidates, WeReadEinkRuntime::taskSyncSuppressed),
            "任务路径全部熔断时必须返回 null",
        )

        val scheduledCandidates = listOf(
            WeReadEinkAccountCandidate("vid-a", lastUsedAt = 100L, authState = "HUMAN_VERIFY_REQUIRED"),
            WeReadEinkAccountCandidate("vid-b", lastUsedAt = 50L, authState = "REAUTH_IN_PROGRESS"),
        )
        assertNull(
            WeReadEinkRuntime.leastUsedEinkAccount(scheduledCandidates, WeReadEinkRuntime::scheduledSyncSuppressed),
            "后台路径全部熔断时必须返回 null",
        )
    }

    @Test
    fun `后台同步允许 REAUTH_REQUIRED 账号参与选号`() {
        val candidates = listOf(
            WeReadEinkAccountCandidate("vid-a", lastUsedAt = 10L, authState = "REAUTH_REQUIRED"),
        )

        val picked = WeReadEinkRuntime.leastUsedEinkAccount(candidates, WeReadEinkRuntime::scheduledSyncSuppressed)

        assertEquals("vid-a", picked?.vid)
    }

    @Test
    fun `失败后的鉴权状态按错误种类归位`() {
        val humanVerify = WeReadEinkApiException(
            kind = WeReadEinkResponseKind.HUMAN_VERIFY,
            statusCode = 499,
            errCode = -2041,
            errMsg = "-2041",
        )
        assertEquals("HUMAN_VERIFY_REQUIRED", WeReadEinkRuntime.authStateAfterFailure(false, humanVerify))
        assertEquals("REAUTH_REQUIRED", WeReadEinkRuntime.authStateAfterFailure(true, RuntimeException("boom")))
        assertEquals(
            "REFRESH_BACKOFF",
            WeReadEinkRuntime.authStateAfterFailure(false, WeReadEinkRefreshBackoffException(null, null)),
        )
        assertEquals("DEGRADED", WeReadEinkRuntime.authStateAfterFailure(false, RuntimeException("boom")))
    }
}
