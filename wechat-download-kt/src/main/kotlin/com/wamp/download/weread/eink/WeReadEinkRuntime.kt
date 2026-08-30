package com.wamp.download.weread.eink

import com.wamp.download.config.AppConfig
import org.slf4j.LoggerFactory
import java.io.File

/** Creates the E-Ink session from its independent persisted credential file. */
object WeReadEinkRuntime {
    private val logger = LoggerFactory.getLogger(WeReadEinkRuntime::class.java)

    fun createSyncService(): WeReadEinkSyncService? {
        if (!AppConfig.wereadEinkEnabled) {
            logger.info("E-Ink 同步通道未启用")
            return null
        }

        val credentialFile = File(AppConfig.wereadEinkCredentialsFile)
        val credentialStore = WeReadEinkCredentialStore(credentialFile)
        val credentials = try {
            credentialStore.load()
        } catch (error: Exception) {
            logger.warn("E-Ink 凭证读取失败，仅使用本地缓存: ${error.message}")
            return null
        }
        if (credentials == null) {
            logger.warn("未找到 E-Ink 凭证文件，仅使用本地缓存: ${credentialFile.name}")
            return null
        }

        return try {
            val session = WeReadEinkSession(
                initialCredentials = credentials,
                credentialStore = credentialStore,
                baseUrl = AppConfig.wereadEinkBaseUrl,
            )
            WeReadEinkSyncService(
                session = session,
                baseUrl = AppConfig.wereadEinkBaseUrl,
                pageSize = AppConfig.wereadEinkPageSize,
                maxPages = AppConfig.wereadEinkMaxPages,
            )
        } catch (error: Exception) {
            logger.warn("E-Ink 同步通道初始化失败，仅使用本地缓存: ${error.message}")
            null
        }
    }
}