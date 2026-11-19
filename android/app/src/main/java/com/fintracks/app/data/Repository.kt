package com.fintracks.app.data

import android.content.Context
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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

    suspend fun signOut() {
        session?.let { runCatching { api.signOut(it.accessToken) } }
        session = null
    }

    // ---------- Request helper with one refresh-and-retry on 401 ----------

    private suspend fun authed(method: String, path: String, body: JsonElement? = null, prefer: String? = null): String {
        val current = session ?: throw SupabaseException(401, "লগইন করা নেই")
        return try {
            api.call(method, path, body, current.accessToken, prefer)
        } catch (e: SupabaseException) {
            if (e.code != 401) throw e
            val fresh = api.refresh(current.refreshToken)
            session = fresh
            api.call(method, path, body, fresh.accessToken, prefer)
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

    suspend fun updateProfile(fields: JsonObject) {
        authed("PATCH", "/rest/v1/profiles?id=eq.${uid()}", fields, "return=minimal")
    }

    companion object {
        private const val KEY_SESSION = "session"
    }
}
