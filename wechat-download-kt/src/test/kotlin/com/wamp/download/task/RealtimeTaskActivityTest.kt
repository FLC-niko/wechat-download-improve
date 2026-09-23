package com.wamp.download.task

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RealtimeTaskActivityTest {
    @Test
    fun `background work waits until all realtime tasks finish and quiet period elapses`() {
        var now = 1_000L
        val tracker = RealtimeTaskTracker(quietPeriodMs = 120_000L) { now }

        assertFalse(tracker.isBackgroundWorkAllowed())
        now += 120_000L
        assertTrue(tracker.isBackgroundWorkAllowed())

        tracker.taskStarted()
        assertTrue(tracker.hasActiveTasks())
        assertFalse(tracker.isBackgroundWorkAllowed())

        tracker.taskStarted()
        tracker.taskFinished()
        assertTrue(tracker.hasActiveTasks())

        tracker.taskFinished()
        assertFalse(tracker.hasActiveTasks())
        assertFalse(tracker.isBackgroundWorkAllowed())
        now += 120_000L
        assertTrue(tracker.isBackgroundWorkAllowed())
    }
}
