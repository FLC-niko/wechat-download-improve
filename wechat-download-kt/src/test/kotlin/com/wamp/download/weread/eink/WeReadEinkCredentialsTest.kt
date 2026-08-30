package com.wamp.download.weread.eink

import com.google.gson.JsonParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WeReadEinkCredentialsTest {
    @Test
    fun `uses explicit duration and epoch expiry without inventing a default`() {
        val issuedAt = 1_700_000_000_000L
        assertEquals(
            issuedAt + 3_600_000L,
            WeReadEinkTokenExpiry.parse(JsonParser.parseString("{\"expiresIn\":3600}").asJsonObject, issuedAt),
        )
        assertEquals(
            1_800_000_000_000L,
            WeReadEinkTokenExpiry.parse(JsonParser.parseString("{\"expiresAt\":1800000000}").asJsonObject, issuedAt),
        )
        assertNull(WeReadEinkTokenExpiry.parse(JsonParser.parseString("{}").asJsonObject, issuedAt))
    }

    @Test
    fun `unknown expiry remains usable until the server rejects it`() {
        val unknown = credentials(expiresAt = null)
        val known = credentials(expiresAt = 2_000L)

        assertFalse(unknown.accessTokenNeedsRefresh(now = Long.MAX_VALUE))
        assertTrue(known.accessTokenNeedsRefresh(now = 1_500L, skewMs = 500L))
        assertEquals(1L, known.secondsUntilExpiry(now = 1_000L))
    }

    private fun credentials(expiresAt: Long?) = WeReadEinkCredentials(
        vid = "1",
        accessToken = "access",
        refreshToken = "refresh",
        deviceId = "device",
        accessTokenIssuedAt = 1_000L,
        accessTokenExpiresAt = expiresAt,
    )
}
