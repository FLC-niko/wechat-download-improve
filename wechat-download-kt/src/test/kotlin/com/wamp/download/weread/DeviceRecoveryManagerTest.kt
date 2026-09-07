package com.wamp.download.weread

import com.wamp.download.adb.AdbCommandResult
import com.wamp.download.adb.AdbCommandRunner
import com.wamp.download.adb.AdbDeviceSupervisor
import com.wamp.download.adb.DeviceSlot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class DeviceRecoveryManagerTest {

    private fun mockSupervisor(
        devices: List<String>,
        usersMap: Map<String, List<Int>> = mapOf("dev-1" to listOf(0, 999)),
        hwMap: Map<String, String> = mapOf("dev-1" to "HW-DEV-1"),
    ): AdbDeviceSupervisor {
        val runner = AdbCommandRunner { args ->
            when {
                args == listOf("devices", "-l") -> {
                    val stdout = buildString {
                        appendLine("List of devices attached")
                        devices.forEach { appendLine("$it\tdevice product:x model:y") }
                    }
                    AdbCommandResult(0, stdout, "")
                }
                args.contains("ro.serialno") -> {
                    val serial = args[1]
                    AdbCommandResult(0, hwMap[serial] ?: serial, "")
                }
                args.contains("pm") && args.contains("list") -> {
                    val serial = args[1]
                    val users = usersMap[serial] ?: listOf(0)
                    val stdout = buildString {
                        appendLine("Users:")
                        users.forEach { appendLine("UserInfo{$it:name:0}") }
                    }
                    AdbCommandResult(0, stdout, "")
                }
                else -> AdbCommandResult(0, "", "")
            }
        }
        val supervisor = AdbDeviceSupervisor(runner)
        supervisor.refresh()
        return supervisor
    }

    @Test
    fun `未绑定槽位的新账号在 FIXED_DEVICE 模式下仍可分配首个可用槽位`() {
        val supervisor = mockSupervisor(listOf("dev-1"))
        val manager = DeviceRecoveryManager(supervisor, scope = CoroutineScope(SupervisorJob() + Dispatchers.IO))
        val account = WeReadAccount(
            vid = "12345",
            credentials = WeReadWebCredentials(wrVid = "12345", wrSkey = "dummy"),
            boundSerial = "",
            boundUserId = 0,
            bindingStrategy = "FIXED_DEVICE",
        )

        val slot = manager.chooseSlot(account)
        assertNotNull(slot)
        assertEquals("dev-1", slot.serial)
        assertEquals(0, slot.userId)
        assertEquals("HW-DEV-1", slot.hardwareSerial)
    }

    @Test
    fun `已绑定槽位的账号在 FIXED_DEVICE 模式下严格匹配硬件序列号与用户`() {
        val supervisor = mockSupervisor(listOf("dev-1"))
        val manager = DeviceRecoveryManager(supervisor, scope = CoroutineScope(SupervisorJob() + Dispatchers.IO))
        val account = WeReadAccount(
            vid = "12345",
            credentials = WeReadWebCredentials(wrVid = "12345", wrSkey = "dummy"),
            boundSerial = "HW-DEV-1",
            boundUserId = 999,
            bindingStrategy = "FIXED_DEVICE",
        )

        val slot = manager.chooseSlot(account)
        assertNotNull(slot)
        assertEquals("dev-1", slot.serial)
        assertEquals(999, slot.userId)
    }

    @Test
    fun `已绑定设备离线时 FIXED_DEVICE 不漂移但 ELASTIC_DEVICE 允许借用其他在线槽位`() {
        val supervisor = mockSupervisor(listOf("dev-other"))
        val manager = DeviceRecoveryManager(supervisor, scope = CoroutineScope(SupervisorJob() + Dispatchers.IO))

        val fixedAccount = WeReadAccount(
            vid = "111",
            credentials = WeReadWebCredentials(wrVid = "111", wrSkey = "dummy"),
            boundSerial = "dev-offline",
            boundUserId = 0,
            bindingStrategy = "FIXED_DEVICE",
        )
        assertNull(manager.chooseSlot(fixedAccount))

        val elasticAccount = WeReadAccount(
            vid = "222",
            credentials = WeReadWebCredentials(wrVid = "222", wrSkey = "dummy"),
            boundSerial = "dev-offline",
            boundUserId = 0,
            bindingStrategy = "ELASTIC_DEVICE",
        )
        val elasticSlot = manager.chooseSlot(elasticAccount)
        assertNotNull(elasticSlot)
        assertEquals("dev-other", elasticSlot.serial)
    }

    @Test
    fun `recover 在无可用设备槽位时返回失败原因并不抛出异常`() = kotlinx.coroutines.runBlocking {
        val supervisor = mockSupervisor(emptyList()) // 无在线设备
        val manager = DeviceRecoveryManager(supervisor, scope = CoroutineScope(SupervisorJob() + Dispatchers.IO))

        // 临时注册一个测试账号
        val testVid = "test-no-slot-vid"
        val account = WeReadAccount(
            vid = testVid,
            credentials = WeReadWebCredentials(wrVid = testVid, wrSkey = "dummy"),
            boundSerial = "offline-device",
            boundUserId = 0,
            bindingStrategy = "FIXED_DEVICE",
        )
        WeReadAccountPool.setForTest(account)

        val failure = manager.recover(testVid)
        assertEquals("没有可用的 ADB 设备槽位", failure)

        WeReadAccountPool.removeForTest(testVid)
    }
}

