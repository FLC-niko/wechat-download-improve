package com.wamp.download.weread.eink

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.HttpUrl.Companion.toHttpUrl
import kotlin.random.Random

/** Refreshes an E-Ink access token using the mobile API /login endpoint. */
class WeReadEinkLoginClient(
    private val transport: WeReadEinkTransport = OkHttpWeReadEinkTransport(),
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val profile: WeReadEinkProfile = DefaultWeReadEinkProfile,
    private val timestampProvider: () -> Long = { System.currentTimeMillis() },
    private val randomProvider: () -> Int = { Random.nextInt(1, 1001) },
    private val gson: Gson = Gson(),
) {
    fun refresh(credentials: WeReadEinkCredentials): WeReadEinkCredentials {
        val timestamp = timestampProvider()
        val random = randomProvider()
        val body = linkedMapOf<String, Any?>().apply {
            put("deviceId", credentials.deviceId)
            put("deviceName", profile.deviceName)
            put("inBackground", 0)
            put("kickType", 1)
            put("random", random)
            put("refCgi", "")
            put("refreshToken", credentials.refreshToken)
            put("signature", profile.refreshSignature(credentials.deviceId, timestamp, random, credentials.refreshToken))
            put("timestamp", timestamp)
            put("trackId", "")
            putAll(profile.loginBodyExtras())
            put("deviceType", profile.deviceType)
        }
        val request = WeReadEinkRequest(
            method = "POST",
            url = baseUrl.toHttpUrl().newBuilder().addPathSegment("login").build(),
            headers = profile.versionHeaders + mapOf("Content-Type" to "application/json; charset=utf-8"),
            body = gson.toJson(body),
        )
        val response = transport.execute(request)
        val classification = WeReadEinkResponseClassifier.classify(response)
        if (classification.kind != WeReadEinkResponseKind.SUCCESS) {
            throw WeReadEinkApiException(
                kind = classification.kind,
                statusCode = response.statusCode,
                errCode = classification.errCode,
                errMsg = classification.errMsg,
            )
        }

        return parseCredentials(response.body, credentials, timestamp)
    }

    private fun parseCredentials(raw: String, current: WeReadEinkCredentials, issuedAt: Long): WeReadEinkCredentials {
        try {
            val root = JsonParser.parseString(raw)
            if (!root.isJsonObject) throw WeReadEinkParseException("/login returned a non-object body")
            val obj = root.asJsonObject
            val returnedVid = optionalString(obj, "vid")
            if (returnedVid != null && returnedVid != current.vid) {
                throw WeReadEinkParseException("/login response vid does not match the current account")
            }
            val accessToken = requiredString(obj, "accessToken")
            val refreshToken = optionalString(obj, "refreshToken") ?: current.refreshToken
            return WeReadEinkCredentials(
                vid = current.vid,
                accessToken = accessToken,
                refreshToken = refreshToken,
                deviceId = current.deviceId,
                accessTokenIssuedAt = issuedAt,
                accessTokenExpiresAt = WeReadEinkTokenExpiry.parse(obj, issuedAt),
            )
        } catch (error: WeReadEinkParseException) {
            throw error
        } catch (error: Exception) {
            throw WeReadEinkParseException("failed to parse /login response", error)
        }
    }

    private fun requiredString(obj: JsonObject, name: String): String {
        return optionalString(obj, name)
            ?: throw WeReadEinkParseException("/login response is missing $name")
    }

    private fun optionalString(obj: JsonObject, name: String): String? {
        val value = obj.get(name)
            ?.takeIf { it.isJsonPrimitive }
            ?.let { runCatching { it.asString }.getOrNull() }
            ?.takeIf { it.isNotBlank() }
        return value
    }

    private companion object {
        const val DEFAULT_BASE_URL = "https://i.weread.qq.com"
    }
}
