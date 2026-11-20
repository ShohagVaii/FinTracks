package com.fintracks.app.ui

import android.app.Application
import android.content.Context
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import java.io.IOException

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

/** The last deleted row, kept for a few seconds so the user can undo. */
data class UndoEntry(val table: String, val row: JsonObject, val token: Long) {
    val label: String get() = UNDO_LABELS[table] ?: "আইটেম"
}

/** Shown once when a goal crosses 25 / 50 / 75 / 100 %. */
data class GoalCelebration(val goalName: String, val percent: Int, val saved: Double, val target: Double)

val UNDO_LABELS = mapOf(
    "transactions" to "লেনদেন", "accounts" to "অ্যাকাউন্ট", "budgets" to "বাজেট", "goals" to "লক্ষ্য",
    "debts" to "ধার", "loans" to "লোন", "subscriptions" to "সাবস্ক্রিপশন", "investments" to "বিনিয়োগ",
    "credit_entries" to "কেনা", "credit_payments" to "পরিশোধ", "shopping_items" to "শপিং আইটেম",
    "bill_splits" to "বিল ভাগ", "mess_bazaars" to "বাজার", "mess_meals" to "মিল"
)

data class UiState(
    val checking: Boolean = true,
    val signedIn: Boolean = false,
    val busy: Boolean = false,
    val message: String? = null,
    val notice: String? = null,
    val profile: JsonObject? = null,
    val tables: Map<String, List<JsonObject>> = emptyMap(),
    val pendingSms: List<SmsSuggestion> = emptyList(),
    val undo: UndoEntry? = null,
    val celebration: GoalCelebration? = null
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

    private suspend fun loadAll() {
        val profile = repo.list("profiles", "select=*&id=eq.${repo.uid()}").firstOrNull()
        val tables = Sync.run(getApplication<Application>(), repo, profile?.bool("is_privacy_mode") == true)
        _state.update {
            it.copy(
                signedIn = true,
                profile = profile,
                tables = tables,
                pendingSms = PendingSmsStore.load(getApplication())
            )
        }
    }

    /** Sends a question with a short financial summary to the server-side AI function. */
    suspend fun askAi(question: String, context: String): String = repo.askAi(question, context)

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
        } catch (e: IllegalArgumentException) {
            _state.update { it.copy(message = e.message ?: "ভুল তথ্য") }
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

    /** Re-checks the password, deletes the account on the server, then clears every local store. */
    fun deleteAccount(password: String) = act {
        val email = state.value.profile?.str("email").orEmpty()
        if (email.isBlank()) throw IllegalStateException("ইমেইল পাওয়া যায়নি, আবার লগইন করুন")
        repo.signIn(email, password)
        repo.deleteAccount()
        clearLocalData()
        _state.value = UiState(checking = false, notice = "আপনার অ্যাকাউন্ট ও সব ডেটা স্থায়ীভাবে মুছে ফেলা হয়েছে।")
    }

    private fun clearLocalData() {
        val app = getApplication<Application>()
        AppLock.disable(app)
        listOf("fintrack_lock", "fintrack_recurring", "fintrack_reminders").forEach {
            app.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
        }
        PendingSmsStore.save(app, emptyList())
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

    /** Deletes a row and keeps a copy so it can be restored from the undo snackbar. */
    fun remove(table: String, id: String) = act {
        val row = state.value.rows(table).firstOrNull { it.id() == id }
        repo.delete(table, id)
        loadAll()
        // Shops delete their purchases and payments too, so they are not undoable.
        if (row != null && table != "credit_shops") {
            _state.update { it.copy(undo = UndoEntry(table, row, System.nanoTime())) }
        }
    }

    fun undoRemove() = act {
        val u = state.value.undo ?: return@act
        _state.update { it.copy(undo = null) }
        repo.upsert(u.table, listOf(u.row))
        loadAll()
    }

    fun clearUndo() {
        _state.update { it.copy(undo = null) }
    }

    fun dismissCelebration() {
        _state.update { it.copy(celebration = null) }
    }

    /**
     * Adds (delta > 0) or withdraws (delta < 0) money from a goal. Refuses to go past the target
     * or below zero. Crossing a 25/50/75/100 % mark opens the celebration.
     */
    fun changeGoal(goal: JsonObject, delta: Double) = act {
        val target = goal.num("target")
        val before = goal.num("saved")
        val after = before + delta
        if (after > target + 0.005) throw IllegalArgumentException("লক্ষ্যের বেশি জমা করা যাবে না")
        if (after < -0.005) throw IllegalArgumentException("জমার বেশি তোলা যাবে না")
        val newSaved = after.coerceIn(0.0, target)
        repo.update("goals", goal.id(), row("saved" to newSaved))
        loadAll()
        val oldPct = if (target > 0) (before / target * 100).toInt() else 0
        val newPct = if (target > 0) (newSaved / target * 100).toInt() else 0
        val crossed = listOf(100, 75, 50, 25).firstOrNull { oldPct < it && newPct >= it }
        if (delta > 0 && crossed != null) {
            _state.update {
                it.copy(celebration = GoalCelebration(goal.str("name"), crossed, newSaved, target))
            }
        }
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

    /** Restores a validated backup. Existing rows stay; rows with the same id are updated. */
    fun restoreBackup(plan: BackupPlan) = act {
        RESTORE_ORDER.forEach { table ->
            val rows = plan.tables[table].orEmpty()
            if (rows.isNotEmpty()) repo.upsert(table, rows)
        }
        loadAll()
        _state.update { it.copy(notice = "ব্যাকআপ থেকে ${plan.total}টি সারি ফিরিয়ে আনা হয়েছে") }
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
