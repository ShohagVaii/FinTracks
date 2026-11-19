package com.fintracks.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fintracks.app.data.EntryRow
import com.fintracks.app.data.PaymentRow
import com.fintracks.app.data.PendingSmsStore
import com.fintracks.app.data.ProfileRow
import com.fintracks.app.data.Repository
import com.fintracks.app.data.ShopRow
import com.fintracks.app.data.SmsSuggestion
import com.fintracks.app.data.SupabaseException
import com.fintracks.app.data.TxRow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException

data class UiState(
    val checking: Boolean = true,
    val signedIn: Boolean = false,
    val busy: Boolean = false,
    val message: String? = null,
    val notice: String? = null,
    val profile: ProfileRow? = null,
    val transactions: List<TxRow> = emptyList(),
    val shops: List<ShopRow> = emptyList(),
    val entries: List<EntryRow> = emptyList(),
    val payments: List<PaymentRow> = emptyList(),
    val pendingSms: List<SmsSuggestion> = emptyList()
)

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
        val profile = repo.fetchProfile()
        val tx = repo.fetchTransactions()
        val shops = repo.fetchShops()
        val entries = repo.fetchEntries()
        val payments = repo.fetchPayments()
        _state.update {
            it.copy(
                signedIn = true,
                profile = profile ?: ProfileRow(id = repo.uid()),
                transactions = tx,
                shops = shops,
                entries = entries,
                payments = payments,
                pendingSms = PendingSmsStore.load(getApplication())
            )
        }
    }

    /** Shows busy state, converts errors into a readable message, and signs out on expired sessions. */
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
                val friendly = if (text.contains("Invalid login", ignoreCase = true)) "ইমেইল বা পাসওয়ার্ড ভুল" else text
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
        else _state.update { it.copy(notice = "অ্যাকাউন্ট তৈরি হয়েছে। ইমেইল কনফার্ম করে লগইন করুন।") }
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

    // ---------- Transactions ----------

    fun addTransaction(type: String, amount: Double, category: String, note: String, date: String, mode: String) = act {
        repo.insertTransaction(type, amount, category, note, date, mode)
        loadAll()
    }

    fun deleteTransaction(id: String) = act {
        repo.delete("transactions", id)
        loadAll()
    }

    // ---------- Credit (বাকি) ----------

    fun addShop(name: String, phone: String, note: String) = act {
        repo.insertShop(name, phone, note)
        loadAll()
    }

    fun deleteShop(id: String) = act {
        repo.delete("credit_shops", id)
        loadAll()
    }

    fun addEntry(
        shopId: String, item: String, quantity: Double, unitPrice: Double,
        purchaseDate: String, dueDate: String?, note: String
    ) = act {
        repo.insertEntry(shopId, item, quantity, unitPrice, purchaseDate, dueDate, note)
        loadAll()
    }

    fun deleteEntry(id: String) = act {
        repo.delete("credit_entries", id)
        loadAll()
    }

    fun addPayment(shopId: String, amount: Double, paidOn: String, note: String) = act {
        repo.insertPayment(shopId, amount, paidOn, note)
        loadAll()
    }

    fun deletePayment(id: String) = act {
        repo.delete("credit_payments", id)
        loadAll()
    }

    // ---------- Profile / settings ----------

    fun updateProfile(name: String? = null, theme: String? = null, themeColor: String? = null, privacy: Boolean? = null) = act {
        repo.updateProfile(name, theme, themeColor, privacy)
        loadAll()
    }

    // ---------- SMS suggestions ----------

    fun acceptSms(s: SmsSuggestion) {
        dismissSms(s)
        val mode = if (s.provider == "Cash") "Cash" else s.provider
        addTransaction(s.type, s.amount, "Others", s.note, todayIso(), mode)
    }

    fun dismissSms(s: SmsSuggestion) {
        val remaining = PendingSmsStore.load(getApplication()).filter { it.id != s.id }
        PendingSmsStore.save(getApplication(), remaining)
        _state.update { it.copy(pendingSms = remaining) }
    }
}
