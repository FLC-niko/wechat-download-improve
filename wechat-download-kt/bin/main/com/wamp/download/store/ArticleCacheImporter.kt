package com.wamp.download.store

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import org.jetbrains.exposed.sql.*
import org.slf4j.LoggerFactory
import java.io.File
import java.time.Instant
import java.util.Base64

/**
 * 历史文章 JSON 文件导入至 SQLite 缓存工具
 * 用于将 we-mp-rss-kotlin 的 articles 目录 JSON 迁移进 Subscriptions 和 Articles 表
 */
object ArticleCacheImporter {
    private val logger = LoggerFactory.getLogger(ArticleCacheImporter::class.java)
    private val gson = Gson()

    data class RawArticle(
        val id: String = "",
        val title: String = "",
        val url: String = "",
        @SerializedName("publish_time") val publishTime: Long = 0L,
        @SerializedName("publish_date") val publishDate: String = "",
    )

    data class RawMetadata(
        @SerializedName("mp_name") val mpName: String = "",
        @SerializedName("mp_id") val mpId: String = "",
        @SerializedName("total_articles") val totalArticles: Int = 0,
        @SerializedName("last_fetch_time") val lastFetchTime: Long = 0L,
    )

    data class RawArticleFile(
        val metadata: RawMetadata = RawMetadata(),
        val articles: List<RawArticle> = emptyList(),
    )

    data class ImportResult(
        val mpName: String,
        val fakeid: String,
        val mpId: String,
        val totalInFile: Int,
        val inserted: Int,
        val skipped: Int,
    )

    /**
     * 导入指定目录下的所有 *_articles.json 文件
     */
    fun importDirectory(dir: File): List<ImportResult> {
        if (!dir.exists() || !dir.isDirectory) {
            logger.error("指定目录不存在: ${dir.absolutePath}")
            return emptyList()
        }

        val jsonFiles = dir.listFiles()?.filter { it.isFile && it.name.endsWith(".json") }?.sortedBy { it.name }
            ?: return emptyList()

        logger.info("开始扫描目录 [${dir.absolutePath}]，发现 ${jsonFiles.size} 个 JSON 文件")
        val results = mutableListOf<ImportResult>()

        for (file in jsonFiles) {
            try {
                val result = importFile(file)
                results.add(result)
                logger.info("【导入完成】${result.mpName} (${file.name}): 文件中 ${result.totalInFile} 篇, 新增入库 ${result.inserted} 篇, 跳过已存在 ${result.skipped} 篇")
            } catch (e: Exception) {
                logger.error("导入文件失败: ${file.name}, error=${e.message}", e)
            }
        }

        val totalInserted = results.sumOf { it.inserted }
        val totalProcessed = results.sumOf { it.totalInFile }
        logger.info("全部导入完成！处理公众号 ${results.size} 个，文章记录 $totalProcessed 条，实际新增写入 SQLite $totalInserted 条")
        return results
    }

    /**
     * 导入单个 JSON 文件
     */
    fun importFile(file: File): ImportResult {
        val content = file.readText(Charsets.UTF_8)
        val data = gson.fromJson(content, RawArticleFile::class.java)

        val meta = data.metadata
        val mpName = meta.mpName.ifBlank { file.name.removeSuffix("_articles.json") }
        val mpId = meta.mpId.ifBlank { "" }

        val decodedBiz = if (mpId.isNotBlank()) mpId.removePrefix("MP_WXS_") else ""
        val fakeid = if (decodedBiz.isNotBlank()) {
            Base64.getEncoder().encodeToString(decodedBiz.toByteArray(Charsets.UTF_8))
        } else {
            Base64.getEncoder().encodeToString(mpName.toByteArray(Charsets.UTF_8))
        }

        var insertedCount = 0
        var skippedCount = 0

        Database.query {
            // 1. 确保订阅信息存在
            val existingSub = Subscriptions.selectAll().where { Subscriptions.fakeid eq fakeid }.singleOrNull()
            if (existingSub == null) {
                Subscriptions.insert {
                    it[Subscriptions.fakeid] = fakeid
                    it[Subscriptions.nickname] = mpName
                    it[Subscriptions.alias] = ""
                    it[Subscriptions.headImg] = ""
                    it[Subscriptions.createdAt] = if (meta.lastFetchTime > 0) meta.lastFetchTime else Instant.now().epochSecond
                    it[Subscriptions.lastPoll] = if (meta.lastFetchTime > 0) meta.lastFetchTime else 0L
                    it[Subscriptions.wereadMpId] = mpId
                }
            } else {
                val needsNicknameUpdate = mpName.isNotBlank() && existingSub[Subscriptions.nickname].isBlank()
                val needsMpIdUpdate = mpId.isNotBlank() && existingSub[Subscriptions.wereadMpId].isBlank()
                val needsPollUpdate = meta.lastFetchTime > existingSub[Subscriptions.lastPoll]

                if (needsNicknameUpdate || needsMpIdUpdate || needsPollUpdate) {
                    Subscriptions.update({ Subscriptions.fakeid eq fakeid }) {
                        if (needsNicknameUpdate) it[Subscriptions.nickname] = mpName
                        if (needsMpIdUpdate) it[Subscriptions.wereadMpId] = mpId
                        if (needsPollUpdate) it[Subscriptions.lastPoll] = meta.lastFetchTime
                    }
                }
            }

            // 2. 批量读取当前公众号已有的 link
            val existingLinks = Articles.selectAll()
                .where { Articles.fakeid eq fakeid }
                .map { it[Articles.link] }
                .toHashSet()

            // 3. 批量插入新文章
            for (raw in data.articles) {
                val link = raw.url.ifBlank { raw.id }
                if (link.isBlank()) continue

                if (existingLinks.contains(link)) {
                    skippedCount++
                    continue
                }

                Articles.insert {
                    it[Articles.fakeid] = fakeid
                    it[Articles.aid] = raw.id
                    it[Articles.title] = raw.title
                    it[Articles.link] = raw.url
                    it[Articles.digest] = ""
                    it[Articles.cover] = ""
                    it[Articles.author] = mpName
                    it[Articles.content] = ""
                    it[Articles.plainContent] = ""
                    it[Articles.publishTime] = raw.publishTime
                    it[Articles.fetchedAt] = if (meta.lastFetchTime > 0) meta.lastFetchTime else Instant.now().epochSecond
                    it[Articles.articleSource] = "cache_import"
                }
                existingLinks.add(link)
                insertedCount++
            }
        }

        return ImportResult(
            mpName = mpName,
            fakeid = fakeid,
            mpId = mpId,
            totalInFile = data.articles.size,
            inserted = insertedCount,
            skipped = skippedCount,
        )
    }
}
