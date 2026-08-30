package com.wamp.download.weread.eink

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.security.MessageDigest
import kotlin.random.Random

data class WeReadEinkQrRequest(
    val uuid: String,
    val confirmUrl: String,
)

enum class WeReadEinkQrStatus {
    WAITING,
    SCANNED,
    CONFIRMED,
}

class WeReadEinkQrLoginException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * E-Ink QR login copied from the official mobile flow used by weread-omni.
 * It exchanges one confirmed WeChat QR code for vid/accessToken/refreshToken/deviceId.
 */
class WeReadEinkQrLoginClient(
    private val transport: WeReadEinkTransport = OkHttpWeReadEinkTransport(),
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val profile: WeReadEinkProfile = DefaultWeReadEinkProfile,
    private val timestampProvider: () -> Long = { System.currentTimeMillis() },
    private val randomProvider: () -> Int = { Random.nextInt(0, 1000) },
    private val waitProvider: (Long) -> Unit = { Thread.sleep(it) },
    private val gson: Gson = Gson(),
) {
    fun requestQr(): WeReadEinkQrRequest {
        val ticketResponse = transport.execute(
            WeReadEinkRequest(
                method = "GET",
                url = "$baseUrl/wxticket".toHttpUrl().newBuilder()
                    .addQueryParameter("nonceStr", NONCE)
                    .build(),
                headers = profile.versionHeaders,
            ),
        )
        val ticket = requireObject(ticketResponse, "WeRead QR ticket")
        val signature = requiredString(ticket, "signature", "WeRead QR ticket")
        val timestamp = ticket.get("timeStamp")
            ?.takeIf { it.isJsonPrimitive }
            ?.asString
            ?.takeIf { it.isNotBlank() }
            ?: throw WeReadEinkQrLoginException("WeRead QR ticket is missing timeStamp")

        val qrResponse = transport.execute(
            WeReadEinkRequest(
                method = "GET",
                url = QR_CONNECT_URL.toHttpUrl().newBuilder()
                    .addQueryParameter("appid", WX_APP_ID)
                    .addQueryParameter("noncestr", NONCE)
                    .addQueryParameter("timestamp", timestamp)
                    .addQueryParameter("scope", SCOPE)
                    .addQueryParameter("signature", signature)
                    .build(),
                headers = mapOf("User-Agent" to profile.versionHeaders["User-Agent"].orEmpty()),
            ),
        )
        val qr = requireObject(qrResponse, "WeChat qrconnect")
        val errCode = intField(qr, "errcode")
        if (errCode != 0) {
            throw WeReadEinkQrLoginException("WeChat qrconnect failed: ${errCode ?: qrResponse.statusCode}")
        }
        val uuid = requiredString(qr, "uuid", "WeChat qrconnect")
        val confirmUrl = CONFIRM_URL.toHttpUrl().newBuilder().addQueryParameter("uuid", uuid).build().toString()
        return WeReadEinkQrRequest(uuid = uuid, confirmUrl = confirmUrl)
    }

    fun pollForCode(
        uuid: String,
        deadlineMs: Long = DEFAULT_LOGIN_DEADLINE_MS,
        pollDelayMs: Long = DEFAULT_POLL_DELAY_MS,
        onStatus: (WeReadEinkQrStatus) -> Unit = {},
    ): String {
        require(uuid.isNotBlank()) { "uuid must not be blank" }
        val deadline = timestampProvider() + deadlineMs
        var last: Int? = null
        while (timestampProvider() < deadline) {
            val url = QR_POLL_URL.toHttpUrl().newBuilder()
                .addQueryParameter("f", "json")
                .addQueryParameter("uuid", uuid)
                .apply { last?.let { addQueryParameter("last", it.toString()) } }
                .build()
            val response = transport.execute(
                WeReadEinkRequest(
                    method = "GET",
                    url = url,
                    headers = mapOf("User-Agent" to "Mozilla/5.0"),
                ),
            )
            val body = requireObject(response, "WeChat QR poll")
            val code = intField(body, "wx_errcode")
                ?: throw WeReadEinkQrLoginException("WeChat QR poll omitted wx_errcode")
            when (code) {
                405 -> {
                    val wxCode = requiredString(body, "wx_code", "WeChat QR confirmation")
                    onStatus(WeReadEinkQrStatus.CONFIRMED)
                    return wxCode
                }
                404 -> onStatus(WeReadEinkQrStatus.SCANNED)
                408 -> onStatus(WeReadEinkQrStatus.WAITING)
                402 -> throw WeReadEinkQrLoginException("E-Ink QR expired; start login again")
                403 -> throw WeReadEinkQrLoginException("E-Ink QR login was declined")
                else -> throw WeReadEinkQrLoginException("WeChat QR poll returned unknown status: $code")
            }
            last = code
            val remaining = deadline - timestampProvider()
            if (remaining > 0L) waitProvider(pollDelayMs.coerceAtMost(remaining))
        }
        throw WeReadEinkQrLoginException("E-Ink QR login timed out; start login again")
    }

    fun exchange(wxCode: String, deviceId: String = profile.newDeviceId()): WeReadEinkCredentials {
        require(wxCode.isNotBlank()) { "wxCode must not be blank" }
        require(deviceId.isNotBlank()) { "deviceId must not be blank" }
        val timestamp = timestampProvider()
        val random = randomProvider()
        val body = linkedMapOf<String, Any?>().apply {
            put("appFirstInstall", 1)
            put("code", wxCode)
            put("deviceId", deviceId)
            put("deviceName", profile.deviceName)
            put("installId", profile.newInstallId())
            put("isAutoLogout", 0)
            put("isFromQrcode", 1)
            put("random", random)
            put("signature", sha256("$timestamp$deviceId$random"))
            put("timestamp", timestamp)
            put("trackId", "")
            putAll(profile.loginBodyExtras())
            put("deviceType", profile.deviceType)
        }
        val response = transport.execute(
            WeReadEinkRequest(
                method = "POST",
                url = baseUrl.toHttpUrl().newBuilder().addPathSegment("login").build(),
                headers = profile.versionHeaders + mapOf("Content-Type" to "application/json; charset=utf-8"),
                body = gson.toJson(body),
            ),
        )
        val result = requireObject(response, "WeRead E-Ink QR login")
        val accessToken = requiredString(result, "accessToken", "WeRead E-Ink QR login")
        val refreshToken = requiredString(result, "refreshToken", "WeRead E-Ink QR login")
        val vid = scalarString(result, "vid")
            ?: throw WeReadEinkQrLoginException("WeRead E-Ink QR login is missing vid")
        return WeReadEinkCredentials(
            vid = vid,
            accessToken = accessToken,
            refreshToken = refreshToken,
            deviceId = deviceId,
            accessTokenIssuedAt = timestamp,
            accessTokenExpiresAt = WeReadEinkTokenExpiry.parse(result, timestamp),
        )
    }

    fun login(
        onQr: (WeReadEinkQrRequest) -> Unit,
        onStatus: (WeReadEinkQrStatus) -> Unit = {},
    ): WeReadEinkCredentials {
        val deviceId = profile.newDeviceId()
        val qr = requestQr()
        onQr(qr)
        val code = pollForCode(qr.uuid, onStatus = onStatus)
        return exchange(code, deviceId)
    }

    private fun requireObject(response: WeReadEinkResponse, label: String): JsonObject {
        if (response.statusCode !in 200..299) {
            throw WeReadEinkQrLoginException("$label failed: HTTP ${response.statusCode}")
        }
        return try {
            val root = JsonParser.parseString(response.body)
            if (!root.isJsonObject) throw WeReadEinkQrLoginException("$label returned a non-object body")
            root.asJsonObject
        } catch (error: WeReadEinkQrLoginException) {
            throw error
        } catch (error: Exception) {
            throw WeReadEinkQrLoginException("$label returned invalid JSON", error)
        }
    }

    private fun requiredString(obj: JsonObject, name: String, label: String): String =
        scalarString(obj, name)?.takeIf { it.isNotBlank() }
            ?: throw WeReadEinkQrLoginException("$label is missing $name")

    private fun scalarString(obj: JsonObject, name: String): String? = obj.get(name)
        ?.takeIf { it.isJsonPrimitive }
        ?.let { runCatching { it.asString }.getOrNull() }

    private fun intField(obj: JsonObject, name: String): Int? = obj.get(name)
        ?.takeIf { it.isJsonPrimitive }
        ?.let { runCatching { it.asInt }.getOrNull() }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private companion object {
        const val DEFAULT_BASE_URL = "https://i.weread.qq.com"
        const val WX_APP_ID = "wxab9b71ad2b90ff34"
        const val NONCE = "weread"
        const val SCOPE = "snsapi_userinfo,snsapi_timeline,snsapi_friend"
        const val QR_CONNECT_URL = "https://open.weixin.qq.com/connect/sdk/qrconnect"
        const val QR_POLL_URL = "https://long.open.weixin.qq.com/connect/l/qrconnect"
        const val CONFIRM_URL = "https://open.weixin.qq.com/connect/confirm"
        const val DEFAULT_LOGIN_DEADLINE_MS = 5 * 60_000L
        const val DEFAULT_POLL_DELAY_MS = 1_000L
    }
}
