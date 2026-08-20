package com.wamp.download.store

import com.wamp.download.config.AppConfig
import org.jetbrains.exposed.sql.selectAll
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertTrue

class ArticleCacheImporterTest {

    @Test
    fun `导入所有历史文章JSON至SQLite缓存`() {
        println("===== [1] 初始化配置与数据库 =====")
        AppConfig.load()
        Database.init()

        val articlesDir = File("../we-mp-rss-all/we-mp-rss-kotlin/articles")
        assertTrue(articlesDir.exists(), "articles 目录不存在: ${articlesDir.absolutePath}")

        println("===== [2] 执行导入 =====")
        val results = ArticleCacheImporter.importDirectory(articlesDir)

        println("\n===== [3] 导入明细汇总 =====")
        println("%-28s %-20s %-18s %-10s %-10s %-10s".format("公众号名称", "fakeid", "mpId", "文件篇数", "新增入库", "跳过已存"))
        println("-".repeat(100))
        for (r in results) {
            println("%-28s %-20s %-18s %-10d %-10d %-10d".format(
                r.mpName.take(14),
                r.fakeid.take(16),
                r.mpId,
                r.totalInFile,
                r.inserted,
                r.skipped
            ))
        }

        println("\n===== [4] 数据库当前总览 =====")
        Database.query {
            val totalSubs = Subscriptions.selectAll().count()
            val totalArticles = Articles.selectAll().count()
            println("当前 Subscriptions 表总记录数: $totalSubs")
            println("当前 Articles 表总文章缓存数: $totalArticles")
        }
    }
}
