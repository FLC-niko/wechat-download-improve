package com.wamp.download.weread

import com.wamp.download.config.AppConfig
import com.wamp.download.weread.eink.OkHttpWeReadEinkTransport
import com.wamp.download.weread.eink.WeReadEinkRuntime
import com.wamp.download.weread.eink.WeReadEinkSession
import com.wamp.download.weread.eink.WeReadEinkTransport

class WeReadDirectoryResolver(
    private val einkSessionProvider: () -> WeReadEinkSession? = WeReadEinkRuntime::directorySession,
    private val webAccountAcquire: () -> WeReadAccount? = WeReadAccountPool::acquire,
    private val webAccountRelease: (String) -> Unit = WeReadAccountPool::release,
    private val transport: WeReadEinkTransport = OkHttpWeReadEinkTransport(),
    private val baseUrl: String = AppConfig.wereadEinkBaseUrl,
) : WeReadSubscriptionResolver {
    override fun resolve(keyword: String): WeReadDirectoryAccount? =
        withEink { it.resolve(keyword) } ?: withWeb { it.resolve(keyword) }

    fun listFollowedAccounts(): List<WeReadDirectoryAccount> =
        withEink { it.listFollowedAccounts() }.orEmpty().ifEmpty {
            withWeb { it.listFollowedAccounts() }.orEmpty()
        }

    private fun <T> withEink(block: (WeReadDirectoryClient) -> T): T? {
        val session = einkSessionProvider() ?: return null
        return runCatching { block(session.directoryClient(baseUrl)) }.getOrNull()
    }

    private fun <T> withWeb(block: (WeReadDirectoryClient) -> T): T? {
        val account = webAccountAcquire() ?: return null
        return try {
            runCatching {
                block(
                    WeReadDirectoryClient(
                        baseUrl = baseUrl,
                        auth = WebDirectoryAuth(account.credentials),
                        transport = transport,
                    ),
                )
            }.getOrNull()
        } finally {
            webAccountRelease(account.vid)
        }
    }
}
