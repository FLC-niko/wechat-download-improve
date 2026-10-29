package com.wamp.download.weread

import com.wamp.download.store.Subscription
import com.wamp.download.store.SubscriptionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

class WeReadSubscriptionRegistrar(
    private val resolver: WeReadSubscriptionResolver?,
) {
    private val logger = LoggerFactory.getLogger(WeReadSubscriptionRegistrar::class.java)

    suspend fun resolveOnly(keyword: String): WeReadDirectoryAccount? {
        val trimmed = keyword.trim()
        if (trimmed.isEmpty()) return null
        val local = SubscriptionStore.findByNickname(trimmed)
        if (local != null) {
            val bookId = local.wereadMpId.ifBlank {
                WeReadDirectoryClient.mpIdFromFakeid(local.fakeid).orEmpty()
            }
            return WeReadDirectoryAccount(
                fakeid = local.fakeid,
                bookId = bookId,
                nickname = local.nickname,
                alias = local.alias,
                headImg = local.headImg,
            )
        }
        val resolver = resolver ?: return null
        return try {
            withContext(Dispatchers.IO) { resolver.resolve(trimmed) }
        } catch (e: Exception) {
            logger.warn("【账号解析】微信读书解析异常: gzh=$keyword, error=${e.message}")
            null
        }
    }

    suspend fun resolveAndSave(keyword: String): Subscription? {
        val account = resolveOnly(keyword) ?: return null
        if (account.fakeid.isBlank()) return null

        return try {
            val nickname = account.nickname.ifBlank { keyword }
            SubscriptionStore.add(
                fakeid = account.fakeid,
                nickname = nickname,
                alias = account.alias,
                headImg = account.headImg,
            )
            SubscriptionStore.updateProfile(
                fakeid = account.fakeid,
                nickname = nickname,
                alias = account.alias,
                headImg = account.headImg,
            )
            if (account.bookId.isNotBlank()) {
                SubscriptionStore.saveWereadMpId(account.fakeid, account.bookId)
            }
            logger.info(
                "【账号解析】微信读书兜底成功: gzh=$keyword, fakeid=${account.fakeid}, " +
                    "bookId=${account.bookId}",
            )
            SubscriptionStore.findByNickname(nickname) ?: SubscriptionStore.get(account.fakeid)
        } catch (e: Exception) {
            logger.warn("【账号解析】微信读书结果落库失败: gzh=$keyword, error=${e.message}")
            null
        }
    }
}
