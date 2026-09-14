package com.wamp.download.weread.eink

import com.wamp.download.adb.AdbCommandResult
import com.wamp.download.adb.AdbCommandRunner
import com.wamp.download.adb.AdbDeviceSupervisor
import com.wamp.download.adb.AdbUserOperations
import com.wamp.download.adb.DeviceSlot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertNotSame

class WeReadEinkLoginCoordinatorTest {

    @Test
    fun `AdbDeviceSupervisor 为相同硬件序列号提供全局唯一设备互斥锁`() {
        val runner = AdbCommandRunner { AdbCommandResult(0, "", "") }
        val supervisor = AdbDeviceSupervisor(runner)

        val slotMain = DeviceSlot(serial = "phone-1", userId = 0, hardwareSerial = "HW-XIAOMI-1")
        val slotClone = DeviceSlot(serial = "phone-1", userId = 999, hardwareSerial = "HW-XIAOMI-1")
        val slotOther = DeviceSlot(serial = "phone-2", userId = 0, hardwareSerial = "HW-XIAOMI-2")

        val lockMain = supervisor.getDeviceLock(slotMain)
        val lockClone = supervisor.getDeviceLock(slotClone)
        val lockOther = supervisor.getDeviceLock(slotOther)

        assertSame(lockMain, lockClone, "同一手机的主身与分身必须共享同一把硬件级设备锁")
        assertNotSame(lockMain, lockOther, "不同物理手机应持有独立的设备锁")
    }

    @Test
    fun `WeReadEinkLoginCoordinator 正常配置 ADB 管理器并输出状态快照`() {
        val runner = AdbCommandRunner { AdbCommandResult(0, "", "") }
        val supervisor = AdbDeviceSupervisor(runner)
        val operations = AdbUserOperations(runner)

        WeReadEinkLoginCoordinator.configureAdb(supervisor, operations)
        val status = WeReadEinkLoginCoordinator.status()

        assertNotNull(status)
        assertEquals(false, status["in_progress"])
        assertEquals(false, WeReadEinkLoginCoordinator.isInProgress())
    }
}
