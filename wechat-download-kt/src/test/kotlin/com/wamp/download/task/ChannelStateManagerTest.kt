package com.wamp.download.task

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChannelStateManagerTest {
    @Test
    fun `200013 invalidation does not recover through cooldown`() {
        ChannelStateManager.resetMainChannelAfterCredentialUpdate()
        try {
            ChannelStateManager.markMainChannelInvalid("ret=200013")
            ChannelStateManager.markMainChannelFreqControl(durationMs = 0)

            assertTrue(ChannelStateManager.isMainChannelInvalidated())
            assertFalse(ChannelStateManager.isMainChannelAvailable())
            assertEquals("ret=200013", ChannelStateManager.mainChannelUnavailableReason())
            assertEquals(true, ChannelStateManager.snapshot()["main_invalidated"])
        } finally {
            ChannelStateManager.resetMainChannelAfterCredentialUpdate()
        }
    }
}
