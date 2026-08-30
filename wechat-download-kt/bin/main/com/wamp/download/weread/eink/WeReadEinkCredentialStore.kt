package com.wamp.download.weread.eink

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions

/** Stores E-Ink credentials independently from the legacy Web cookie credentials. */
class WeReadEinkCredentialStore(
    private val file: File,
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create(),
) {
    fun load(): WeReadEinkCredentials? {
        if (!file.exists()) return null
        return try {
            gson.fromJson(file.readText(), WeReadEinkCredentials::class.java)
        } catch (error: Exception) {
            throw IllegalStateException("failed to load E-Ink credentials from ${file.name}", error)
        }
    }

    /** Writes a complete credential snapshot without exposing secrets in logs or errors. */
    @Synchronized
    fun save(credentials: WeReadEinkCredentials) {
        file.parentFile?.mkdirs()
        val temp = Files.createTempFile(file.parentFile?.toPath(), ".${file.name}.", ".tmp").toFile()
        try {
            temp.writeText(gson.toJson(credentials))
            setPrivatePermissions(temp)
            try {
                Files.move(
                    temp.toPath(),
                    file.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(
                    temp.toPath(),
                    file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
        } catch (error: Exception) {
            temp.delete()
            throw IllegalStateException("failed to save E-Ink credentials to ${file.name}", error)
        }
    }

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
