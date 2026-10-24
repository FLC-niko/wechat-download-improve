package com.wamp.download.weread

import com.wamp.download.store.Database
import com.wamp.download.store.SubscriptionStore
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class WeReadSubscriptionRegistrarTest {
    @Test
    fun `resolveOnly does not persist a subscription`() {
        withTemporaryDatabase {
            val registrar = registrar()
            val account = registrar.resolveOnly("海珠检查")

            assertNotNull(account)
            assertEquals("广州海珠发布", account.nickname)
            assertNull(SubscriptionStore.findByNickname("广州海珠发布"))
        }
    }

    @Test
    fun `local subscription resolves without network when target nickname exists`() {
        withTemporaryDatabase {
            SubscriptionStore.add(
                fakeid = "ZmFrZS1iaXo=",
                nickname = "广州海珠发布",
                alias = "海珠发布",
                headImg = "cover",
            )
            SubscriptionStore.saveWereadMpId("ZmFrZS1iaXo=", "MP_WXS_3295180586")
            val registrar = WeReadSubscriptionRegistrar(null)

            val account = registrar.resolveOnly("广州海珠发布")

            assertNotNull(account)
            assertEquals("ZmFrZS1iaXo=", account.fakeid)
            assertEquals("MP_WXS_3295180586", account.bookId)
            assertEquals("广州海珠发布", account.nickname)
        }
    }

    @Test
    fun `resolveAndSave persists fakeid mpId and profile`() {
        withTemporaryDatabase {
            val registrar = registrar()
            val subscription = registrar.resolveAndSave("海珠检查")

            assertNotNull(subscription)
            assertEquals("ZmFrZS1iaXo=", subscription.fakeid)
            assertEquals("MP_WXS_3295180586", subscription.wereadMpId)
            assertEquals("广州海珠发布", subscription.nickname)
            assertEquals("海珠发布", subscription.alias)
        }
    }

    private fun registrar(): WeReadSubscriptionRegistrar =
        WeReadSubscriptionRegistrar { keyword ->
            if (keyword == "海珠检查") {
                WeReadDirectoryAccount(
                    fakeid = "ZmFrZS1iaXo=",
                    bookId = "MP_WXS_3295180586",
                    nickname = "广州海珠发布",
                    alias = "海珠发布",
                    headImg = "cover",
                )
            } else {
                null
            }
        }

    private fun withTemporaryDatabase(block: suspend () -> Unit) {
        val dbFile = Files.createTempFile("wechat-download-registrar-", ".db").toFile()
        dbFile.delete()
        try {
            Database.init(dbFile.absolutePath)
            runBlocking { block() }
        } finally {
            File(dbFile.absolutePath).delete()
            File("${dbFile.absolutePath}-wal").delete()
            File("${dbFile.absolutePath}-shm").delete()
        }
    }
}
