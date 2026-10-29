package com.wamp.download.adb

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AdbDeviceSupervisorTest {
    @Test
    fun `refresh marks discovery complete even when no device is online`() {
        val supervisor = AdbDeviceSupervisor(
            commandRunner = AdbCommandRunner { AdbCommandResult(0, "List of devices attached\n", "") },
        )

        assertFalse(supervisor.hasCompletedRefresh())
        supervisor.refresh()
        assertTrue(supervisor.hasCompletedRefresh())
        assertFalse(supervisor.hasOnlineDevice())
    }
}
