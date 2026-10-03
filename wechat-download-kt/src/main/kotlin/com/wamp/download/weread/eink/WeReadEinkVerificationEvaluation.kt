package com.wamp.download.weread.eink

import java.util.ArrayDeque
import java.util.UUID

/** Evaluation data contains no account ID, challenge URL, nonce, or CAPTCHA credentials. */
data class WeReadEinkVerificationEvaluationEvent(
    val sequence: Long,
    val sessionId: String,
    val state: WeReadEinkVerificationState,
    val at: Long,
    val elapsedMs: Long,
    val verificationMs: Long? = null,
)

data class WeReadEinkVerificationEvaluationSnapshot(
    val generatedAt: Long,
    val generationId: String,
    val schemaVersion: Int = 2,
    val lastSequence: Long = 0,
    val droppedEventCount: Long = 0,
    val events: List<WeReadEinkVerificationEvaluationEvent> = emptyList(),
)

/** Called under the coordinator lock; bounded, process-local history per existing account. */
internal class WeReadEinkVerificationEvaluationRecorder(private val historyLimit: Int = 20) {
    private class History {
        var lastSequence = 0L
        val events = ArrayDeque<WeReadEinkVerificationEvaluationEvent>()
    }

    private val histories = mutableMapOf<String, History>()
    private var generationId = UUID.randomUUID().toString()

    init {
        require(historyLimit > 0)
    }

    fun record(
        vid: String,
        sessionId: String,
        state: WeReadEinkVerificationState,
        createdAt: Long,
        verificationStartedAt: Long?,
        at: Long,
    ) {
        val history = histories.getOrPut(vid) { History() }
        history.lastSequence++
        val terminal = state == WeReadEinkVerificationState.SUCCEEDED ||
            state == WeReadEinkVerificationState.FAILED
        history.events.addLast(
            WeReadEinkVerificationEvaluationEvent(
                sequence = history.lastSequence,
                sessionId = sessionId,
                state = state,
                at = at,
                elapsedMs = (at - createdAt).coerceAtLeast(0),
                verificationMs = if (terminal && verificationStartedAt != null) {
                    (at - verificationStartedAt).coerceAtLeast(0)
                } else null,
            ),
        )
        while (history.events.size > historyLimit) history.events.removeFirst()
    }

    fun snapshot(vid: String, now: Long): WeReadEinkVerificationEvaluationSnapshot {
        val history = histories[vid] ?: return WeReadEinkVerificationEvaluationSnapshot(generatedAt = now, generationId = generationId)
        return WeReadEinkVerificationEvaluationSnapshot(
            generatedAt = now,
            generationId = generationId,
            lastSequence = history.lastSequence,
            droppedEventCount = history.lastSequence - history.events.size,
            events = history.events.toList(),
        )
    }

    fun reset() {
        histories.clear()
        generationId = UUID.randomUUID().toString()
    }
}
