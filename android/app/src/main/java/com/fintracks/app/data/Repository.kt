package com.fintracks.app.data

import android.content.Context
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.serializer

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

    private suspend inline fun <reified T> select(path: String): List<T> =
        json.decodeFromString(ListSerializer(serializer<T>()), authed("GET", path))

    // ---------- Reads ----------

    suspend fun fetchProfile(): ProfileRow? =
        select<ProfileRow>("/rest/v1/profiles?select=*&id=eq.${uid()}").firstOrNull()

    suspend fun fetchTransactions(): List<TxRow> =
        select("/rest/v1/transactions?select=*&order=tx_date.desc,created_at.desc")

    suspend fun fetchShops(): List<ShopRow> =
        select("/rest/v1/credit_shops?select=*&order=created_at.desc")

    suspend fun fetchEntries(): List<EntryRow> =
        select("/rest/v1/credit_entries?select=*&order=purchase_date.desc")

    suspend fun fetchPayments(): List<PaymentRow> =
        select("/rest/v1/credit_payments?select=*&order=paid_on.desc")

    // ---------- Writes ----------

    suspend fun insertTransaction(
        type: String, amount: Double, category: String, note: String, date: String, mode: String
    ) {
        val body = buildJsonObject {
            put("user_id", uid())
            put("type", type)
            put("amount", amount)
            put("category", category)
            put("note", note)
            put("tx_date", date)
            put("payment_mode", mode)
        }
        authed("POST", "/rest/v1/transactions", body, "return=minimal")
    }

    suspend fun insertShop(name: String, phone: String, note: String) {
        val body = buildJsonObject {
            put("user_id", uid())
            put("name", name)
            put("phone", phone)
            put("note", note)
        }
        authed("POST", "/rest/v1/credit_shops", body, "return=minimal")
    }

    suspend fun insertEntry(
        shopId: String, item: String, quantity: Double, unitPrice: Double,
        purchaseDate: String, dueDate: String?, note: String
    ) {
        val body = buildJsonObject {
            put("user_id", uid())
            put("shop_id", shopId)
            put("item_name", item)
            put("quantity", quantity)
            put("unit_price", unitPrice)
            put("purchase_date", purchaseDate)
            put("due_date", dueDate)
            put("note", note)
        }
        authed("POST", "/rest/v1/credit_entries", body, "return=minimal")
    }

    suspend fun insertPayment(shopId: String, amount: Double, paidOn: String, note: String) {
        val body = buildJsonObject {
            put("user_id", uid())
            put("shop_id", shopId)
            put("amount", amount)
            put("paid_on", paidOn)
            put("note", note)
        }
        authed("POST", "/rest/v1/credit_payments", body, "return=minimal")
    }

    suspend fun updateProfile(name: String?, theme: String?, themeColor: String?, privacy: Boolean?) {
        val body = buildJsonObject {
            name?.let { put("name", it) }
            theme?.let { put("theme", it) }
            themeColor?.let { put("theme_color", it) }
            privacy?.let { put("is_privacy_mode", it) }
        }
        authed("PATCH", "/rest/v1/profiles?id=eq.${uid()}", body, "return=minimal")
    }

    suspend fun delete(table: String, id: String) {
        authed("DELETE", "/rest/v1/$table?id=eq.$id")
    }

    companion object {
        private const val KEY_SESSION = "session"
    }
}
