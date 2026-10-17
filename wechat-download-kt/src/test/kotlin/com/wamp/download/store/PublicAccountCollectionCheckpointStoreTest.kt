package com.wamp.download.store

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

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
}
