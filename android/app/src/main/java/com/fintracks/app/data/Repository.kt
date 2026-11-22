package com.fintracks.app.data

import android.content.Context
import com.fintracks.app.BuildConfig
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.JsonElement

/**
 * Single entry point for all data. Every request carries the user's access token,
 * so Row Level Security on Supabase enforces ownership.
 */
class Repository(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("fintracks", Context.MODE_PRIVATE)
    private val api = SupabaseApi()
    private val json = api.codec

    var session: Session?
        get() = prefs.getString(KEY_SESSION, null)
            ?.let { runCatching { json.decodeFromString(Session.serializer(), it) }.getOrNull() }
        set(value) {
            prefs.edit().apply {
                if (value == null) remove(KEY_SESSION)
                else putString(KEY_SESSION, json.encodeToString(Session.serializer(), value))
            }.apply()
        }

    fun uid(): String = session?.user?.id ?: throw SupabaseException(401, "লগইন করা নেই")

    // ---------- Auth ----------

    /** Returns true when a session was created; false when email confirmation is pending. */
    suspend fun signUp(email: String, password: String, name: String): Boolean {
        val s = api.signUp(email, password, name) ?: return false
        session = s
        return true
    }

    suspend fun signIn(email: String, password: String) {
        session = api.signIn(email, password)
    }

    suspend fun signInWithGoogle(idToken: String) {
        session = api.signInWithGoogle(idToken)
    }

    /** Permanently deletes the signed-in account and all of its data (server-side). */
    suspend fun deleteAccount() {
        authed("POST", "/functions/v1/delete-account", null)
        session = null
    }

    suspend fun signOut() {
        session?.let { runCatching { api.signOut(it.accessToken) } }
        session = null
    }

    // ---------- Request helper with one refresh-and-retry on 401 ----------

    private suspend fun authed(
        method: String,
        path: String,
        body: JsonElement? = null,
        prefer: String? = null,
        readTimeoutSec: Long? = null
    ): String {
        val current = session ?: throw SupabaseException(401, "লগইন করা নেই")
        return try {
            api.call(method, path, body, current.accessToken, prefer, readTimeoutSec)
        } catch (e: SupabaseException) {
            if (e.code != 401) throw e
            val fresh = api.refresh(current.refreshToken)
            session = fresh
            api.call(method, path, body, fresh.accessToken, prefer, readTimeoutSec)
        }
    }

    // ---------- Generic table access (RLS filters rows by user) ----------

    suspend fun list(table: String, query: String = "select=*"): List<JsonObject> =
        json.decodeFromString(ListSerializer(JsonObject.serializer()), authed("GET", "/rest/v1/$table?$query"))

    suspend fun insert(table: String, body: JsonObject) {
        val withUser = JsonObject(body + ("user_id" to JsonPrimitive(uid())))
        authed("POST", "/rest/v1/$table", withUser, "return=minimal")
    }

    suspend fun update(table: String, id: String, body: JsonObject) {
        authed("PATCH", "/rest/v1/$table?id=eq.$id", body, "return=minimal")
    }

    suspend fun delete(table: String, id: String) {
        authed("DELETE", "/rest/v1/$table?id=eq.$id")
    }

    /**
     * Restore: inserts rows, or updates rows with the same id. Every row is stamped with the
     * current user's id, so a backup from another account can never write outside this account.
     */
    suspend fun upsert(table: String, rows: List<JsonObject>, onConflict: String = "id") {
        val uid = uid()
        rows.chunked(400).forEach { chunk ->
            val body = JsonArray(chunk.map { JsonObject(it + ("user_id" to JsonPrimitive(uid))) })
            authed("POST", "/rest/v1/$table?on_conflict=$onConflict", body, "resolution=merge-duplicates,return=minimal")
        }
    }

    /**
     * The login email. It lives in Supabase Auth, not in profiles, so it is read from the session;
     * when the session has none, it is fetched from the Auth user endpoint.
     */
    suspend fun accountEmail(): String {
        session?.user?.email?.takeIf { it.isNotBlank() }?.let { return it }
        val text = authed("GET", "/auth/v1/user", null)
        return json.parseToJsonElement(text).jsonObject["email"]?.jsonPrimitive?.content.orEmpty()
    }

    /** Uploads a profile photo to the avatars bucket (own folder only) and returns its public URL. */
    suspend fun uploadAvatar(bytes: ByteArray): String {
        val uid = uid()
        val path = "/storage/v1/object/avatars/$uid/avatar.jpg"
        val current = session ?: throw SupabaseException(401, "লগইন করা নেই")
        try {
            api.upload(path, bytes, "image/jpeg", current.accessToken)
        } catch (e: SupabaseException) {
            if (e.code != 401) throw e
            val fresh = api.refresh(current.refreshToken)
            session = fresh
            api.upload(path, bytes, "image/jpeg", fresh.accessToken)
        }
        return BuildConfig.SUPABASE_URL.trimEnd('/') + "/storage/v1/object/public/avatars/$uid/avatar.jpg?v=${System.currentTimeMillis()}"
    }

    suspend fun updateProfile(fields: JsonObject) {
        authed("PATCH", "/rest/v1/profiles?id=eq.${uid()}", fields, "return=minimal")
    }

    /** Calls the server-side AI function. The Gemini key never leaves Supabase. */
    suspend fun askAi(question: String, context: String, language: String): String {
        val body = buildJsonObject {
            put("question", question)
            put("context", context)
            // "en" makes the server answer in English; anything else gets the Bengali answer.
            put("language", language)
        }
        // The server may try two models in turn, so this call waits longer than ordinary requests.
        val text = try {
            authed("POST", "/functions/v1/ai-chat", body, readTimeoutSec = 90)
        } catch (e: SupabaseException) {
            throw SupabaseException(e.code, aiReason(e), e.body)
        }
        return json.parseToJsonElement(text).jsonObject["answer"]?.jsonPrimitive?.content
            ?: throw SupabaseException(500, "AI উত্তর পাওয়া যায়নি")
    }

    /** A short, factual reason from the AI function's error body: HTTP code, server error, and each model tried. */
    private fun aiReason(e: SupabaseException): String {
        val obj = runCatching { json.parseToJsonElement(e.body).jsonObject }.getOrNull()
        val serverError = obj?.get("error")?.jsonPrimitive?.contentOrNull
        val tried = (obj?.get("attempts") as? JsonArray).orEmpty().mapNotNull { a ->
            val o = a as? JsonObject ?: return@mapNotNull null
            "${o["model"]?.jsonPrimitive?.contentOrNull} HTTP ${o["status"]?.jsonPrimitive?.contentOrNull}"
        }
        return buildString {
            append("HTTP ${e.code}")
            if (!serverError.isNullOrBlank()) append(": $serverError")
            if (tried.isNotEmpty()) append(" (${tried.joinToString("; ")})")
            if (serverError.isNullOrBlank() && tried.isEmpty()) append(": ${e.message}")
        }
    }

    companion object {
        private const val KEY_SESSION = "session"
    }
}
