package com.wamp.download.weread

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.wamp.download.weread.eink.OkHttpWeReadEinkTransport
import com.wamp.download.weread.eink.WeReadEinkApiException
import com.wamp.download.weread.eink.WeReadEinkParseException
import com.wamp.download.weread.eink.WeReadEinkRequest
import com.wamp.download.weread.eink.WeReadEinkRequestGate
import com.wamp.download.weread.eink.WeReadEinkResponse
import com.wamp.download.weread.eink.WeReadEinkResponseClassifier
import com.wamp.download.weread.eink.WeReadEinkResponseKind
import com.wamp.download.weread.eink.WeReadEinkTransport
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.Base64

data class WeReadDirectoryAccount(
    val fakeid: String,
    val bookId: String,
    val nickname: String,
    val alias: String = fakeid,
    val headImg: String = "",
    val intro: String = "",
)

fun interface WeReadSubscriptionResolver {
    fun resolve(keyword: String): WeReadDirectoryAccount?
}

class WeReadDirectoryClient(
    private val baseUrl: String,
    private val auth: WeReadDirectoryAuth,
    private val transport: WeReadEinkTransport = OkHttpWeReadEinkTransport(),
    private val requestGate: (String, () -> WeReadEinkResponse) -> WeReadEinkResponse = { vid, block ->
        WeReadEinkRequestGate.execute(vid, block)
    },
) {
    private val gson = Gson()

    fun resolve(keyword: String): WeReadDirectoryAccount? {
        val trimmed = keyword.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            return resolveByUrl(trimmed)
        }
        val results = searchAccounts(trimmed)
        val normalized = normalizeName(trimmed)
        return results.firstOrNull { normalizeName(it.nickname) == normalized }
    }

    fun searchAccounts(keyword: String, count: Int = 20): List<WeReadDirectoryAccount> {
        val scoped = runCatching {
            getJson(
                path = listOf("store", "search"),
                query = listOf(
                    "keyword" to keyword,
                    "scope" to "2",
                    "v" to "2",
                    "count" to count.toString(),
                ),
            )
        }.getOrNull()
        val scopedAccounts = parseBooks(scoped)
        if (scopedAccounts.isNotEmpty()) return scopedAccounts

        val general = runCatching {
            getJson(
                path = listOf("store", "search"),
                query = listOf("keyword" to keyword, "v" to "2", "count" to count.toString()),
            )
        }.getOrNull()
        return parseBooks(general)
    }

    fun resolveByUrl(url: String): WeReadDirectoryAccount? {
        val httpUrl = url.toHttpUrlOrNull()
        val biz = httpUrl?.queryParameter("__biz")?.takeIf { it.isNotBlank() }
        var bookId = biz?.let(::mpIdFromFakeid)
        if (bookId.isNullOrBlank()) {
            val body = """{"urls":[${gson.toJson(url)}]}"""
            val response = runCatching {
                postJson(listOf("mp", "getreviewid"), body)
            }.getOrNull()
            bookId = stringField(response, "bookId")
                ?: response?.getAsJsonArray("reviewIds")
                    ?.firstOrNull()
                    ?.takeIf { it.isJsonObject }
                    ?.asJsonObject
                    ?.let { stringField(it, "bookId") }
        }
        if (bookId.isNullOrBlank()) return null

        val info = runCatching {
            getJson(listOf("book", "info"), listOf("bookId" to bookId))
        }.getOrNull()
        val infoRoot = info?.let { objectField(it, "data") ?: it }
        val fakeid = fakeidFromBookId(bookId) ?: biz
        if (fakeid.isNullOrBlank()) return null
        return WeReadDirectoryAccount(
            fakeid = fakeid,
            bookId = bookId,
            nickname = stringField(infoRoot, "title").orEmpty().ifBlank { bookId },
            alias = fakeid,
            headImg = stringField(infoRoot, "cover").orEmpty(),
            intro = stringField(infoRoot, "intro").orEmpty(),
        )
    }

    fun listFollowedAccounts(count: Int = 50): List<WeReadDirectoryAccount> {
        val accounts = LinkedHashMap<String, WeReadDirectoryAccount>()
        fun addAll(items: List<WeReadDirectoryAccount>) {
            items.forEach { accounts.putIfAbsent(it.bookId, it) }
        }

        addAll(parseMpList(listType = 0, count = count))
        addAll(parseMpList(listType = 1, count = count))
        runCatching {
            getJson(listOf("shelf", "sync"), listOf("synckey" to "0"))
        }.getOrNull()?.let { addAll(parseBookArray(it, "books")) }
        runCatching {
            getJson(listOf("subscription", "books"), listOf("count" to count.toString()))
        }.getOrNull()?.let {
            addAll(parseBookArray(it, "books"))
            addAll(parseBookArray(it, "data"))
        }
        return accounts.values.toList()
    }

    fun activateTerminalSession(): Boolean {
        val response = runCatching {
            execute(
                WeReadEinkRequest(
                    method = "POST",
                    url = buildUrl(listOf("mobileSync"), emptyList()),
                    headers = auth.headers() + ("Content-Type" to "application/json; charset=utf-8"),
                    body = MOBILE_SYNC_BODY,
                ),
                allowFailure = true,
            )
        }.getOrNull() ?: return false
        if (WeReadEinkResponseClassifier.classify(response).kind != WeReadEinkResponseKind.SUCCESS) return false

        runCatching {
            getJson(listOf("shelf", "sync"), listOf("synckey" to "0"), allowFailure = true)
        }
        runCatching {
            getJson(
                listOf("mp", "list"),
                listOf("listType" to "0", "count" to "50", "synckey" to "0"),
                allowFailure = true,
            )
        }
        return true
    }

    private fun parseMpList(listType: Int, count: Int): List<WeReadDirectoryAccount> {
        val root = runCatching {
            getJson(
                listOf("mp", "list"),
                listOf("listType" to listType.toString(), "count" to count.toString(), "synckey" to "0"),
            )
        }.getOrNull() ?: return emptyList()
        val elements = root.getAsJsonArray("lists") ?: root.getAsJsonArray("items") ?: return emptyList()
        return elements.mapNotNull { element ->
            if (!element.isJsonObject) return@mapNotNull null
            val obj = element.asJsonObject
            val bookInfo = objectField(obj, "bookInfo") ?: obj
            val bookId = stringField(bookInfo, "bookId") ?: stringField(obj, "bookId") ?: return@mapNotNull null
            accountFromBook(bookId, bookInfo, obj)
        }
    }

    private fun parseBooks(root: JsonObject?): List<WeReadDirectoryAccount> =
        parseBookArray(root, "books")

    private fun parseBookArray(root: JsonObject?, field: String): List<WeReadDirectoryAccount> {
        val elements = root?.getAsJsonArray(field) ?: return emptyList()
        return elements.mapNotNull { element ->
            if (!element.isJsonObject) return@mapNotNull null
            val obj = element.asJsonObject
            val bookInfo = objectField(obj, "bookInfo") ?: obj
            val bookId = stringField(bookInfo, "bookId") ?: stringField(obj, "bookId") ?: return@mapNotNull null
            accountFromBook(bookId, bookInfo, obj)
        }
    }

    private fun accountFromBook(
        bookId: String,
        bookInfo: JsonObject,
        fallback: JsonObject,
    ): WeReadDirectoryAccount? {
        if (!bookId.startsWith("MP_") && !bookId.contains("MP_WXS")) return null
        val fakeid = fakeidFromBookId(bookId) ?: return null
        return WeReadDirectoryAccount(
            fakeid = fakeid,
            bookId = bookId,
            nickname = stringField(bookInfo, "title")
                ?: stringField(fallback, "title")
                ?: stringField(fallback, "account")
                .orEmpty(),
            alias = fakeid,
            headImg = stringField(bookInfo, "cover")
                ?: stringField(fallback, "mpavatar")
                ?: stringField(fallback, "thumbUrl")
                .orEmpty(),
            intro = stringField(bookInfo, "intro").orEmpty(),
        )
    }

    private fun getJson(
        path: List<String>,
        query: List<Pair<String, String>>,
        allowFailure: Boolean = false,
    ): JsonObject {
        val response = execute(
            WeReadEinkRequest(
                method = "GET",
                url = buildUrl(path, query),
                headers = auth.headers(),
            ),
            allowFailure = allowFailure,
        )
        return parseObject(response.body)
    }

    private fun postJson(
        path: List<String>,
        body: String,
        allowFailure: Boolean = false,
    ): JsonObject {
        val response = execute(
            WeReadEinkRequest(
                method = "POST",
                url = buildUrl(path, emptyList()),
                headers = auth.headers() + ("Content-Type" to "application/json; charset=utf-8"),
                body = body,
            ),
            allowFailure = allowFailure,
        )
        return parseObject(response.body)
    }

    private fun execute(request: WeReadEinkRequest, allowFailure: Boolean = false): WeReadEinkResponse {
        val response = requestGate(auth.vid()) { transport.execute(request) }
        val classification = WeReadEinkResponseClassifier.classify(response)
        if (!allowFailure && classification.kind != WeReadEinkResponseKind.SUCCESS) {
            throw WeReadEinkApiException(
                kind = classification.kind,
                statusCode = response.statusCode,
                errCode = classification.errCode,
                errMsg = classification.errMsg,
            )
        }
        return response
    }

    private fun parseObject(raw: String): JsonObject {
        val root = runCatching { JsonParser.parseString(raw) }.getOrNull()
        if (root == null || !root.isJsonObject) {
            throw WeReadEinkParseException("WeRead directory returned a non-object body")
        }
        return root.asJsonObject
    }

    private fun buildUrl(path: List<String>, query: List<Pair<String, String>>): HttpUrl {
        val builder = baseUrl.toHttpUrl().newBuilder()
        path.forEach { builder.addPathSegment(it) }
        query.forEach { builder.addQueryParameter(it.first, it.second) }
        return builder.build()
    }

    private fun normalizeName(value: String): String =
        value.replace(Regex("\\s+"), "").trim()

    private fun stringField(obj: JsonObject?, name: String): String? = obj?.get(name)
        ?.takeIf { it.isJsonPrimitive }
        ?.let { runCatching { it.asString }.getOrNull() }
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

    private fun objectField(obj: JsonObject?, name: String): JsonObject? = obj?.get(name)
        ?.takeIf { it.isJsonObject }
        ?.asJsonObject

    companion object {
        private const val MOBILE_SYNC_BODY =
            """{"shelf":0,"bookInventory":0,"shelfLecture":0,"notifications":0,"config":0,"gift":0,"payItem":0,"balance":0.0,"reviewTimeline":0,"follower":0,"readingExchange":0,"chat":0,"chatRemovedSynckey":0,"wechatFriend":0,"inBackground":0,"refluxSynckey":0,"following":0,"applyList":0,"mcard":0,"discoverFeedSynckey":0,"rateSynckey":0,"browse":0,"medal":0,"medalUrl":0,"regionResource":0,"discoverColumnSynckey":0,"friendReviewSynckey":0,"wehearSyncKey":0,"preferTab":1,"localBrowseTab":"","localCommunityTab":""}"""

        fun mpIdFromFakeid(fakeid: String): String? {
            val trimmed = fakeid.trim()
            if (trimmed.isEmpty()) return null
            val decoded = runCatching {
                String(Base64.getDecoder().decode(padBase64(trimmed)), Charsets.UTF_8).trim()
            }.getOrNull() ?: return null
            if (decoded.isEmpty() || decoded.any { !it.isDigit() }) return null
            return "MP_WXS_$decoded"
        }

        fun fakeidFromBookId(bookId: String): String? {
            val trimmed = bookId.trim()
            if (!trimmed.startsWith("MP_WXS_")) return null
            val uin = trimmed.removePrefix("MP_WXS_").trim()
            if (uin.isEmpty() || uin.any { !it.isDigit() }) return null
            return Base64.getEncoder().encodeToString(uin.toByteArray(Charsets.UTF_8))
        }

        private fun padBase64(value: String): String {
            val pad = (4 - value.length % 4) % 4
            return value + "=".repeat(pad)
        }
    }
}
