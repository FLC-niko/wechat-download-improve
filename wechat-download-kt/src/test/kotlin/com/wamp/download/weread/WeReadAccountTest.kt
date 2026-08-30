package com.wamp.download.weread

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WeReadAccountTest {
    @Test
    fun `active account with valid credentials is available`() {
        val account = account(status = AccountStatus.ACTIVE, cooldownUntil = 0L)

        assertTrue(account.hasWebCredentials())
        assertTrue(account.isAvailable(now = 1_000L))
    }

    @Test
    fun `empty web credentials are never available`() {
        val account = account(
            credentials = WeReadWebCredentials(wrVid = "vid-1"),
            status = AccountStatus.ACTIVE,
        )

        assertFalse(account.hasWebCredentials())
        assertFalse(account.isAvailable(now = 1_000L))
    }

    @Test
    fun `cooldown expires at runtime while disabled remains unavailable`() {
        val cooling = account(status = AccountStatus.COOLDOWN, cooldownUntil = 2_000L)
        val cooledDown = account(status = AccountStatus.COOLDOWN, cooldownUntil = 500L)
        val disabled = account(status = AccountStatus.DISABLED, cooldownUntil = 0L)

        assertFalse(cooling.isAvailable(now = 1_000L))
        assertFalse(disabled.isAvailable(now = 1_000L))
        assertTrue(cooledDown.isAvailable(now = 1_000L))
    }

    @Test
    fun `expired account stays unavailable until credentials are replaced and status restored`() {
        val account = account(status = AccountStatus.EXPIRED, cooldownUntil = 0L)

        assertFalse(account.isAvailable(now = 1_000L))

        account.credentials = account.credentials.copy(wrSkey = "new-skey")
        account.status = AccountStatus.ACTIVE

        assertTrue(account.isAvailable(now = 1_000L))
    }

    private fun account(
        credentials: WeReadWebCredentials = WeReadWebCredentials(wrVid = "vid-1", wrSkey = "skey-1"),
        status: AccountStatus,
        cooldownUntil: Long = 0L,
    ): WeReadAccount = WeReadAccount(
        vid = "vid-1",
        credentials = credentials,
        status = status,
        cooldownUntil = cooldownUntil,
    )
}
