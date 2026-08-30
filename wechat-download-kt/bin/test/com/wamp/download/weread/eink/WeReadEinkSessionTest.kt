package com.wamp.download.weread.eink

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WeReadEinkSessionTest {
    @Test
    fun `refresh failure keeps the previous credentials in memory and on disk`() {
        val credentials = credentials()
        val credentialFile = Files.createTempFile("weread-eink-credentials-", ".json").toFile()
        val store = WeReadEinkCredentialStore(credentialFile)
        store.save(credentials)
        val session = WeReadEinkSession(
            initialCredentials = credentials,
            transport = WeReadEinkTransport {
                WeReadEinkResponse(503, "service unavailable")
            },
            credentialStore = store,
            baseUrl = "https://eink.example.test",
        )

        try {
            assertFailsWith<WeReadEinkApiException> { session.refresh() }

            assertEquals(credentials, session.credentials())
            assertEquals(credentials, store.load())
        } finally {
            File(credentialFile.absolutePath).delete()
        }
    }

    private fun credentials() = WeReadEinkCredentials(
        vid = "vid-1",
        accessToken = "access-1",
        refreshToken = "refresh-1",
        deviceId = "device-1",
    )
}