package com.fintracks.app.data

import com.fintracks.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/** [body] is the server response text, kept so screens can show the real reason (for example the AI service attempts). */
/** Where the password-reset email link returns: the reset form on the Pages site (must be in Auth redirect allow-list). */
const val PASSWORD_RESET_URL = "https://shohagvaii.github.io/FinTracks/reset.html"

class SupabaseException(val code: Int, message: String, val body: String = "") : IOException(message)

@Serializable
data class AuthUser(val id: String, val email: String? = null)

@Serializable
data class Session(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String,
    val user: AuthUser
)

/** Thin client over Supabase Auth (GoTrue) and PostgREST, using only OkHttp + kotlinx.serialization. */
class SupabaseApi {
    val codec: Json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    /** Raw-bytes upload to Supabase Storage (used for profile photos). Upserts, so a new photo replaces the old one. */
    suspend fun upload(path: String, bytes: ByteArray, mime: String, token: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(BuildConfig.SUPABASE_URL.trimEnd('/') + path)
            .header("apikey", BuildConfig.SUPABASE_KEY)
            .header("Authorization", "Bearer $token")
            .header("x-upsert", "true")
            .post(bytes.toRequestBody(mime.toMediaType()))
            .build()
        http.newCall(request).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw SupabaseException(resp.code, parseError(text, resp.code))
            text
        }
    }

    /** Downloads one private storage object (attachment photo). */
    suspend fun download(path: String, token: String): ByteArray = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(BuildConfig.SUPABASE_URL.trimEnd('/') + path)
            .header("apikey", BuildConfig.SUPABASE_KEY)
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        http.newCall(request).execute().use { resp ->
            val bytes = resp.body?.bytes() ?: ByteArray(0)
            if (!resp.isSuccessful) throw SupabaseException(resp.code, parseError(bytes.decodeToString(), resp.code))
            bytes
        }
    }

    /** Deletes one storage object. A missing object (404) counts as deleted. */
    suspend fun deleteObject(path: String, token: String) = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(BuildConfig.SUPABASE_URL.trimEnd('/') + path)
            .header("apikey", BuildConfig.SUPABASE_KEY)
            .header("Authorization", "Bearer $token")
            .delete()
            .build()
        http.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful && resp.code != 404) {
                throw SupabaseException(resp.code, parseError(resp.body?.string().orEmpty(), resp.code))
            }
        }
    }

    suspend fun call(
        method: String,
        path: String,
        body: JsonElement? = null,
        token: String? = null,
        prefer: String? = null,
        readTimeoutSec: Long? = null
    ): String = withContext(Dispatchers.IO) {
        val reqBody: RequestBody? = when {
            body != null -> body.toString().toRequestBody(jsonMedia)
            method == "POST" || method == "PATCH" -> "".toRequestBody(jsonMedia)
            else -> null
        }
        val builder = Request.Builder()
            .url(BuildConfig.SUPABASE_URL.trimEnd('/') + path)
            .header("apikey", BuildConfig.SUPABASE_KEY)
            .header("Authorization", "Bearer ${token ?: BuildConfig.SUPABASE_KEY}")
            .method(method, reqBody)
        if (prefer != null) builder.header("Prefer", prefer)

        val client = if (readTimeoutSec != null) http.newBuilder().readTimeout(readTimeoutSec, TimeUnit.SECONDS).build() else http
        client.newCall(builder.build()).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw SupabaseException(resp.code, parseError(text, resp.code), text)
            text
        }
    }

    suspend fun signUp(email: String, password: String, name: String): Session? {
        val body = buildJsonObject {
            put("email", email)
            put("password", password)
            put("data", buildJsonObject { put("name", name) })
        }
        val text = call("POST", "/auth/v1/signup", body)
        // When email confirmation is required, Supabase returns no session.
        val obj = codec.parseToJsonElement(text).jsonObject
        return if ("access_token" in obj) codec.decodeFromString(Session.serializer(), text) else null
    }

    /**
     * Asks the server to email a password-reset link. The Edge Function checks the last send time on the server
     * and allows one mail per email every 5 hours; a blocked request comes back as HTTP 429 with retry_after_seconds.
     */
    suspend fun recover(email: String) {
        call("POST", "/functions/v1/request-password-reset", buildJsonObject { put("email", email) })
    }

    suspend fun signIn(email: String, password: String): Session {
        val body = buildJsonObject { put("email", email); put("password", password) }
        val text = call("POST", "/auth/v1/token?grant_type=password", body)
        return codec.decodeFromString(Session.serializer(), text)
    }

    suspend fun signInWithGoogle(idToken: String): Session {
        val body = buildJsonObject { put("provider", "google"); put("id_token", idToken) }
        val text = call("POST", "/auth/v1/token?grant_type=id_token", body)
        return codec.decodeFromString(Session.serializer(), text)
    }

    suspend fun refresh(refreshToken: String): Session {
        val body = buildJsonObject { put("refresh_token", refreshToken) }
        val text = call("POST", "/auth/v1/token?grant_type=refresh_token", body)
        return codec.decodeFromString(Session.serializer(), text)
    }

    suspend fun signOut(accessToken: String) {
        call("POST", "/auth/v1/logout", buildJsonObject { }, token = accessToken)
    }

    private fun parseError(text: String, code: Int): String = try {
        val obj = codec.parseToJsonElement(text).jsonObject
        listOf("msg", "error_description", "message", "error")
            .firstNotNullOfOrNull { obj[it]?.jsonPrimitive?.contentOrNull }
            ?: "সার্ভার ত্রুটি ($code)"
    } catch (e: Exception) {
        "সার্ভার ত্রুটি ($code)"
    }
}
