package com.wamp.download.weread.eink

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions

/**
 * E-Ink 多账号存储：`data/weread_eink_accounts/{vid}.json`，每个账号一个文件，
 * 内含凭证与该账号专属的设备身份。
 *
 * - 首次启动且目录为空时，自动迁移旧单账号文件（迁移后旧文件改名为 `.migrated`
 *   保留为历史备份，不会被二次导入，防止账号目录清空后复活已删除账号）；
 * - 写入采用临时文件 + 原子替换，并收紧文件权限（仅属主可读写）。
 */
class WeReadEinkAccountStore(
    private val dir: File,
    private val legacyFile: File? = null,
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create(),
) {
    private val logger = LoggerFactory.getLogger(WeReadEinkAccountStore::class.java)

    @Synchronized
    fun loadAll(): List<WeReadEinkAccountRecord> {
        dir.mkdirs()
        val files = dir.listFiles { file -> file.isFile && file.name.endsWith(".json") }
            ?.sortedBy { it.name }
            .orEmpty()
        val records = files.mapNotNull { file ->
            runCatching { gson.fromJson(file.readText(), WeReadEinkAccountRecord::class.java) }
                .onFailure { logger.warn("E-Ink 账号文件解析失败，已跳过: ${file.name}, ${it.message}") }
                .getOrNull()
                ?.takeIf { it.credentials.vid.isNotBlank() }
        }
        if (records.isNotEmpty()) return records

        val legacy = legacyFile?.takeIf { it.isFile } ?: return emptyList()
        return runCatching {
            val credentials = WeReadEinkCredentialStore(legacy).load() ?: return@runCatching emptyList()
            val record = WeReadEinkAccountRecord.migrated(credentials)
            save(record)
            // 迁移成功后立即给旧文件改名：账号目录日后被清空（例如账号全部删除）时，
            // loadAll 不得再次从未改名的旧文件复活已删除的账号。改名后的文件仍留在
            // 原目录，继续充当迁移前的历史备份。
            val backup = File(legacy.parentFile, "${legacy.name}.migrated")
            if (legacy.renameTo(backup)) {
                logger.info(
                    "E-Ink 旧单账号凭证已迁移到多账号目录: vid=${record.vid}, model=${record.deviceModel}, " +
                        "legacy=${legacy.name} 已改名为 ${backup.name}（保留为历史备份）",
                )
            } else {
                logger.warn(
                    "E-Ink 旧单账号凭证已迁移到多账号目录: vid=${record.vid}, model=${record.deviceModel}, " +
                        "但旧文件改名失败（不影响运行；账号目录清空前请手动处理）: ${legacy.name}",
                )
            }
            listOf(record)
        }.onFailure {
            logger.warn("E-Ink 旧单账号凭证迁移失败: ${it.message}")
        }.getOrDefault(emptyList())
    }

    @Synchronized
    fun save(record: WeReadEinkAccountRecord) {
        val target = File(dir, "${record.vid}.json")
        dir.mkdirs()
        val temp = Files.createTempFile(dir.toPath(), ".${record.vid}.", ".tmp").toFile()
        try {
            temp.writeText(gson.toJson(record))
            setPrivatePermissions(temp)
            try {
                Files.move(
                    temp.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (error: Exception) {
            temp.delete()
            throw IllegalStateException("failed to save E-Ink account ${record.vid}", error)
        }
    }

    @Synchronized
    fun delete(vid: String): Boolean {
        if (vid.isBlank()) return false
        return File(dir, "$vid.json").delete()
    }

    fun loadRecord(vid: String): WeReadEinkAccountRecord? =
        loadAll().firstOrNull { it.vid == vid }

    /**
     * 仅更新凭证（令牌刷新路径）：合并回该账号记录，保留设备身份元数据；
     * 找不到既有记录时按迁移规则补建，绝不静默丢弃凭证。
     */
    @Synchronized
    fun saveCredentials(credentials: WeReadEinkCredentials) {
        val existing = loadRecord(credentials.vid)
        val record = existing?.copy(credentials = credentials)
            ?: WeReadEinkAccountRecord.migrated(credentials)
        save(record)
    }

    /** 供 Session 刷新回写使用的凭证通道（按 vid 定位记录）。 */
    fun credentialSink(vid: String): WeReadEinkCredentialSink = object : WeReadEinkCredentialSink {
        override fun load(): WeReadEinkCredentials? = loadRecord(vid)?.credentials

        override fun save(credentials: WeReadEinkCredentials) = saveCredentials(credentials)
    }

    /** 已占用的设备型号集合，用于为新账号挑选不重复的设备身份。 */
    fun usedDeviceModels(): Set<String> = loadAll().map { it.deviceModel }.toSet()

    private fun setPrivatePermissions(target: File) {
        try {
            Files.setPosixFilePermissions(
                target.toPath(),
                PosixFilePermissions.fromString("rw-------"),
            )
        } catch (_: UnsupportedOperationException) {
            target.setReadable(false, false)
            target.setReadable(true, true)
            target.setWritable(false, false)
            target.setWritable(true, true)
        }
    }
}
