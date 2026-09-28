package com.wamp.download.weread.eink

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class WeReadEinkAccountStoreTest {

    private fun tempDir(): File = Files.createTempDirectory("eink-accounts-test").toFile()

    @Test
    fun `legacy single-file credentials are migrated with dedicated identity`() {
        val root = tempDir()
        val legacy = File(root, "legacy.json")
        val credentials = WeReadEinkCredentials(
            vid = "909705585",
            accessToken = "token",
            refreshToken = "refresh",
            deviceId = "eink3346912250000000001",
        )
        WeReadEinkCredentialStore(legacy).save(credentials)
        val accountsDir = File(root, "accounts")

        val store = WeReadEinkAccountStore(dir = accountsDir, legacyFile = legacy)
        val records = store.loadAll()

        assertEquals(1, records.size)
        val record = records.single()
        assertEquals("909705585", record.vid)
        assertTrue(record.installId.isNotBlank(), "迁移账号必须补一个专属 installId")
        assertEquals(WeReadEinkDeviceModels.DEFAULT_MODEL, record.deviceModel)
        assertTrue(File(accountsDir, "909705585.json").isFile, "迁移后必须写入多账号目录")
        assertTrue(legacy.isFile, "旧文件必须保留为备份")
    }

    @Test
    fun `accounts keep independent device identities across credential updates`() {
        val store = WeReadEinkAccountStore(dir = File(tempDir(), "accounts"))
        store.save(
            WeReadEinkAccountRecord.forNewAccount(
                WeReadEinkCredentials("111", "a", "r1", "dev1"),
                WeReadEinkDeviceModels.POOL[0],
            ),
        )
        store.save(
            WeReadEinkAccountRecord.forNewAccount(
                WeReadEinkCredentials("222", "b", "r2", "dev2"),
                WeReadEinkDeviceModels.POOL[1],
            ),
        )

        val loaded = store.loadAll()
        assertEquals(2, loaded.size)
        val first = store.loadRecord("111")!!
        val second = store.loadRecord("222")!!
        assertNotEquals(first.deviceModel, second.deviceModel, "不同账号禁止共享设备型号")
        assertNotEquals(
            first.profile().versionHeaders["User-Agent"],
            second.profile().versionHeaders["User-Agent"],
            "不同账号必须使用不同 User-Agent",
        )

        // 令牌刷新只更新凭证，设备身份必须原样保留
        store.saveCredentials(first.credentials.copy(accessToken = "rotated"))
        val refreshed = store.loadRecord("111")!!
        assertEquals(first.deviceModel, refreshed.deviceModel)
        assertEquals(first.installId, refreshed.installId)
        assertEquals("rotated", refreshed.credentials.accessToken)
    }

    @Test
    fun `pickUnused skips occupied models`() {
        val occupied = setOf(
            WeReadEinkDeviceModels.POOL[0].model,
            WeReadEinkDeviceModels.POOL[1].model,
        )
        val picked = WeReadEinkDeviceModels.pickUnused(occupied)
        assertTrue(picked.model !in occupied)
        assertEquals(WeReadEinkDeviceModels.POOL[2].model, picked.model)
    }
}
