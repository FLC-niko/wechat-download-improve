package com.wamp.download.weread.eink

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.UUID

internal enum class WeReadEinkAutoSolverPhase {
    IDLE, SOLVING, PROBING, RECOVERED, MANUAL_REQUIRED,
}

/** Only non-sensitive, immutable diagnostics are retained. Captcha results never enter this type. */
internal data class WeReadEinkAutoSolverSnapshot(
    val phase: WeReadEinkAutoSolverPhase = WeReadEinkAutoSolverPhase.IDLE,
    val result: WeReadEinkAutoRecoveryCode? = null,
    val startedAt: Long? = null,
    val finishedAt: Long? = null,
    val durationMs: Long = 0,
    val attempts: Int? = null,
    val attemptId: String? = null,
)

internal fun WeReadEinkAutoRecoveryCode.manualReason(): String = when (this) {
    WeReadEinkAutoRecoveryCode.DISABLED -> "自动求解未启用"
    WeReadEinkAutoRecoveryCode.NOT_ALLOWLISTED -> "账号未加入白名单"
    WeReadEinkAutoRecoveryCode.SIDE_CAR_UNAVAILABLE -> "求解器无法连接、超时或服务异常"
    WeReadEinkAutoRecoveryCode.SIDE_CAR_UNAUTHORIZED -> "求解器鉴权失败"
    WeReadEinkAutoRecoveryCode.SOLVE_FAILED -> "自动求解失败"
    WeReadEinkAutoRecoveryCode.INVALID_RESULT -> "验证码求解结果或验证会话无效"
    WeReadEinkAutoRecoveryCode.PROBE_FAILED -> "验证后探测未恢复"
    WeReadEinkAutoRecoveryCode.SESSION_CHANGED -> "账号会话已变化，旧自动求解任务已失效"
    WeReadEinkAutoRecoveryCode.RECOVERED -> "自动验证及恢复探测成功"
}

internal fun WeReadEinkAutoRecoveryAttempt.notificationDiagnostics(): Map<String, Any?> = mapOf(
    "auto_solver_result" to code.name,
    "auto_solver_reason" to code.manualReason(),
)

/** Per account, independent of the reminder clock. A slow solve must not overlap another solve. */
internal class WeReadEinkAutoSolverDiagnostics(
    initial: WeReadEinkAutoSolverSnapshot = WeReadEinkAutoSolverSnapshot(),
    private val clock: () -> Long = System::currentTimeMillis,
    vid: String = "",
    initialEvents: List<WeReadEinkSolverLogEntry> = emptyList(),
) {
    private val log = WeReadEinkAutoSolverLog(vid, initialEvents)
    private val inFlight = AtomicBoolean(false)
    private val state = AtomicReference(initial)
    private var startedNanos: Long = 0

    fun snapshot(): WeReadEinkAutoSolverSnapshot = state.get()

    fun recentEvents(): List<WeReadEinkSolverLogEntry> = log.recent()

    fun record(
        event: WeReadEinkSolverEvent,
        result: WeReadEinkAutoRecoveryCode? = null,
        solve: WeReadEinkCaptchaSolveResult? = null,
        textSent: Boolean? = null,
        qrSent: Boolean? = null,
        enabled: Boolean? = null,
        allowlisted: Boolean? = null,
        secretConfigured: Boolean? = null,
    ) {
        val current = snapshot()
        log.record(WeReadEinkSolverLogEntry(
            timestamp = clock(), event = event, attemptId = current.attemptId,
            result = result, httpStatus = solve?.httpStatus, failure = solve?.failure,
            solverResult = solve?.code,
            durationMs = solve?.durationMs ?: current.durationMs,
            attempts = solve?.attempts ?: current.attempts, textSent = textSent, qrSent = qrSent,
            enabled = enabled, allowlisted = allowlisted, secretConfigured = secretConfigured,
        ))
    }

    fun tryAcquire(): Boolean = inFlight.compareAndSet(false, true)

    fun release() { inFlight.set(false) }

    fun begin() {
        startedNanos = System.nanoTime()
        state.set(WeReadEinkAutoSolverSnapshot(startedAt = clock(), attemptId = UUID.randomUUID().toString()))
    }

    fun transition(phase: WeReadEinkAutoSolverPhase) {
        if (phase == WeReadEinkAutoSolverPhase.SOLVING) {
            startedNanos = System.nanoTime()
            state.updateAndGet { it.copy(phase = phase, startedAt = clock()) }
        } else {
            state.updateAndGet { it.copy(phase = phase) }
        }
    }

    fun finish(attempt: WeReadEinkAutoRecoveryAttempt) {
        state.updateAndGet {
            it.copy(
                phase = if (attempt.code == WeReadEinkAutoRecoveryCode.RECOVERED) {
                    WeReadEinkAutoSolverPhase.RECOVERED
                } else WeReadEinkAutoSolverPhase.MANUAL_REQUIRED,
                result = attempt.code,
                finishedAt = clock(),
                durationMs = if (it.startedAt == null) 0 else {
                    (System.nanoTime() - startedNanos) / 1_000_000
                },
                attempts = attempt.attempts,
            )
        }
    }

    /** Copy diagnostics to a new runtime, never share mutable state with an obsolete session. */
    fun afterReload(): WeReadEinkAutoSolverSnapshot = snapshot().let {
        if (it.phase == WeReadEinkAutoSolverPhase.SOLVING || it.phase == WeReadEinkAutoSolverPhase.PROBING) {
            it.copy(
                phase = WeReadEinkAutoSolverPhase.MANUAL_REQUIRED,
                result = WeReadEinkAutoRecoveryCode.SESSION_CHANGED,
                finishedAt = clock(),
                durationMs = (System.nanoTime() - startedNanos) / 1_000_000,
            )
        } else it
    }

    fun status(enabled: Boolean, allowlisted: Boolean, secretConfigured: Boolean = false): Map<String, Any?> {
        val current = snapshot()
        return mapOf(
            "enabled" to enabled,
            "allowlisted" to allowlisted,
            "eligible" to (enabled && allowlisted),
            "secret_configured" to secretConfigured,
            "diagnostics_version" to 1,
            "attempt_id" to current.attemptId,
            "phase" to current.phase.name,
            "result" to current.result?.name,
            "reason" to current.result?.manualReason(),
            "started_at" to current.startedAt,
            "finished_at" to current.finishedAt,
            "duration_ms" to current.durationMs,
            "attempts" to current.attempts,
            "recent_events" to recentEvents(),
        )
    }
}
