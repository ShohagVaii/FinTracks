package com.fintracks.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fintracks.app.data.PendingSmsStore
import com.fintracks.app.data.Repository
import com.fintracks.app.data.SmsSuggestion
import com.fintracks.app.data.SupabaseException
import com.fintracks.app.data.row
import com.fintracks.app.data.str
import com.fintracks.app.data.num
import com.fintracks.app.data.bool
import com.fintracks.app.data.strOrNull
import com.fintracks.app.data.strList
import com.fintracks.app.data.id
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import java.io.IOException
import java.time.YearMonth
import android.content.Context

/** Every table the app reads, with its PostgREST query. Rows stay as JSON for generic CRUD. */
val TABLES: List<Pair<String, String>> = listOf(
    "transactions" to "select=*&order=tx_date.desc,created_at.desc",
    "accounts" to "select=*&order=created_at.asc",
    "budgets" to "select=*",
    "goals" to "select=*",
    "debts" to "select=*&order=settled.asc",
    "loans" to "select=*",
    "subscriptions" to "select=*",
    "investments" to "select=*",
    "credit_shops" to "select=*&order=created_at.asc",
    "credit_entries" to "select=*&order=purchase_date.desc",
    "credit_payments" to "select=*&order=paid_on.desc",
    "bill_splits" to "select=*&order=split_date.desc",
    "shopping_items" to "select=*",
    "mess_members" to "select=*",
    "mess_bazaars" to "select=*&order=bazaar_date.desc",
    "mess_meals" to "select=*&order=meal_date.desc"
)

