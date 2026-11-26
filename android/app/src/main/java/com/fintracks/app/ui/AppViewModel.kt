package com.fintracks.app.ui

import com.fintracks.app.data.AccountInfo
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import android.net.ConnectivityManager
import android.net.Network
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.time.LocalDate

/** Every table the app reads, with its PostgREST query. Rows stay as JSON for generic CRUD. */
val TABLES: List<Pair<String, String>> = listOf(
    "transactions" to "select=*&order=tx_date.desc,created_at.desc",
    "bike_fuel_entries" to "select=*&order=tx_date.desc",
    "bike_maintenance_entries" to "select=*&order=tx_date.desc",
    "calendar_marks" to "select=*&order=mark_date.asc",
    "accounts" to "select=*&order=created_at.asc",
    "budgets" to "select=*",
    "goals" to "select=*",
    "debts" to "select=*&order=settled.asc",
    "debt_people" to "select=*&order=name.asc",
    "debt_payments" to "select=*&order=paid_on.desc",
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
    "cash_counts" to "select=*&order=counted_at.desc",
    "ai_chat_messages" to "select=*&order=created_at.asc"
)

/** The last deleted row, kept for a few seconds so the user can undo. */
data class UndoEntry(val table: String, val row: JsonObject, val token: Long) {
    val label: String get() = undoLabel(table)
}

/** Shown once when a goal crosses 25 / 50 / 75 / 100 %. */
data class GoalCelebration(val goalName: String, val percent: Int, val saved: Double, val target: Double)

private val UNDO_BN = mapOf(
    "transactions" to "লেনদেন", "accounts" to "অ্যাকাউন্ট", "budgets" to "বাজেট", "goals" to "লক্ষ্য",
    "debts" to "ধার", "debt_people" to "ধার ব্যক্তি", "debt_payments" to "ধার পরিশোধ", "loans" to "লোন", "subscriptions" to "সাবস্ক্রিপশন", "investments" to "বিনিয়োগ",
    "credit_entries" to "কেনা", "credit_payments" to "পরিশোধ", "shopping_items" to "শপিং আইটেম",
    "bill_splits" to "বিল ভাগ", "mess_bazaars" to "বাজার", "mess_meals" to "মিল", "mess_deposits" to "জমা",
    "categories" to "ক্যাটাগরি", "cash_counts" to "গণনা"
)
private val UNDO_EN = mapOf(
    "transactions" to "transaction", "accounts" to "account", "budgets" to "budget", "goals" to "goal",
    "debts" to "debt", "debt_people" to "debt person", "debt_payments" to "debt payment", "loans" to "loan", "subscriptions" to "subscription", "investments" to "investment",
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
    /** Rows in the Trash (deleted_at set), grouped by table. Not shown in the app's lists. */
    val trash: Map<String, List<JsonObject>> = emptyMap(),
    val pendingSms: List<SmsSuggestion> = emptyList(),
    val undo: UndoEntry? = null,
    val celebration: GoalCelebration? = null,
    /** Local changes waiting to be sent (the "সিঙ্ক বাকি" count). Shown by the sync indicator, never as a toast. */
    val pendingSync: Int = 0,
    /** Sync problem text (offline, server refused, session renewal). Shown by the sync indicator, never as a toast. */
    val syncError: String? = null,
    /** Demo mode: amounts are shown as fake numbers. Saved on the device. */
    val demo: Boolean = false
) {
    fun rows(table: String): List<JsonObject> = tables[table].orEmpty()
    val dark: Boolean get() = profile?.str("theme") != "light"
    val accent: AccentColor get() = AccentColor.fromKey(profile?.str("theme_color"))
    val privacy: Boolean get() = profile?.bool("is_privacy_mode") == true
    val currency: String get() = profile?.str("currency").takeUnless { it.isNullOrEmpty() } ?: "BDT"
    val name: String get() = profile?.str("name").orEmpty()
}

