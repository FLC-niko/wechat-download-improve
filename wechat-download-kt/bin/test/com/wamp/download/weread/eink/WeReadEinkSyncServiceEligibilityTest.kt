package com.wamp.download.weread.eink

import com.wamp.download.store.Subscription
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WeReadEinkSyncServiceEligibilityTest {
    private val service = WeReadEinkSyncService(
        session = WeReadEinkSession(
            initialCredentials = WeReadEinkCredentials(
                vid = "vid-1",
                accessToken = "access-token",
                refreshToken = "refresh-token",
                deviceId = "device-1",
            ),
        ),
    )

    @Test
    fun `existing mpId makes subscription eligible without network access`() {
        assertTrue(service.canSync(Subscription(fakeid = "fakeid", wereadMpId = "MP_WXS_1")))
    }

    @Test
    fun `invalid fakeid without mpId is not eligible for E-Ink`() {
        assertFalse(service.canSync(Subscription(fakeid = "not-base64")))
    }
}