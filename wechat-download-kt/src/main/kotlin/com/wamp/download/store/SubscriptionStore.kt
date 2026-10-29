package com.wamp.download.store

import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import org.slf4j.LoggerFactory
import java.time.Instant

data class Subscription(
    val fakeid: String,
    val nickname: String = "",
    val alias: String = "",
    val headImg: String = "",
    val createdAt: Long = Instant.now().epochSecond,
    val lastPoll: Long = 0L,
    val wereadMpId: String = "",
)

/**
 * 订阅管理 — 对齐 Python 版 rss_store.py 的订阅相关函数
 */
object SubscriptionStore {
    private val logger = LoggerFactory.getLogger(SubscriptionStore::class.java)

    fun add(fakeid: String, nickname: String = "", alias: String = "", headImg: String = ""): Boolean =
        Database.query {
            Subscriptions.insertIgnore {
                it[Subscriptions.fakeid] = fakeid
                it[Subscriptions.nickname] = nickname
                it[Subscriptions.alias] = alias
                it[Subscriptions.headImg] = headImg
                it[Subscriptions.createdAt] = Instant.now().epochSecond
            }.insertedCount > 0
        }

    fun remove(fakeid: String): Boolean = Database.query {
        Subscriptions.deleteWhere { Subscriptions.fakeid eq fakeid } > 0
    }

    fun get(fakeid: String): Subscription? = Database.query {
        Subscriptions.selectAll()
            .where { Subscriptions.fakeid eq fakeid }
            .map { it.toSubscription() }
            .singleOrNull()
    }

    fun list(kw: String? = null, limit: Int = 100, offset: Int = 0): Pair<List<Subscription>, Int> =
        Database.query {
            val query = if (!kw.isNullOrEmpty()) {
                Subscriptions.selectAll().where {
                    (Subscriptions.nickname like "%$kw%") or (Subscriptions.alias like "%$kw%")
                }
            } else {
                Subscriptions.selectAll()
            }

            val total = query.count().toInt()
            val rows = query
                .orderBy(Subscriptions.createdAt, SortOrder.DESC)
                .limit(limit, offset.toLong())
                .map { it.toSubscription() }
            rows to total
        }

    fun allFakeids(): List<String> = Database.query {
        Subscriptions.selectAll().map { it[Subscriptions.fakeid] }
    }

    fun findByNickname(nickname: String): Subscription? = Database.query {
        Subscriptions.selectAll()
            .where { Subscriptions.nickname eq nickname }
            .map { it.toSubscription() }
            .singleOrNull()
    }

    fun updateLastPoll(fakeid: String) = Database.query {
        Subscriptions.update({ Subscriptions.fakeid eq fakeid }) {
            it[Subscriptions.lastPoll] = Instant.now().epochSecond
        }
    }

    /** 记录该公众号的微信读书 mpId（供回退通道复用） */
    fun saveWereadMpId(fakeid: String, mpId: String) = Database.query {
        Subscriptions.update({ Subscriptions.fakeid eq fakeid }) {
            it[Subscriptions.wereadMpId] = mpId
        }
    }

    fun updateProfile(fakeid: String, nickname: String, alias: String, headImg: String) = Database.query {
        if (nickname.isBlank() && alias.isBlank() && headImg.isBlank()) return@query 0
        Subscriptions.update({ Subscriptions.fakeid eq fakeid }) {
            if (nickname.isNotBlank()) it[Subscriptions.nickname] = nickname
            if (alias.isNotBlank()) it[Subscriptions.alias] = alias
            if (headImg.isNotBlank()) it[Subscriptions.headImg] = headImg
        }
    }

    /** 按微信读书 mpId 反查公众号，找不到返回 null */
    fun findByWereadMpId(mpId: String): Subscription? = Database.query {
        Subscriptions.selectAll()
            .where { Subscriptions.wereadMpId eq mpId }
            .map { it.toSubscription() }
            .singleOrNull()
    }

    private fun ResultRow.toSubscription() = Subscription(
        fakeid = this[Subscriptions.fakeid],
        nickname = this[Subscriptions.nickname],
        alias = this[Subscriptions.alias],
        headImg = this[Subscriptions.headImg],
        createdAt = this[Subscriptions.createdAt],
        lastPoll = this[Subscriptions.lastPoll],
        wereadMpId = this[Subscriptions.wereadMpId],
    )
}
