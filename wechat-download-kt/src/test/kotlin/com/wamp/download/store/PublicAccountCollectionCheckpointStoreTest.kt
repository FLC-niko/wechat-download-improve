package com.wamp.download.store

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

/** oldestCollectedAt == 0 表示起始边界未知，绝不能宣称覆盖任何历史区间。 */
class PublicAccountCollectionCheckpointStoreTest {

    private fun checkpoint(oldest: Long, newest: Long) = PublicAccountCollectionCheckpoint(
        fakeid = "fake",
        mpId = "MP_WXS_1",
        source = "eink",
        oldestCollectedAt = oldest,
        newestCollectedAt = newest,
        lastSuccessAt = 1L,
        pagesCollected = 1,
        articlesCollected = 1,
    )

    @Test
    fun `unknown oldest never claims historical coverage`() {
        val cp = checkpoint(oldest = 0L, newest = 1792204451L)
        assertFalse(cp.covers(1721433600L, 1723161600L))
        assertFalse(cp.covers(1704067200L, 1735689600L))
    }

    @Test
    fun `known range still covers inside windows`() {
        val cp = checkpoint(oldest = 1721433600L, newest = 1792204451L)
        assertTrue(cp.covers(1721433600L, 1723161600L))
        assertFalse(cp.covers(1721433599L, 1723161600L))
        assertFalse(cp.covers(1721433600L, 1792204452L))
    }

    @Test
    fun `targeted scan persists offset cursor and freezes original end`() {
        val db = Files.createTempFile("targeted-scan-store-", ".db").toFile()
        db.delete()
        try {
            Database.init(db.absolutePath)
            WeReadEinkTargetedScanStore.saveProgress("vid", "mp", 100L, 200L, "targeted", 50L, 5L, 7L, 1_000L, 120L)
            WeReadEinkTargetedScanStore.saveProgress("vid", "mp", 100L, 300L, "targeted", 100L, 99L, 8L, 1_000L, 80L)

            val scan = checkNotNull(WeReadEinkTargetedScanStore.get("vid", "mp", 100L, "targeted"))
            assertEquals(200L, scan.requestedEnd)
            assertEquals(100L, scan.nextOffset)
            assertEquals(5L, scan.baseSynckey)
            assertEquals(8L, scan.synckey)
            assertEquals(80L, scan.oldestCollectedAt)
            assertEquals(null, WeReadEinkTargetedScanStore.get("vid", "mp", 0L, "history"))
            WeReadEinkTargetedScanStore.delete("vid", "mp", 100L, "targeted")
            assertEquals(null, WeReadEinkTargetedScanStore.get("vid", "mp", 100L, "targeted"))
        } finally {
            listOf(db.absolutePath, "${db.absolutePath}-wal", "${db.absolutePath}-shm").forEach { File(it).delete() }
        }
    }
}
