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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.time.LocalDate

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
    "mess_meals" to "select=*&order=meal_date.desc",
    "mess_deposits" to "select=*&order=deposit_date.desc",
    "categories" to "select=*&order=name.asc",
    "cash_counts" to "select=*&order=counted_at.desc"
)

/** The last deleted row, kept for a few seconds so the user can undo. */
data class UndoEntry(val table: String, val row: JsonObject, val token: Long) {
    val label: String get() = undoLabel(table)
}

/** Shown once when a goal crosses 25 / 50 / 75 / 100 %. */
data class GoalCelebration(val goalName: String, val percent: Int, val saved: Double, val target: Double)

private val UNDO_BN = mapOf(
    "transactions" to "লেনদেন", "accounts" to "অ্যাকাউন্ট", "budgets" to "বাজেট", "goals" to "লক্ষ্য",
    "debts" to "ধার", "loans" to "লোন", "subscriptions" to "সাবস্ক্রিপশন", "investments" to "বিনিয়োগ",
    "credit_entries" to "কেনা", "credit_payments" to "পরিশোধ", "shopping_items" to "শপিং আইটেম",
    "bill_splits" to "বিল ভাগ", "mess_bazaars" to "বাজার", "mess_meals" to "মিল", "mess_deposits" to "জমা",
    "categories" to "ক্যাটাগরি", "cash_counts" to "গণনা"
)
private val UNDO_EN = mapOf(
    "transactions" to "transaction", "accounts" to "account", "budgets" to "budget", "goals" to "goal",
    "debts" to "debt", "loans" to "loan", "subscriptions" to "subscription", "investments" to "investment",
    "credit_entries" to "purchase", "credit_payments" to "payment", "shopping_items" to "shopping item",
    "bill_splits" to "bill split", "mess_bazaars" to "bazaar", "mess_meals" to "meal", "mess_deposits" to "deposit",
    "categories" to "category", "cash_counts" to "count"
)
fun undoLabel(table: String): String = tr(UNDO_BN[table] ?: "আইটেম", UNDO_EN[table] ?: "item")

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
    /** Notepad: server copy plus offline cache and pending queue (see Notes.kt). */
    val notes = NotesStore(app, repo, viewModelScope)
    /** Cash counter history: device first, then the cash_counts table (see Counter.kt). */
    val cashCounts = CashCountStore(app, repo, viewModelScope)
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
        Sync.markOk(getApplication<Application>())
        _state.update {
            it.copy(
                signedIn = true,
                profile = profile,
                tables = tables,
                pendingSms = PendingSmsStore.load(getApplication())
            )
        }
        syncRegistry(profile, tables["transactions"].orEmpty())
    }

    /** After a write: refetch the 16 tables only. Full sync (recurring, reminders, widget) runs on login and daily. */
    private suspend fun refreshTables() {
        val tables = Sync.fetchTables(repo)
        Sync.markOk(getApplication<Application>())
        _state.update { it.copy(tables = tables) }
        syncRegistry(state.value.profile, tables["transactions"].orEmpty())
    }

    /**
     * Keeps the server voucher_registry in step with this device's saved TRX records.
     * Every record is upserted each time (so the first run also back-fills old records); deleted
     * transactions are sent as status "void". A network failure is retried on the next refresh.
     */
    private suspend fun syncRegistry(profile: JsonObject?, txs: List<JsonObject>) {
        val records = Voucher.syncLocal(getApplication<Application>(), profile, txs)
        if (records.isEmpty()) return
        runCatching { repo.upsert("voucher_registry", records.map { Voucher.registryRow(it) }, "trx_id,checksum") }
    }

    /**
     * Summary voucher reference. The same totals and period reuse their batch; otherwise the batch is
     * STMT<YYYYMM>, then STMT<YYYYMM>-2, -3 ... for the same month. The checksum does not depend on the date of creation.
     */
    suspend fun summaryRef(txs: List<JsonObject>, range: DateRange): Voucher.SummaryRef {
        val income = txs.filter { it.str("type") == "Income" }.sumOf { it.num("amount") }
        val expense = txs.filter { it.str("type") == "Expense" }.sumOf { it.num("amount") }
        val from = range.from?.toString().orEmpty()
        val to = range.to?.toString().orEmpty()
        val period = Voucher.periodOf(from, to)
        val chk = Voucher.summaryChecksum(income, expense, period)
        val existing = repo.list("voucher_registry", "select=batch_id&type=eq.SUMMARY&checksum=eq.$chk&limit=1")
            .firstOrNull()?.str("batch_id").orEmpty()
        val batch = if (existing.isNotEmpty()) {
            existing
        } else {
            val d = range.to ?: LocalDate.now()
            val ym = "%04d%02d".format(d.year, d.monthValue)
            val used = repo.list("voucher_registry", "select=batch_id&type=eq.SUMMARY&batch_id=like.STMT$ym*")
                .map { it.str("batch_id") }.toSet()
            if (used.isEmpty()) "STMT$ym" else "STMT$ym-${used.size + 1}"
        }
        val date = to.ifEmpty { LocalDate.now().toString() }
        val row = buildJsonObject {
            put("trx_id", batch)
            put("checksum", chk)
            put("type", "SUMMARY")
            put("amount", income - expense)
            put("date", date)
            put("profile_name", Voucher.profileName(state.value.profile))
            put("status", "active")
            put("batch_id", batch)
            put("period", period)
        }
        repo.upsert("voucher_registry", listOf(row), "trx_id,checksum")
        return Voucher.SummaryRef(batch, chk, period, date)
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
                    text.contains("Invalid login", ignoreCase = true) -> tr("ইমেইল বা পাসওয়ার্ড ভুল", "Wrong email or password")
                    text.contains("already registered", ignoreCase = true) -> tr("এই ইমেইল দিয়ে আগে অ্যাকাউন্ট খোলা হয়েছে", "An account already exists with this email")
                    text.contains("Email not confirmed", ignoreCase = true) -> tr("ইমেইলে পাঠানো লিংকে ক্লিক করে অ্যাকাউন্ট কনফার্ম করুন", "Confirm your account using the link sent to your email")
                    text.contains("profiles_username", ignoreCase = true) -> tr("এই ইউজারনেম আগে থেকে অন্য কেউ নিয়েছে", "This username is already taken")
                    text.contains("duplicate key", ignoreCase = true) -> tr("এই নামে আগে থেকেই একটি আছে", "One with this name already exists")
                    text.contains("profiles_username_check", ignoreCase = true) || text.contains("username", ignoreCase = true) && text.contains("check", ignoreCase = true) -> tr("ইউজারনেম ৩–২০ অক্ষর হতে হবে: শুধু ইংরেজি ছোট হাতের অক্ষর, সংখ্যা ও _", "Username must be 3–20 characters: lowercase English letters, digits and _")
                    else -> text
                }
                _state.update { it.copy(message = friendly) }
            }
        } catch (e: IllegalArgumentException) {
            _state.update { it.copy(message = e.message ?: tr("ভুল তথ্য", "Invalid details")) }
        } catch (e: IOException) {
            _state.update { it.copy(message = tr("ইন্টারনেট সংযোগ চেক করুন", "Check your internet connection")) }
        } catch (e: Exception) {
            _state.update { it.copy(message = tr("সমস্যা হয়েছে: ${e.message}", "Something went wrong: ${e.message}")) }
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
        else _state.update { it.copy(notice = tr("অ্যাকাউন্ট তৈরি হয়েছে। ইমেইলের কনফার্মেশন লিংকে ক্লিক করে লগইন করুন।", "Account created. Click the confirmation link in your email, then log in.")) }
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
        val email = repo.accountEmail()
        if (email.isBlank()) throw IllegalStateException(tr("ইমেইল পাওয়া যায়নি, আবার লগইন করুন", "Email not found, please log in again"))
        repo.signIn(email, password)
        repo.deleteAccount()
        clearLocalData()
        _state.value = UiState(checking = false, notice = tr("আপনার অ্যাকাউন্ট ও সব ডেটা স্থায়ীভাবে মুছে ফেলা হয়েছে।", "Your account and all data have been permanently deleted."))
    }

    /** Login email from Auth (profiles has no email column). Used by Settings and account deletion. */
    suspend fun accountEmail(): String = repo.accountEmail()

    /** Refreshes every table now. Settings uses this for the manual sync button. */
    fun syncNow() = act { refreshTables(); Sync.markOk(getApplication<Application>()) }

    /** Uploads a profile photo and saves its URL on the profile. */
    fun saveAvatar(bytes: ByteArray) = act {
        val url = repo.uploadAvatar(bytes)
        repo.updateProfile(row("avatar_url" to url))
        _state.update { it.copy(profile = JsonObject(state.value.profile.orEmpty() + row("avatar_url" to url))) }
        _state.update { it.copy(notice = tr("প্রোফাইল ছবি সেভ হয়েছে", "Profile photo saved")) }
    }

    /**
     * Removes every local store on this phone: preferences, caches, pending offline changes, PIN and lock,
     * local SMS suggestions, and the sign-in on this device. Server data is NOT touched.
     */
    fun resetLocal() {
        val app = getApplication<Application>()
        notes.reset()
        cashCounts.reset()
        repo.session = null
        AppLock.disable(app)
        DailyReminder.cancel(app)
        PendingSmsStore.save(app, emptyList())
        // The "fintracks" file (session) is already cleared above; everything else goes.
        java.io.File(app.applicationInfo.dataDir, "shared_prefs").listFiles()?.forEach { f ->
            val name = f.name.removeSuffix(".xml")
            if (name != "fintracks") app.deleteSharedPreferences(name)
        }
        _state.value = UiState(checking = false, notice = tr("এই ফোনের সব লোকাল ডেটা মুছে ফেলা হয়েছে। সার্ভারের ডেটা অক্ষত আছে।", "All local data on this phone has been deleted. Server data is intact."))
    }

    private fun clearLocalData() {
        val app = getApplication<Application>()
        AppLock.disable(app)
        listOf("fintrack_lock", "fintrack_recurring", "fintrack_reminders", "fintrack_notes", "fintrack_counter", "fintrack_sync", "fintrack_daily").forEach {
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
        refreshTables()
    }

    /**
     * Records a বাকি payment and the expense that goes with it, in one batch.
     * The expense (category বাকি পরিশোধ) is linked by source = debt_payment and source_ref = payment id.
     * Purchases on credit do not create expenses, so the money is counted once, when it is paid.
     */
    fun recordDebtPayment(shopName: String, amount: Double, date: String, note: String, shopId: String) {
        val paymentId = java.util.UUID.randomUUID().toString()
        val label = "দোকান: $shopName" + if (note.isNotBlank()) " · $note" else ""
        batch(
            listOf(
                Triple("credit_payments", row("id" to paymentId, "shop_id" to shopId, "amount" to amount, "paid_on" to date, "note" to note), null),
                Triple(
                    "transactions",
                    row(
                        "type" to "Expense", "amount" to amount, "currency" to state.value.currency,
                        "category" to "বাকি পরিশোধ", "note" to label, "tx_date" to date, "payment_mode" to "Cash",
                        "mood" to "", "is_recurring" to false,
                        "source" to "debt_payment", "source_ref" to paymentId
                    ),
                    null
                )
            )
        )
    }

    /** Deletes a row and keeps a copy so it can be restored from the undo snackbar. */
    fun remove(table: String, id: String) = act {
        val row = state.value.rows(table).firstOrNull { it.id() == id }
        // A বাকি payment and the expense it created are removed together, so the two never disagree.
        val linked: List<Pair<String, String>> = when {
            table == "credit_payments" -> state.value.rows("transactions")
                .filter { it.str("source") == "debt_payment" && it.str("source_ref") == id }
                .map { "transactions" to it.id() }
            table == "transactions" && row?.str("source") == "debt_payment" -> listOfNotNull(
                row?.str("source_ref")?.takeIf { it.isNotEmpty() }?.let { "credit_payments" to it }
            )
            else -> emptyList()
        }
        repo.delete(table, id)
        linked.forEach { (t, i) -> repo.delete(t, i) }
        refreshTables()
        // Shops delete their purchases and payments too, so they are not undoable. Linked rows are not undoable either.
        // Shops and members delete their children too (purchases, bazaar and meal rows), so they are not undoable.
        if (row != null && table != "credit_shops" && table != "mess_members" && linked.isEmpty()) {
            _state.update { it.copy(undo = UndoEntry(table, row, System.nanoTime())) }
        }
    }

    fun undoRemove() = act {
        val u = state.value.undo ?: return@act
        _state.update { it.copy(undo = null) }
        repo.upsert(u.table, listOf(u.row))
        refreshTables()
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
        if (after > target + 0.005) throw IllegalArgumentException(tr("লক্ষ্যের বেশি জমা করা যাবে না", "Cannot save more than the goal"))
        if (after < -0.005) throw IllegalArgumentException(tr("জমার বেশি তোলা যাবে না", "Cannot withdraw more than saved"))
        val newSaved = after.coerceIn(0.0, target)
        repo.update("goals", goal.id(), row("saved" to newSaved))
        refreshTables()
        val oldPct = if (target > 0) (before / target * 100).toInt() else 0
        val newPct = if (target > 0) (newSaved / target * 100).toInt() else 0
        val crossed = listOf(100, 75, 50, 25).firstOrNull { oldPct < it && newPct >= it }
        if (delta > 0 && crossed != null) {
            _state.update {
                it.copy(celebration = GoalCelebration(goal.str("name"), crossed, newSaved, target))
            }
        }
    }

    /** Saves profile fields and applies them locally at once (theme, privacy, accent), so the UI changes without a reload. */
    fun saveProfile(body: JsonObject) = act {
        repo.updateProfile(body)
        val merged = JsonObject(state.value.profile.orEmpty() + body)
        _state.update { it.copy(profile = merged) }
        refreshTables()
    }

    /** Several writes in order, then one reload (used for things like shop + entry). */
    fun batch(writes: List<Triple<String, JsonObject, String?>>) = act {
        writes.forEach { (table, body, id) ->
            if (id == null) repo.insert(table, body) else repo.update(table, id, body)
        }
        refreshTables()
    }

    /** Restores a validated backup. Existing rows stay; rows with the same id are updated. */
    fun restoreBackup(plan: BackupPlan) = act {
        RESTORE_ORDER.forEach { table ->
            val rows = plan.tables[table].orEmpty()
            if (rows.isNotEmpty()) repo.upsert(table, rows)
        }
        refreshTables()
        _state.update { it.copy(notice = tr("ব্যাকআপ থেকে ${plan.total}টি সারি ফিরিয়ে আনা হয়েছে", "Restored ${plan.total} rows from the backup")) }
    }

    // ---------- SMS suggestions ----------

    fun acceptSms(s: SmsSuggestion) {
        dismissSms(s)
        save(
            "transactions",
            row(
                "source" to "sms",
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