class AppViewModel(app: Application) : AndroidViewModel(app) {
    /** Shared with the UI (inbox reads and writes through it). */
    val repo = Repository(app)
    /** Notepad: server copy plus offline cache and pending queue (see Notes.kt). */
    val notes = NotesStore(app, repo, viewModelScope)
    /** Cash counter history: device first, then the cash_counts table (see Counter.kt). */
    val cashCounts = CashCountStore(app, repo, viewModelScope)
    /** Local-first store for every table: saved copy, queued writes and sync (see LocalOutbox.kt). */
    val outbox = LocalOutbox(app, repo)
    /** Profile as last read from the server; queued profile changes are applied on top of it. */
    private var serverProfile: JsonObject? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        // A saved session opens the home screen at once, from the saved copy. The server is asked in the background,
        // so a slow or missing network never holds the entry screen.
        val hasSession = repo.session != null
        val savedDemo = getApplication<Application>().getSharedPreferences("fintracks", Context.MODE_PRIVATE).getBoolean("demo_mode", false)
        _state.update { it.copy(signedIn = hasSession, checking = false, demo = savedDemo) }
        if (hasSession) publish(outbox.cached())
        viewModelScope.launch { outbox.pendingCount.collect { n -> _state.update { it.copy(pendingSync = n) } } }
        if (hasSession) viewModelScope.launch { runGuarded { loadAll() } }
        watchNetwork()
    }

    /** Back online: sends queued changes and refreshes the screen, without any tap. */
    private fun watchNetwork() {
        val cm = getApplication<Application>().getSystemService(ConnectivityManager::class.java) ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                viewModelScope.launch {
                    if (repo.session == null) return@launch
                    if (outbox.flush().drained) runCatching { refreshTables() }
                }
            }
        }
        netCallback = cb
        runCatching { cm.registerDefaultNetworkCallback(cb) }
    }

    override fun onCleared() {
        super.onCleared()
        val cb = netCallback ?: return
        runCatching { getApplication<Application>().getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) }
    }

    /** Puts tables on screen with queued changes applied; the profile comes from the same overlay. */
    private fun publish(base: Map<String, List<JsonObject>>, pendingSms: List<SmsSuggestion>? = null, notice: String? = null) {
        val merged = outbox.overlay(base)
        _state.update {
            it.copy(
                signedIn = true,
                tables = liveTables(merged),
                trash = trashedTables(merged),
                profile = merged["profiles"]?.firstOrNull() ?: it.profile,
                pendingSms = pendingSms ?: it.pendingSms,
                notice = notice ?: it.notice
            )
        }
    }

    private fun offlineNotice(e: Exception): String =
        if (e is SupabaseException && e.code == 401) {
            tr("সেশন নবায়ন হয়নি। অনলাইনে এলে আবার চেষ্টা হবে।", "Your session could not be renewed. Sync will retry when online.")
        } else {
            tr("সার্ভারে যোগ হওয়া যাচ্ছে না। ফোনে সেভ করা ডেটা দেখানো হচ্ছে।", "Cannot reach the server. Showing data saved on this phone.")
        }

    /** Best effort: stamps profiles.last_seen_at for the admin's "active users" count. Failures are ignored. */
    private suspend fun touchLastSeen() {
        try {
            repo.updateProfile(buildJsonObject { put("last_seen_at", java.time.Instant.now().toString()) })
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    private suspend fun loadAll() {
        outbox.flush()
        try {
            val profile = repo.list("profiles", "select=*&id=eq.${repo.uid()}").firstOrNull()
            val fetched = Sync.run(getApplication<Application>(), repo, profile?.bool("is_privacy_mode") == true, outbox)
            outbox.flush()
            Sync.markOk(getApplication<Application>())
            touchLastSeen()
            serverProfile = profile
            val base = fetched + ("profiles" to listOfNotNull(profile))
            outbox.remember(base)
            publish(base, pendingSms = PendingSmsStore.load(getApplication()))
            if (outbox.pendingCount.value == 0) backfillBike()
            _state.update { it.copy(syncError = null) }
            syncRegistry(profile, liveTables(fetched)["transactions"].orEmpty())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Server not reached: show the saved copy. The session stays, so nothing logs out.
            publish(outbox.cached())
            _state.update { it.copy(syncError = offlineNotice(e)) }
        }
    }

    /** After a write: refetch the 16 tables only. Full sync (recurring, reminders, widget) runs on login and daily. */
    private suspend fun refreshTables() {
        outbox.flush()
        val fetched = Sync.fetchTables(repo)
        // debt_payments is optional until its migration is applied; keep the last known rows if it could not be read.
        val tables = if ("debt_payments" in fetched) fetched
            else fetched + ("debt_payments" to outbox.cached()["debt_payments"].orEmpty())
        Sync.markOk(getApplication<Application>())
        serverProfile = repo.list("profiles", "select=*&id=eq.${repo.uid()}").firstOrNull() ?: serverProfile
        val base = tables + ("profiles" to listOfNotNull(serverProfile))
        outbox.remember(base)
        publish(base)
        _state.update { it.copy(syncError = null) }
        syncRegistry(state.value.profile, liveTables(tables)["transactions"].orEmpty())
        purgeExpiredTrash()
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
    suspend fun askAi(question: String, context: String, language: String): String = repo.askAi(question, context, language)

    /** Shows busy state, converts errors into readable messages, and signs out on expired sessions. */
    private suspend fun runGuarded(block: suspend () -> Unit) {
        _state.update { it.copy(busy = true, message = null) }
        try {
            block()
        } catch (e: SupabaseException) {
            if (e.code == 401) {
                // The session could not be renewed. Keep it: the saved copy stays and sync retries when online.
                _state.update { it.copy(syncError = offlineNotice(e)) }
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

    fun resetPassword(email: String) = act {
        try {
            repo.sendPasswordReset(email)
        } catch (e: SupabaseException) {
            if (e.code == 429) {
                // Cooldown: say how long to wait, in Bangla or English, instead of the raw server error.
                val secs = Regex("\"retry_after_seconds\"\\s*:\\s*(\\d+)").find(e.body)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                val h = secs / 3600
                val m = (secs % 3600 + 59) / 60
                _state.update {
                    it.copy(message = tr(
                        "এই ইমেইলে প্রতি ৫ ঘণ্টায় একবার রিসেট মেইল যায়। ${bnDigits("$h")} ঘণ্টা ${bnDigits("$m")} মিনিট পরে আবার চেষ্টা করুন।",
                        "A reset mail can be sent once every 5 hours. Try again in ${h}h ${m}m."
                    ))
                }
                return@act
            }
            // Show the real failure (HTTP code and server reason), never the success notice.
            _state.update {
                it.copy(message = tr("পাসওয়ার্ড রিসেট মেইল পাঠানো যায়নি (HTTP ${e.code}): ", "Could not send the reset email (HTTP ${e.code}): ") + e.message.orEmpty())
            }
            return@act
        }
        _state.update {
            it.copy(notice = tr(
                "পাসওয়ার্ড রিসেট লিংক ইমেইলে পাঠানো হয়েছে। মেইল না পেলে Spam বা Junk ফোল্ডার দেখুন।",
                "A password reset link was sent to your email. If it is not in your inbox, check your Spam or Junk folder."
            ))
        }
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

    /** Sends the reset email. Returns null when it was sent, otherwise the reason it was not. */
    suspend fun sendResetEmail(email: String): String? = try {
        repo.sendPasswordReset(email)
        null
    } catch (e: SupabaseException) {
        tr("রিসেট ইমেইল পাঠানো যায়নি (HTTP ${e.code})", "Could not send the reset email (HTTP ${e.code})")
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        tr("রিসেট ইমেইল পাঠানো যায়নি", "Could not send the reset email")
    }

    /** Login email from Auth (profiles has no email column). Used by Settings and account deletion. */
    suspend fun accountEmail(): String = repo.accountEmail()
    suspend fun accountInfo(): AccountInfo = repo.accountInfo()
    fun currentUid(): String = repo.currentUid()
    suspend fun unlinkIdentity(identityId: String) = repo.unlinkIdentity(identityId)
    suspend fun changePassword(email: String, current: String, new: String) = repo.changePassword(email, current, new)

    /** Refreshes every table now. Settings uses this for the manual sync button. */
    fun syncNow() = act {
        processAttachments()
        val r = outbox.flush()
        if (r.dropped > 0) _state.update { it.copy(syncError = refusedText(r.dropped)) }
        if (!r.drained) {
            _state.update { it.copy(syncError = tr("ইন্টারনেট নেই। সংযোগ এলে সিঙ্ক হবে।", "No internet. It will sync when you're back online.")) }
            return@act
        }
        try {
            refreshTables()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(syncError = offlineNotice(e)) }
        }
    }

    /**
     * Uploads a profile photo (needs internet), then saves its URL on the profile. The profile state is updated at once.
     * Errors are thrown to the caller, so the photo dialog can show the real reason. Returns the saved URL.
     */
    suspend fun uploadAvatar(bytes: ByteArray): String {
        val url = repo.uploadAvatar(bytes)
        val uid = repo.uid()
        val b = row("avatar_url" to url)
        val existing = state.value.profile ?: JsonObject(emptyMap())
        reportSync(saveAndSend(listOf(LocalOutbox.op("update", "profiles", uid, b, JsonObject(existing + b)))), null)
        return url
    }

    /**
     * Removes every local store on this phone: preferences, caches, pending offline changes, PIN and lock,
     * local SMS suggestions, and the sign-in on this device. Server data is NOT touched.
     */
    fun resetLocal() {
        val app = getApplication<Application>()
        notes.reset()
        cashCounts.reset()
        outbox.reset(deleteFile = true)
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
        outbox.reset(deleteFile = true)
        val app = getApplication<Application>()
        AppLock.disable(app)
        listOf("fintrack_lock", "fintrack_recurring", "fintrack_reminders", "fintrack_notes", "fintrack_counter", "fintrack_debts", "fintrack_sync", "fintrack_daily").forEach {
            app.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
        }
        PendingSmsStore.save(app, emptyList())
    }

    fun signOut() = act {
        // Remove this device's push token from the account while the session still exists.
        com.fintracks.app.data.PushTokens.forget(getApplication(), repo)
        repo.signOut()
        _state.value = UiState(checking = false)
    }

    // ---------- Generic CRUD (RLS enforces ownership) ----------

    /** Insert when id is null, otherwise update the row. Saved on the phone first, then sent. */
    fun save(table: String, body: JsonObject, id: String?) {
        localChange(saveOps(table, body, id))
    }


    /**
     * Records a বাকি payment and the expense that goes with it, in one batch.
     * The expense (category বাকি পরিশোধ) is linked by source = debt_payment and source_ref = payment id.
     * Purchases on credit do not create expenses, so the money is counted once, when it is paid.
     */
    fun recordDebtPayment(shopName: String, amount: Double, date: String, note: String, shopId: String, method: String = "Cash") {
        val paymentId = java.util.UUID.randomUUID().toString()
        val label = tr("দোকান: ", "Shop: ") + shopName + if (note.isNotBlank()) " · $note" else ""
        batch(
            listOf(
                Triple("credit_payments", row("id" to paymentId, "shop_id" to shopId, "amount" to amount, "paid_on" to date, "note" to note), null),
                Triple(
                    "transactions",
                    row(
                        "type" to "Expense", "amount" to amount, "currency" to state.value.currency,
                        "category" to "বাকি পরিশোধ", "note" to label, "tx_date" to date, "payment_mode" to method,
                        "mood" to "", "is_recurring" to false,
                        "source" to "debt_payment", "source_ref" to paymentId
                    ),
                    null
                )
            )
        )
    }

    /** Adds or updates a person. Renaming a person also updates the name kept on each of their entries. */
    fun savePerson(id: String?, name: String, phone: String, address: String) {
        val body = row("name" to name, "phone" to phone, "address" to address)
        if (id == null) {
            val nid = newId()
            val full = withFields(body, "id" to nid, "created_at" to nowIso())
            localChange(listOf(LocalOutbox.op("insert", "debt_people", nid, full, full)))
            return
        }
        val existing = state.value.rows("debt_people").firstOrNull { it.id() == id } ?: JsonObject(emptyMap())
        val ops = mutableListOf(LocalOutbox.op("update", "debt_people", id, body, JsonObject(existing + body)))
        state.value.rows("debts")
            .filter { it.str("person_id") == id && it.str("person") != name }
            .forEach { d ->
                val b = row("person" to name)
                ops += LocalOutbox.op("update", "debts", d.id(), b, JsonObject(d + b))
            }
        localChange(ops)
    }

    /** Saves changes on the phone, shows them at once, then sends them. Offline they wait in the queue. */
    private suspend fun saveAndSend(ops: List<JsonObject>): FlushResult {
        outbox.enqueue(ops)
        publish(state.value.tables)
        val result = outbox.flush()
        if (result.drained) {
            try { refreshTables() } catch (e: CancellationException) { throw e } catch (e: Exception) {
                // Saved on the phone; the next refresh shows the server copy.
            }
        }
        return result
    }

    private fun refusedText(n: Int): String =
        tr("সার্ভার $n টি পরিবর্তন গ্রহণ করেনি, সেগুলো বাতিল হয়েছে", "The server refused $n change(s); they were removed.")

    /**
     * After a local save: the toast ("done") is shown because the change is already saved on the phone.
     * Sync state (refused, offline, pending count) goes to the sync indicator, never to the toast.
     */
    private fun reportSync(result: FlushResult, done: String?) {
        _state.update { s ->
            when {
                result.dropped > 0 -> s.copy(syncError = refusedText(result.dropped))
                result.drained -> s.copy(syncError = null, notice = done ?: s.notice)
                else -> s.copy(notice = done ?: s.notice)
            }
        }
    }

    /** Every local write runs through here: saved, shown at once, sent (or queued offline). */
    private fun localChange(ops: List<JsonObject>, done: String? = null) = act {
        reportSync(saveAndSend(ops), done)
        processAttachments()
    }

    /** Drops a transaction's photo: its waiting upload, its local copy, and (queued) its Storage object. */
    private fun queueAttachmentDelete(txId: String, row: JsonObject?) {
        val app = getApplication<Application>()
        val path = row?.str("attachment_url").orEmpty()
        if (path.isNotEmpty()) AttachmentStore.queueDelete(app, path)
        AttachmentStore.purge(app, txId)
    }

    /**
     * Uploads waiting transaction photos and deletes removed ones in Storage. Each finished upload writes
     * attachment_url through the outbox. A failure (offline, server refused) leaves the item queued for the next try.
     */
    fun processAttachments() {
        val app = getApplication<Application>()
        if (AttachmentStore.pendingIds(app).isEmpty() && AttachmentStore.pendingDeletes(app).isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            for (txId in AttachmentStore.pendingIds(app)) {
                val file = AttachmentStore.file(app, txId)
                val existing = state.value.rows("transactions").firstOrNull { it.id() == txId }
                if (file == null || existing == null) {
                    AttachmentStore.purge(app, txId)
                    continue
                }
                try {
                    val path = repo.uploadAttachment(txId, file.readBytes())
                    AttachmentStore.unstage(app, txId)
                    val change = JsonObject(mapOf("attachment_url" to JsonPrimitive(path)))
                    withContext(Dispatchers.Main) {
                        localChange(listOf(LocalOutbox.op("update", "transactions", txId, change, JsonObject(existing + change))))
                    }
                } catch (e: Exception) {
                    // Offline or refused: the photo stays queued and is tried again on the next sync.
                }
            }
            for (path in AttachmentStore.pendingDeletes(app)) {
                try {
                    repo.deleteAttachment(path)
                    AttachmentStore.doneDelete(app, path)
                } catch (e: Exception) {
                    // Stays queued until the server answers.
                }
            }
        }
    }

    private fun newId(): String = java.util.UUID.randomUUID().toString()

    private fun nowIso(): String = java.time.OffsetDateTime.now().toString()

    private fun withFields(base: JsonObject, vararg extra: Pair<String, String>): JsonObject =
        JsonObject(base + extra.associate { (k, v) -> k to (JsonPrimitive(v) as JsonElement) })

    /** Insert (id null, or a row with its own id) or update of one row. Debt entries also get created_at and settled. */
    private fun saveOps(table: String, body: JsonObject, id: String?): List<JsonObject> {
        if (table == BikeLink.TX) bikeTxOps(body, id)?.let { return it }
        if (id == null) {
            val nid = body.str("id").ifEmpty { newId() }
            val extra: Map<String, JsonElement> = if (table == "debts")
                mapOf("created_at" to JsonPrimitive(nowIso()), "settled" to JsonPrimitive(false)) else emptyMap()
            val full = JsonObject(body + mapOf<String, JsonElement>("id" to JsonPrimitive(nid)) + extra)
            return listOf(LocalOutbox.op("insert", table, nid, full, full))
        }
        val existing = state.value.rows(table).firstOrNull { it.id() == id } ?: JsonObject(emptyMap())
        return listOf(LocalOutbox.op("update", table, id, body, JsonObject(existing + body)))
    }

    /**
     * Bike category on a transaction. A linked bike expense keeps its category and mirrors amount, date and note
     * into its entry. A manual bike expense gets its fuel entry in the same group, so a refused write drops both.
     * Returns null for every other transaction.
     */
    private fun bikeTxOps(body: JsonObject, id: String?): List<JsonObject>? {
        val s = state.value
        val existing = id?.let { i -> s.rows(BikeLink.TX).firstOrNull { it.id() == i } }
        val linked = existing?.let { BikeLink.entriesForTx(it, s.rows(BikeLink.FUEL), s.rows(BikeLink.MAINT)).firstOrNull() }
        if (existing != null && (existing.str("source") == "bike" || linked != null)) {
            val fixed = JsonObject(body + mapOf("type" to JsonPrimitive("Expense"), "category" to JsonPrimitive(BIKE_CAT)))
            val tx = JsonObject(existing + fixed)
            val ops = mutableListOf(BikeLink.updateOp(BikeLink.TX, existing, fixed))
            linked?.let { (table, entry) ->
                ops += BikeLink.updateOp(table, entry, BikeLink.entryFieldsFromTx(table, tx, entry, ::bikeKindLabel))
            }
            return ops
        }
        val merged = JsonObject((existing ?: JsonObject(emptyMap())) + body)
        if (!BikeLink.isManualBikeTx(merged)) return null
        val txId = id ?: body.str("id").ifEmpty { newId() }
        val entryId = BikeLink.fuelIdForTx(txId)
        val group = newId()
        val link = mapOf("source" to JsonPrimitive("bike"), "source_ref" to JsonPrimitive(entryId))
        val txOp = if (existing == null) {
            BikeLink.insertOp(BikeLink.TX, JsonObject(body + mapOf("id" to JsonPrimitive(txId)) + link), group)
        } else {
            BikeLink.updateOp(BikeLink.TX, existing, JsonObject(body + link), group)
        }
        val entry = BikeLink.fuelRowFor(entryId, merged.str("tx_date").take(10), merged.num("amount"), "", merged.str("note"), txId)
        return listOf(txOp, BikeLink.insertOp(BikeLink.FUEL, entry, group))
    }

    /** Edit of a bike entry: the entry changes and so does its linked expense (amount, date, note). */
    fun saveBikeEntry(table: String, body: JsonObject, id: String, paymentMode: String? = null) {
        val s = state.value
        val entry = s.rows(table).firstOrNull { it.id() == id } ?: return
        val merged = JsonObject(entry + body)
        val ops = mutableListOf(BikeLink.updateOp(table, entry, body))
        val tx = s.rows(BikeLink.TX).firstOrNull { it.id() == merged.str("tx_id") }
        if (tx != null) {
            ops += BikeLink.updateOp(BikeLink.TX, tx, BikeLink.txFieldsOf(table, merged, ::bikeKindLabel, paymentMode))
        }
        localChange(ops)
    }

    /** Links bike entries and bike expenses that are not linked yet. Runs after a full load, with nothing waiting to send. */
    private fun backfillBike() {
        val s = state.value
        if (!s.tables.containsKey(BikeLink.FUEL) || !s.tables.containsKey(BikeLink.MAINT)) return
        val ops = BikeLink.backfill(s.rows(BikeLink.TX), s.rows(BikeLink.FUEL), s.rows(BikeLink.MAINT), s.currency, ::bikeKindLabel)
        if (ops.isNotEmpty()) localChange(ops)
    }

    /** The change that removes one row: a soft delete (moved to the Trash) for trash tables, a hard delete otherwise. */
    private fun removeOp(table: String, id: String, stamp: String): JsonObject? {
        if (table !in TRASH_TABLES) return LocalOutbox.op("delete", table, id)
        val existing = state.value.rows(table).firstOrNull { it.id() == id } ?: return null
        val body = row("deleted_at" to stamp)
        return LocalOutbox.op("update", table, id, body, JsonObject(existing + body))
    }

    /** The change that brings one trashed row back. */
    private fun restoreOp(table: String, trashed: JsonObject): JsonObject {
        val body = JsonObject(mapOf("deleted_at" to JsonNull))
        return LocalOutbox.op("update", table, trashed.id(), body, JsonObject(trashed + body))
    }

    /** Brings back everything that went to the Trash in the given deletions (one per Trash item). */
    fun restoreTrash(stamps: Set<String>) {
        val ops = state.value.trash.flatMap { (t, rows) ->
            rows.filter { it.str("deleted_at") in stamps }.map { restoreOp(t, it) }
        }
        if (ops.isNotEmpty()) localChange(ops)
        stamps.forEach { notes.restoreStamp(it) }
        viewModelScope.launch { stamps.forEach { cashCounts.restore(it) } }
    }

    /** Removes everything from the given deletions for good. Children go first. */
    fun purgeTrash(stamps: Set<String>) {
        val hit = state.value.trash.flatMap { (t, rows) ->
            rows.filter { it.str("deleted_at") in stamps }.map { t to it }
        }.sortedBy { TRASH_PURGE_ORDER.indexOf(it.first) }
        val ops = hit.map { (t, r) ->
            if (t == "transactions") queueAttachmentDelete(r.id(), r)
            LocalOutbox.op("delete", t, r.id())
        }
        if (ops.isNotEmpty()) localChange(ops)
        notes.trashed.value.filter { it.str("deleted_at") in stamps }.forEach { notes.purge(it.id()) }
        viewModelScope.launch {
            cashCounts.trashed.value.filter { it.str("deleted_at") in stamps }.forEach { cashCounts.purge(it.id()) }
        }
    }

    /** Removes everything in the Trash for good. */
    fun emptyTrash() {
        val all = state.value.trash.values.flatten() + notes.trashed.value + cashCounts.trashed.value
        purgeTrash(all.map { it.str("deleted_at") }.toSet())
    }

    /** Removes items that stayed in the Trash for 30 days. Runs on every refresh. */
    private fun purgeExpiredTrash() {
        val all = state.value.trash.values.flatten() + notes.trashed.value + cashCounts.trashed.value
        val stamps = all.map { it.str("deleted_at") }.filter { trashExpired(it) }.toSet()
        if (stamps.isNotEmpty()) purgeTrash(stamps)
    }

    /** Child tables that are deleted with their parent (same rules as the server's foreign keys). */
    private val CHILDREN: Map<String, List<Pair<String, String>>> = mapOf(
        "credit_shops" to listOf("credit_entries" to "shop_id", "credit_payments" to "shop_id"),
        "mess_members" to listOf("mess_bazaars" to "member_id", "mess_meals" to "member_id", "mess_deposits" to "member_id"),
        "debts" to listOf("debt_payments" to "debt_id"),
        "debt_people" to listOf("debts" to "person_id")
    )

    /**
     * Delete of one row with everything that depends on it, children first: a payment takes its
     * transaction, an entry takes its payments, a person takes its entries, an account is cleared
     * from its transactions (the server keeps those transactions and empties the account link).
     */
    private fun deleteOps(table: String, id: String, stamp: String): List<JsonObject> {
        val s = state.value
        val out = mutableListOf<JsonObject>()
        CHILDREN[table].orEmpty().forEach { (child, fk) ->
            s.rows(child).filter { it.str(fk) == id }.forEach { out += deleteOps(child, it.id(), stamp) }
        }
        when (table) {
            // A bike entry and its expense go together, in both directions.
            "bike_fuel_entries", "bike_maintenance_entries" -> {
                val entry = s.rows(table).firstOrNull { it.id() == id }
                if (entry != null) BikeLink.txIdsForEntry(entry, s.rows("transactions")).forEach { tid ->
                    removeOp("transactions", tid, stamp)?.let { out += it }
                }
            }
            "credit_payments" -> s.rows("transactions")
                .filter { it.str("source") == "debt_payment" && it.str("source_ref") == id }
                .forEach { removeOp("transactions", it.id(), stamp)?.let { out += it } }
            "debt_payments" -> s.rows("transactions")
                .filter { it.str("source") == "personal_debt_payment" && it.str("source_ref") == id }
                .forEach { removeOp("transactions", it.id(), stamp)?.let { out += it } }
            "transactions" -> {
                val t = s.rows("transactions").firstOrNull { it.id() == id }
                val ref = t?.str("source_ref").orEmpty()
                if (t != null) BikeLink.entriesForTx(t, s.rows("bike_fuel_entries"), s.rows("bike_maintenance_entries"))
                    .forEach { (entryTable, e) -> removeOp(entryTable, e.id(), stamp)?.let { out += it } }
                if (ref.isNotEmpty() && t?.str("source") == "debt_payment") removeOp("credit_payments", ref, stamp)?.let { out += it }
                if (ref.isNotEmpty() && t?.str("source") == "personal_debt_payment") removeOp("debt_payments", ref, stamp)?.let { out += it }
            }
            "accounts" -> s.rows("transactions").forEach { t ->
                val clearFrom = t.str("account_id") == id
                val clearTo = t.str("to_account_id") == id
                if (clearFrom || clearTo) {
                    val b = mutableMapOf<String, JsonElement>()
                    if (clearFrom) b["account_id"] = JsonNull
                    if (clearTo) b["to_account_id"] = JsonNull
                    out += LocalOutbox.op("update", "transactions", t.id(), JsonObject(b), JsonObject(t + b))
                }
            }
        }
        removeOp(table, id, stamp)?.let { out += it }
        return out
    }

    /**
     * Records a partial or full repayment of one personal debt entry, with its transaction in one batch.
     * Owe (দেনা) payment = Expense; owed (পাওনা) payment = Income. Both are linked by source = personal_debt_payment.
     * The payment row is written first, so an over-payment rejected by the database creates no transaction.
     */
    /** Adds a person and returns the new id, so a quick entry can use the person straight away. */
    fun addPerson(name: String, phone: String): String {
        val nid = newId()
        val full = withFields(row("name" to name, "phone" to phone, "address" to ""), "id" to nid, "created_at" to nowIso())
        localChange(listOf(LocalOutbox.op("insert", "debt_people", nid, full, full)))
        return nid
    }

    /** method = the transaction's payment mode (Cash, Bkash, …); note = the payment's own note. */
    fun recordPersonalDebtPayment(entry: JsonObject, amount: Double, date: String, method: String = "Cash", note: String = "") {
        val paymentId = newId()
        val txId = newId()
        val owed = entry.str("direction") == "owed"
        val person = state.value.rows("debt_people").firstOrNull { it.id() == entry.str("person_id") }?.str("name").orEmpty()
        val reason = entry.str("note")
        val label = tr("ধার পরিশোধ", "Debt payment") + " — " + person + (if (reason.isNotBlank()) " ($reason)" else "")
        val group = "pay-$paymentId"
        // The payment goes first: if the server refuses it (over-payment), its transaction is dropped with it.
        val payment = row(
            "id" to paymentId, "debt_id" to entry.id(), "amount" to amount, "paid_on" to date,
            "note" to note, "created_at" to nowIso()
        )
        val tx = row(
            "id" to txId, "type" to (if (owed) "Income" else "Expense"), "amount" to amount,
            "currency" to state.value.currency, "category" to (if (owed) "ধার আদায়" else "ধার পরিশোধ"),
            "note" to label, "tx_date" to date, "payment_mode" to method, "mood" to "", "is_recurring" to false,
            "source" to "personal_debt_payment", "source_ref" to paymentId
        )
        localChange(
            listOf(
                LocalOutbox.op("insert", "debt_payments", paymentId, payment, payment, group),
                LocalOutbox.op("insert", "transactions", txId, tx, tx, group)
            )
        )
    }

    /** Moves a row with everything that depends on it to the Trash. Only a plain single row can be undone. */
    fun remove(table: String, id: String) {
        val row = state.value.rows(table).firstOrNull { it.id() == id }
        val ops = deleteOps(table, id, nowIso())
        localChange(ops)
        val plain = ops.size == 1 && table !in setOf("credit_shops", "mess_members", "debts", "debt_people", "debt_payments")
        if (row != null && plain) _state.update { it.copy(undo = UndoEntry(table, row, System.nanoTime())) }
    }


    /** Deletes several rows of one table in one local batch. Mirrored rows (debt / dues payments) follow the same rule as remove. */
    fun removeMany(table: String, ids: List<String>) {
        if (ids.isEmpty()) return
        val stamp = nowIso()
        localChange(ids.flatMap { deleteOps(table, it, stamp) })
    }

    /** Turns demo mode on or off. Only the display changes; the saved data stays the same. */
    fun setDemo(on: Boolean) {
        getApplication<Application>().getSharedPreferences("fintracks", Context.MODE_PRIVATE).edit().putBoolean("demo_mode", on).apply()
        _state.update { it.copy(demo = on) }
    }

    fun undoRemove() {
        val u = state.value.undo ?: return
        _state.update { it.copy(undo = null) }
        localChange(listOf(
            if (u.table in TRASH_TABLES) restoreOp(u.table, u.row)
            else LocalOutbox.op("insert", u.table, u.row.id(), u.row, u.row)
        ))
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
        reportSync(saveAndSend(listOf(LocalOutbox.op("update", "goals", goal.id(), row("saved" to newSaved), JsonObject(goal + row("saved" to newSaved))))), null)
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
        val uid = repo.uid()
        val existing = state.value.profile ?: JsonObject(emptyMap())
        reportSync(saveAndSend(listOf(LocalOutbox.op("update", "profiles", uid, body, JsonObject(existing + body)))), null)
    }

    /** Several writes in order, saved and sent together. Within a batch, a refused write also drops the rest. */
    fun batch(writes: List<Triple<String, JsonObject, String?>>) {
        val group = newId()
        val ops = writes.flatMap { (t, b, i) -> saveOps(t, b, i) }.map {
            if (writes.size > 1) JsonObject(it + ("group" to JsonPrimitive(group))) else it
        }
        localChange(ops)
    }

    /** Restores a validated backup: rows are saved on the phone and sent (inserts are sent in batches). */
    fun restoreBackup(plan: BackupPlan) = act {
        val ops = RESTORE_ORDER.flatMap { table ->
            plan.tables[table].orEmpty().filter { it.id().isNotEmpty() }.map { r -> LocalOutbox.op("insert", table, r.id(), r, r) }
        }
        reportSync(saveAndSend(ops), tr("ব্যাকআপ থেকে ${plan.total}টি সারি ফিরিয়ে আনা হয়েছে", "Restored ${plan.total} rows from the backup"))
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
                "tx_date" to s.date.ifEmpty { todayIso() },
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