data class UiState(
    val checking: Boolean = true,
    val signedIn: Boolean = false,
    val busy: Boolean = false,
    val message: String? = null,
    val notice: String? = null,
    val profile: JsonObject? = null,
    val tables: Map<String, List<JsonObject>> = emptyMap(),
    val pendingSms: List<SmsSuggestion> = emptyList()
) {
    fun rows(table: String): List<JsonObject> = tables[table].orEmpty()
    val dark: Boolean get() = profile?.str("theme") != "light"
    val accent: AccentColor get() = AccentColor.fromKey(profile?.str("theme_color"))
    val privacy: Boolean get() = profile?.bool("is_privacy_mode") == true
    val currency: String get() = profile?.str("currency").takeUnless { it.isNullOrEmpty() } ?: "BDT"
    val name: String get() = profile?.str("name").orEmpty()
}

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = Repository(app)
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            if (repo.session != null) runGuarded { loadAll() }
            _state.update { it.copy(checking = false) }
        }
    }

    private suspend fun fetchTables(): Map<String, List<JsonObject>> = coroutineScope {
        TABLES.map { (t, q) -> async { t to repo.list(t, q) } }.awaitAll().toMap()
    }

    private suspend fun loadAll() {
        val profile = repo.list("profiles", "select=*&id=eq.${repo.uid()}").firstOrNull()
        var tables = fetchTables()
        if (materializeRecurring(tables["transactions"].orEmpty())) tables = fetchTables()
        val app = getApplication<Application>()
        Reminders.check(app, tables)
        FinTrackWidgetProvider.refresh(app, tables, profile?.bool("is_privacy_mode") == true)
        _state.update {
            it.copy(
                signedIn = true,
                profile = profile,
                tables = tables,
                pendingSms = PendingSmsStore.load(getApplication())
            )
        }
    }

    /**
     * Monthly auto-recurring: each transaction marked is_recurring is copied once per month
     * into the current month (same day, clamped to month length). Copies are not recurring themselves.
     */
    private suspend fun materializeRecurring(txs: List<JsonObject>): Boolean {
        val prefs = getApplication<Application>().getSharedPreferences("fintrack_recurring", Context.MODE_PRIVATE)
        val month = monthPrefix()
        val key = "done-$month"
        val done = (prefs.getStringSet(key, null) ?: emptySet()).toMutableSet()
        var created = false
        for (t in txs.filter { it.bool("is_recurring") }) {
            val day = dateOf(t, "tx_date")
            if (day.length < 10 || day.take(7) >= month) continue
            if (!done.add(t.id())) continue
            val dd = minOf(day.substring(8).toInt(), YearMonth.parse(month).lengthOfMonth())
            repo.insert("transactions", row(
                "type" to t.str("type"),
                "amount" to t.num("amount"),
                "currency" to t.str("currency").ifEmpty { "BDT" },
                "category" to t.str("category"),
                "note" to t.str("note"),
                "tx_date" to "$month-${dd.toString().padStart(2, '0')}",
                "payment_mode" to t.str("payment_mode"),
                "mood" to t.str("mood"),
                "account_id" to t.strOrNull("account_id"),
                "to_account_id" to t.strOrNull("to_account_id"),
                "is_recurring" to false
            ))
            created = true
        }
        prefs.edit().putStringSet(key, done).apply()
        return created
    }

    /** Shows busy state, converts errors into readable messages, and signs out on expired sessions. */
    private suspend fun runGuarded(block: suspend () -> Unit) {
        _state.update { it.copy(busy = true, message = null) }
        try {
            block()
        } catch (e: SupabaseException) {
            if (e.code == 401) {
                repo.session = null
                _state.value = UiState(checking = false)
            } else {
                val text = e.message.orEmpty()
                val friendly = when {
                    text.contains("Invalid login", ignoreCase = true) -> "ইমেইল বা পাসওয়ার্ড ভুল"
                    text.contains("already registered", ignoreCase = true) -> "এই ইমেইল দিয়ে আগে অ্যাকাউন্ট খোলা হয়েছে"
                    text.contains("Email not confirmed", ignoreCase = true) -> "ইমেইলে পাঠানো লিংকে ক্লিক করে অ্যাকাউন্ট কনফার্ম করুন"
                    else -> text
                }
                _state.update { it.copy(message = friendly) }
            }
        } catch (e: IOException) {
            _state.update { it.copy(message = "ইন্টারনেট সংযোগ চেক করুন") }
        } catch (e: Exception) {
            _state.update { it.copy(message = "সমস্যা হয়েছে: ${e.message}") }
        } finally {
            _state.update { it.copy(busy = false) }
        }
    }

    private fun act(block: suspend () -> Unit) {
        viewModelScope.launch { runGuarded(block) }
    }

    // ---------- Auth ----------

    fun signUp(name: String, email: String, password: String) = act {
        val hasSession = repo.signUp(email, password, name)
        if (hasSession) loadAll()
        else _state.update { it.copy(notice = "অ্যাকাউন্ট তৈরি হয়েছে। ইমেইলের কনফার্মেশন লিংকে ক্লিক করে লগইন করুন।") }
    }

    fun signIn(email: String, password: String) = act {
        repo.signIn(email, password)
        loadAll()
    }

    fun signInWithGoogle(idToken: String) = act {
        repo.signInWithGoogle(idToken)
        loadAll()
    }

    fun signOut() = act {
        repo.signOut()
        _state.value = UiState(checking = false)
    }

    // ---------- Generic CRUD (RLS enforces ownership) ----------

    /** Insert when id is null, otherwise update the row. */
    fun save(table: String, body: JsonObject, id: String?) = act {
        if (id == null) repo.insert(table, body) else repo.update(table, id, body)
        loadAll()
    }

    fun remove(table: String, id: String) = act {
        repo.delete(table, id)
        loadAll()
    }

    fun saveProfile(body: JsonObject) = act {
        repo.updateProfile(body)
        loadAll()
    }

    /** Several writes in order, then one reload (used for things like shop + entry). */
    fun batch(writes: List<Triple<String, JsonObject, String?>>) = act {
        writes.forEach { (table, body, id) ->
            if (id == null) repo.insert(table, body) else repo.update(table, id, body)
        }
        loadAll()
    }

    // ---------- SMS suggestions ----------

    fun acceptSms(s: SmsSuggestion) {
        dismissSms(s)
        save(
            "transactions",
            row(
                "type" to s.type,
                "amount" to s.amount,
                "category" to "অন্যান্য",
                "note" to s.note,
                "tx_date" to todayIso(),
                "payment_mode" to s.provider,
                "currency" to "BDT"
            ),
            null
        )
    }

    fun dismissSms(s: SmsSuggestion) {
        val remaining = PendingSmsStore.load(getApplication()).filter { it.id != s.id }
        PendingSmsStore.save(getApplication(), remaining)
        _state.update { it.copy(pendingSms = remaining) }
    }
}
