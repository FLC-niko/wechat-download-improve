package com.wamp.download.adb

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AdbDeviceSupervisorTest {

    @Test
    fun `只把 device 状态纳入在线设备并解析用户空间`() {
        val runner = FakeAdbRunner(
            mapOf(
                listOf("devices", "-l") to AdbCommandResult(
                    0,
                    """
                    List of devices attached
                    usb-1 device product:foo model:Foo transport_id:1
                    192.0.2.10:5555 unauthorized transport_id:2
                    192.0.2.11:5555 offline transport_id:3
                    """.trimIndent(),
                    "",
                ),
                listOf("-s", "usb-1", "shell", "pm", "list", "users") to AdbCommandResult(
                    0,
                    "Users:\n    UserInfo{0:Owner:13} running\n    UserInfo{999:XSpace:800010}",
                    "",
                ),
            ),
        )
        val supervisor = AdbDeviceSupervisor(runner, pollIntervalMs = 1000)

        val snapshot = supervisor.refresh()

        assertEquals(RecoveryMode.AUTO_RECOVERABLE, snapshot.mode)
        assertEquals(listOf("usb-1"), snapshot.devices.map { it.serial })
        assertEquals(listOf(0, 999), snapshot.devices.single().userIds)
        assertEquals(listOf(DeviceSlot("usb-1", 0), DeviceSlot("usb-1", 999)), supervisor.availableSlots())
    }

    @Test
    fun `没有 device 时进入手动模式`() {
        val runner = FakeAdbRunner(
            mapOf(
                listOf("devices", "-l") to AdbCommandResult(0, "List of devices attached\n", ""),
            ),
        )
        val supervisor = AdbDeviceSupervisor(runner, pollIntervalMs = 1000)

        val snapshot = supervisor.refresh()

        assertEquals(RecoveryMode.MANUAL_ONLY, snapshot.mode)
        assertTrue(snapshot.devices.isEmpty())
    }

    @Test
    fun `设备断开后从自动恢复切换到手动模式`() {
        val runner = SequenceAdbRunner(
            listOf(
                AdbCommandResult(0, "List of devices attached\nserial-1\tdevice\n", ""),
                AdbCommandResult(0, "Users:\n UserInfo{0:Owner:13} running\n", ""),
                AdbCommandResult(0, "List of devices attached\n", ""),
            ),
        )
        val supervisor = AdbDeviceSupervisor(runner, pollIntervalMs = 1000)

        assertEquals(RecoveryMode.AUTO_RECOVERABLE, supervisor.refresh().mode)
        assertEquals(RecoveryMode.MANUAL_ONLY, supervisor.refresh().mode)
    }

    private class FakeAdbRunner(
        private val responses: Map<List<String>, AdbCommandResult>,
    ) : AdbCommandRunner {
        override fun run(arguments: List<String>): AdbCommandResult =
            responses[arguments] ?: error("未配置 ADB 命令: $arguments")
    }

    private class SequenceAdbRunner(
        private val responses: List<AdbCommandResult>,
    ) : AdbCommandRunner {
        private var index = 0
        override fun run(arguments: List<String>): AdbCommandResult {
            val response = responses[index]
            index++
            return response
        }
    }
}
