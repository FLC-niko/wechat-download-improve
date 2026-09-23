package com.wamp.download.task

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

internal class RealtimeTaskTracker(
    private val quietPeriodMs: Long = 120_000L,
    private val nowProvider: () -> Long = System::currentTimeMillis,
) {
    private val activeTasks = AtomicInteger(0)
    private val lastActivityAt = AtomicLong(nowProvider())

    fun taskStarted() {
        activeTasks.incrementAndGet()
        lastActivityAt.set(nowProvider())
    }

    fun taskFinished() {
        activeTasks.updateAndGet { current -> (current - 1).coerceAtLeast(0) }
        lastActivityAt.set(nowProvider())
    }

    fun hasActiveTasks(): Boolean = activeTasks.get() > 0

    fun isBackgroundWorkAllowed(): Boolean =
        !hasActiveTasks() && nowProvider() - lastActivityAt.get() >= quietPeriodMs
}

/** Coordinates real-time RabbitMQ work with low-priority cache warm-up jobs. */
object RealtimeTaskActivity {
    private val tracker = RealtimeTaskTracker()

    fun taskStarted() = tracker.taskStarted()

    fun taskFinished() = tracker.taskFinished()

    fun hasActiveTasks(): Boolean = tracker.hasActiveTasks()

    fun isBackgroundWorkAllowed(): Boolean = tracker.isBackgroundWorkAllowed()
}
