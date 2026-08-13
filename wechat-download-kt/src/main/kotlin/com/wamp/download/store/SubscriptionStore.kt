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
            .map {
                Subscription(
                    fakeid = it[Subscriptions.fakeid],
                    nickname = it[Subscriptions.nickname],
                    alias = it[Subscriptions.alias],
                    headImg = it[Subscriptions.headImg],
                    createdAt = it[Subscriptions.createdAt],
                    lastPoll = it[Subscriptions.lastPoll],
                )
            }
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
                .map {
                    Subscription(
                        fakeid = it[Subscriptions.fakeid],
                        nickname = it[Subscriptions.nickname],
                        alias = it[Subscriptions.alias],
                        headImg = it[Subscriptions.headImg],
                        createdAt = it[Subscriptions.createdAt],
                        lastPoll = it[Subscriptions.lastPoll],
                    )
                }
            rows to total
        }

    fun allFakeids(): List<String> = Database.query {
        Subscriptions.selectAll().map { it[Subscriptions.fakeid] }
    }

    fun updateLastPoll(fakeid: String) = Database.query {
        Subscriptions.update({ Subscriptions.fakeid eq fakeid }) {
            it[Subscriptions.lastPoll] = Instant.now().epochSecond
        }
    }
}
