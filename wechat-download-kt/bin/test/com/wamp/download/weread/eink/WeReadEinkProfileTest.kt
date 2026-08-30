package com.wamp.download.weread.eink

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WeReadEinkProfileTest {
    @Test
    fun `default profile matches the upstream e-ink identity`() {
        val profile = DefaultWeReadEinkProfile

        assertEquals("BOOX", profile.deviceName)
        assertEquals(3, profile.deviceType)
        assertEquals("30", profile.versionHeaders["baseapi"])
        assertEquals("2.1.2.10245900", profile.versionHeaders["appver"])
        assertEquals("11", profile.versionHeaders["osver"])
        assertEquals("900", profile.versionHeaders["channelId"])
        assertEquals(
            "WeRead/2.1.2 WRBrand/Onyx wr_eink Dalvik/2.1.0 (Linux; U; Android 11; BOOX Build/onyx)",
            profile.versionHeaders["User-Agent"],
        )
        assertEquals(mapOf("deviceType" to 3), profile.loginBodyExtras())
    }

    @Test
    fun `refresh signature follows timestamp device random and ignores refresh token`() {
        val profile = DefaultWeReadEinkProfile

        val first = profile.refreshSignature("eink-device", 1700000000000L, 7, "refresh-a")
        val second = profile.refreshSignature("eink-device", 1700000000000L, 7, "refresh-b")

        assertEquals(first, second)
        assertEquals(64, first.length)
        assertEquals(
            "9cc1a97d6346294a24ed5354ab0238e31ea5f566ae1ba0f1c88f39aea03724f6",
            first,
        )
    }

    @Test
    fun `authenticated headers require an access token`() {
        val credentials = WeReadEinkCredentials(
            vid = "1",
            accessToken = null,
            refreshToken = "refresh-token",
            deviceId = "eink-device",
        )

        assertFailsWith<IllegalArgumentException> {
            DefaultWeReadEinkProfile.authHeaders(credentials)
        }
    }
}
