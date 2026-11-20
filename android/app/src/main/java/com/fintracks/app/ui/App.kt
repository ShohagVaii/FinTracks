package com.fintracks.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.WbSunny
import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import com.fintracks.app.data.row
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.serialization.json.JsonObject

/** Full-screen tools opened from the Tools tab or the header. */
enum class Overlay(val title: String) {
    ZAKAT("যাকাত ক্যালকুলেটর"), TAX("ট্যাক্স ক্যালকুলেটর"), CURRENCY("কারেন্সি কনভার্টার"),
    CHAT("AI সহকারী"), LOAN("লোন / EMI"), CREDIT("বাকি খাতা"), DEBTS("ধার ও পাওনা"),
    BUDGETS("বাজেট এনভেলপ"), GOALS("সঞ্চয় লক্ষ্য"), INVESTMENTS("ইনভেস্টমেন্ট"),
    SUBSCRIPTIONS("সাবস্ক্রিপশন"), ACCOUNTS("অ্যাকাউন্ট ও ট্রান্সফার"), REPORTS("পরিসংখ্যান ও রিপোর্ট"), SETTINGS("সেটিংস")
}

@Composable
fun FinTracksApp(vm: AppViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    val locked by AppLock.locked.collectAsState()

    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
    LaunchedEffect(state.signedIn) {
        if (state.signedIn) {
            permissions.launch(arrayOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.POST_NOTIFICATIONS))
        }
    }

    FinTracksTheme(dark = state.dark, accent = state.accent) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            when {
                state.checking -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                !state.signedIn -> AuthScreen(
                    state = state,
                    onSignIn = { e, p -> vm.signIn(e, p) },
                    onSignUp = { n, e, p -> vm.signUp(n, e, p) },
                    onGoogle = { token -> vm.signInWithGoogle(token) }
                )
                locked -> LockScreen(onSignOut = { AppLock.unlock(); vm.signOut() })
                else -> MainScaffold(state, vm)
            }
        }
    }
}

@Composable
private fun MainScaffold(state: UiState, vm: AppViewModel) {
    var tab by rememberSaveable { mutableStateOf("dashboard") }
    var overlayName by rememberSaveable { mutableStateOf<String?>(null) }
    var txEditor by remember { mutableStateOf<TxEditor?>(null) }
    val overlay = overlayName?.let { runCatching { Overlay.valueOf(it) }.getOrNull() }
    val snackbar = remember { SnackbarHostState() }
    var showCalc by remember { mutableStateOf(false) }

    LaunchedEffect(state.message) { state.message?.let { snackbar.showSnackbar(it) } }
    LaunchedEffect(state.notice) { state.notice?.let { snackbar.showSnackbar(it) } }
    // Undo: the deleted row can be brought back for 5 seconds.
    LaunchedEffect(state.undo?.token) {
        val u = state.undo ?: return@LaunchedEffect
        val timer = launch {
            delay(5_000)
            snackbar.currentSnackbarData?.dismiss()
        }
        val result = snackbar.showSnackbar(
            message = "${u.label} মুছে ফেলা হয়েছে",
            actionLabel = "ফেরত আনুন",
            duration = SnackbarDuration.Indefinite
        )
        timer.cancel()
        if (result == SnackbarResult.ActionPerformed) vm.undoRemove() else vm.clearUndo()
    }

    BackHandler(enabled = overlay != null) { overlayName = null }

    val openTx: (JsonObject?) -> Unit = { txEditor = TxEditor(it) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (overlay == null) BottomBar(selected = tab, onSelect = { tab = it }, onAdd = { openTx(null) })
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (overlay == null) {
                Header(
                    privacy = state.privacy,
                    dark = state.dark,
                    onCalc = { showCalc = true },
                    onToggleTheme = { vm.saveProfile(row("theme" to if (state.dark) "light" else "dark")) },
                    onTogglePrivacy = { vm.saveProfile(row("is_privacy_mode" to !state.privacy)) },
                    onSettings = { overlayName = Overlay.SETTINGS.name }
                )
            } else {
                OverlayHeader(overlay.title) { overlayName = null }
            }

            Box(Modifier.fillMaxSize()) {
                if (overlay != null) {
                    OverlayContent(overlay, state, vm, openTx)
                } else when (tab) {
                    "dashboard" -> DashboardScreen(state, vm, openTx) { overlayName = it.name }
                    "transactions" -> TransactionsScreen(state, vm, openTx)
                    "shared" -> SharedScreen(state, vm)
                    else -> ToolsScreen { overlayName = it.name }
                }
                if (overlay == null) {
                    FloatingAiButton(
                        onClick = { overlayName = Overlay.CHAT.name },
                        modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 12.dp)
                    )
                }
            }
        }
    }

    if (showCalc) CalculatorDialog(onUse = { showCalc = false }, onDismiss = { showCalc = false })

    txEditor?.let { editor ->
        TransactionDialog(
            state = state,
            existing = editor.row,
            onDismiss = { txEditor = null },
            onSave = { body, id -> vm.save("transactions", body, id); txEditor = null }
        )
    }
}

/** Holds the transaction being edited; null row means a new entry. */
class TxEditor(val row: JsonObject?)

@Composable
private fun Header(
    privacy: Boolean,
    dark: Boolean,
    onCalc: () -> Unit,
    onToggleTheme: () -> Unit,
    onTogglePrivacy: () -> Unit,
    onSettings: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text("FinTrack", color = Sky400, fontSize = 26.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
            Text("PREMIUM FINANCE", color = Slate400, fontSize = 9.sp, letterSpacing = 3.sp)
        }
        HeaderIcon(Icons.Default.Calculate, "ক্যালকুলেটর", onCalc)
        HeaderIcon(Icons.Default.WbSunny, if (dark) "লাইট থিম" else "ডার্ক থিম", onToggleTheme, tint = Amber400)
        HeaderIcon(if (privacy) Icons.Default.VisibilityOff else Icons.Default.Visibility, "প্রাইভেসি", onTogglePrivacy)
        HeaderIcon(Icons.Default.Settings, "সেটিংস", onSettings)
    }
}

@Composable
private fun HeaderIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, onClick: () -> Unit, tint: Color = MaterialTheme.colorScheme.onSurface) {
    Box(
        Modifier
            .padding(start = 8.dp)
            .size(40.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, Color.White.copy(alpha = 0.08f), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = desc, tint = tint, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun OverlayHeader(title: String, onBack: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "ফিরে যান") }
        Text(title, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, fontSize = 18.sp)
    }
}

@Composable
private fun OverlayContent(overlay: Overlay, state: UiState, vm: AppViewModel, openTx: (JsonObject?) -> Unit) {
    when (overlay) {
        Overlay.CHAT -> ChatScreen(state, vm)
        Overlay.ZAKAT -> ZakatScreen(state)
        Overlay.TAX -> TaxScreen(state)
        Overlay.CURRENCY -> CurrencyScreen()
        Overlay.LOAN -> LoanScreen(state, vm)
        Overlay.CREDIT -> BakiScreen(state, vm)
        Overlay.DEBTS -> DebtsScreen(state, vm)
        Overlay.BUDGETS -> BudgetsScreen(state, vm)
        Overlay.GOALS -> GoalsScreen(state, vm)
        Overlay.INVESTMENTS -> InvestmentsScreen(state, vm)
        Overlay.SUBSCRIPTIONS -> SubscriptionsScreen(state, vm)
        Overlay.ACCOUNTS -> AccountsScreen(state, vm)
        Overlay.REPORTS -> ReportsScreen(state)
        Overlay.SETTINGS -> SettingsScreen(state, vm)
    }
}
