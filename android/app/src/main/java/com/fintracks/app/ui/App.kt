package com.fintracks.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.offset

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.platform.LocalContext
import com.fintracks.app.ui.topInsetPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import com.fintracks.app.data.row
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
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
import com.fintracks.app.data.id
import kotlinx.serialization.json.JsonObject
import androidx.compose.ui.text.font.FontWeight

/** Full-screen tools opened from the Tools tab or the header. */
enum class Overlay(private val bnTitle: String, private val enTitle: String) {
    ZAKAT("যাকাত ক্যালকুলেটর", "Zakat calculator"), TAX("ট্যাক্স ক্যালকুলেটর", "Tax calculator"), CURRENCY("কারেন্সি কনভার্টার", "Currency converter"),
    CHAT("AI সহকারী", "AI assistant"), LOAN("লোন / EMI", "Loan / EMI"), CREDIT("বাকি খাতা", "Credit ledger"), DEBTS("ধার ও পাওনা", "Debts & dues"),
    BUDGETS("বাজেট এনভেলপ", "Budget envelopes"), GOALS("সঞ্চয় লক্ষ্য", "Savings goals"), INVESTMENTS("ইনভেস্টমেন্ট", "Investments"),
    SUBSCRIPTIONS("সাবস্ক্রিপশন", "Subscriptions"), ACCOUNTS("অ্যাকাউন্ট ও ট্রান্সফার", "Accounts & transfers"), REPORTS("পরিসংখ্যান ও রিপোর্ট", "Statistics & reports"), SETTINGS("সেটিংস", "Settings"),
    COUNTER("টাকা গণক", "Money counter"), AGE("বয়স ক্যালকুলেটর", "Age calculator"), NOTES("নোটপ্যাড", "Notepad"), INBOX("ইনবক্স", "Inbox");

    val title: String get() = tr(bnTitle, enTitle)
}

/** A success moment to show over the app. A new object each time, so repeated saves replay it. */
class Celebrate(val kind: Int)

@Composable
fun FinTracksApp(vm: AppViewModel = viewModel()) {
    // Full-screen mode for the whole app, including the PIN lock screen.
    ImmersiveEffect()
    val state by vm.state.collectAsState()
    val locked by AppLock.locked.collectAsState()
    val ctx = LocalContext.current
    // 1 = sign-in or Google, 2 = sign-up. Set when the user submits; read when the request finishes.
    var pendingAuth by rememberSaveable { mutableStateOf(0) }
    var celebration by remember { mutableStateOf<Celebrate?>(null) }
    var holdAuth by remember { mutableStateOf(false) }
    var splashOn by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(if (motionReduced(ctx)) 300L else 1400L)
        splashOn = false
    }
    LaunchedEffect(state.busy) {
        if (!state.busy && pendingAuth != 0) {
            val kind = pendingAuth
            pendingAuth = 0
            if (state.signedIn) {
                // Keep the sign-in screen up for the tick, then let the home screen in.
                holdAuth = true
                celebration = Celebrate(if (kind == 2) 2 else 1)
            } else if (kind == 2 && state.notice != null) {
                // Sign-up succeeded but needs the confirmation email: stamp and confetti still play.
                // Sign-up that needs the confirmation email: the stamp and confetti show on the sign-up card.
                celebration = Celebrate(2)
            }
        }
    }
    LaunchedEffect(celebration) {
        val c = celebration ?: return@LaunchedEffect
        kotlinx.coroutines.delay(if (c.kind == 2) 1800L else 900L)
        if (celebration === c) {
            celebration = null
            holdAuth = false
        }
    }

    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
    LaunchedEffect(state.signedIn) {
        if (state.signedIn) {
            permissions.launch(arrayOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.POST_NOTIFICATIONS))
        }
    }

    // Display settings (currency symbol, language, date style, accent, custom categories) apply before any screen draws.
    AppPrefs.apply(state)
    FinTracksTheme(dark = state.dark, accent = state.accent) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            when {
                state.checking -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                !state.signedIn || holdAuth -> AuthScreen(
                    state = state,
                    onSignIn = { e, p -> pendingAuth = 1; vm.signIn(e, p) },
                    onSignUp = { n, e, p -> pendingAuth = 2; vm.signUp(n, e, p) },
                    onGoogle = { token -> pendingAuth = 1; vm.signInWithGoogle(token) },
                    onReset = { email -> vm.resetPassword(email) }
                )
                locked -> LockScreen(onSignOut = { AppLock.unlock(); vm.signOut() })
                else -> MainScaffold(state, vm, onSaved = { celebration = Celebrate(1) })
            }
            celebration?.let { CelebrationOverlay(it.kind) }
            AnimatedVisibility(visible = splashOn, exit = fadeOut(tween(350))) {
                SplashScreen(onSkip = { splashOn = false })
            }
        }
    }
}

/** Short success moment drawn on top of the screen. It takes no taps, so input is never held up. */
@Composable
private fun CelebrationOverlay(kind: Int) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (kind == 2) {
            ConfettiBurst(Modifier.fillMaxSize())
            StampText(tr("নতুন অ্যাকাউন্ট", "New account"), color = Emerald500, tilt = -8f)
        } else {
            SuccessTick()
        }
    }
}

/**
 * Sync state, kept apart from the toast: a thin line under the top edge. It shows the count of changes
 * waiting to be sent, or the sync problem (offline, server refused, session renewal). Nothing when all is sent.
 */
@Composable
private fun SyncIndicator(state: UiState) {
    val error = state.syncError
    if (error == null && state.pendingSync == 0) return
    val text = when {
        error != null && state.pendingSync > 0 -> error + tr(" · সিঙ্ক বাকি: ", " · Waiting to sync: ") + state.pendingSync
        error != null -> error
        else -> tr("সিঙ্ক বাকি: ", "Waiting to sync: ") + state.pendingSync
    }
    Surface(
        color = if (error != null) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth().shimmer(error == null)
    ) {
        Text(
            text,
            fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            color = if (error != null) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer
        )
    }
}

@Composable
private fun MainScaffold(state: UiState, vm: AppViewModel, onSaved: () -> Unit) {
    var tab by rememberSaveable { mutableStateOf("dashboard") }
    val reduce = rememberReduceMotion()
    var overlayName by rememberSaveable { mutableStateOf<String?>(null) }
    // Money counter: true while its history page is open (that page has its own back link, so the header's back arrow is hidden).
    var counterHistory by rememberSaveable { mutableStateOf(false) }
    var txEditor by remember { mutableStateOf<TxEditor?>(null) }
    val overlay = overlayName?.let { runCatching { Overlay.valueOf(it) }.getOrNull() }
    val snackbar = remember { SnackbarHostState() }
    var showCalc by remember { mutableStateOf(false) }
    var smsOpen by remember { mutableStateOf(false) }
    var receipt by remember { mutableStateOf<JsonObject?>(null) }
    var txAction by remember { mutableStateOf<JsonObject?>(null) }

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
            message = tr("${u.label} মুছে ফেলা হয়েছে", "${u.label} deleted"),
            actionLabel = tr("ফেরত আনুন", "Restore"),
            duration = SnackbarDuration.Indefinite
        )
        timer.cancel()
        if (result == SnackbarResult.ActionPerformed) vm.undoRemove() else vm.clearUndo()
    }

    BackHandler(enabled = overlay != null) {
        if (overlay == Overlay.COUNTER && counterHistory) counterHistory = false else overlayName = null
    }

    val openTx: (JsonObject?) -> Unit = { txEditor = TxEditor(it) }

    // Inbox: load on start and after sign-in changes; a push's target opens its screen.
    LaunchedEffect(state.signedIn) { com.fintracks.app.data.Inbox.refresh(vm.repo) }
    val openTarget: (String) -> Unit = { target ->
        val key = target.lowercase()
        val screen = runCatching { Overlay.valueOf(key.uppercase()) }.getOrNull()?.takeIf { it != Overlay.INBOX }
        counterHistory = false
        if (screen != null) {
            overlayName = screen.name
        } else {
            overlayName = null
            tab = when (key) {
                "transactions", "shared", "tools" -> key
                else -> "dashboard"
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (overlay == null) BottomBar(selected = tab, onSelect = { tab = it }, onAdd = { openTx(null) })
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            SyncIndicator(state)
            if (overlay == null) {
                Header(
                    demo = state.demo,
                    privacy = state.privacy,
                    dark = state.dark,
                    onCalc = { showCalc = true },
                    onToggleTheme = { vm.saveProfile(row("theme" to if (state.dark) "light" else "dark")) },
                    onTogglePrivacy = { vm.saveProfile(row("is_privacy_mode" to !state.privacy)) },
                    unread = com.fintracks.app.data.Inbox.unread,
                    onInbox = { overlayName = Overlay.INBOX.name },
                    onSettings = { overlayName = Overlay.SETTINGS.name }
                )
            } else if (overlay != Overlay.CHAT && overlay != Overlay.DEBTS && overlay != Overlay.NOTES && !(overlay == Overlay.COUNTER && counterHistory)) {
                // ধার screen draws its own header (it shows the person's name on the detail page).
                OverlayHeader(overlay.title) { overlayName = null }
            }

            Box(Modifier.fillMaxSize()) {
                if (overlay == Overlay.INBOX) {
                    InboxScreen(vm.repo) { item -> openTarget(item.target) }
                } else if (overlay != null) {
                    OverlayContent(overlay, state, vm, openTx, counterHistory, { counterHistory = it }) {
                        counterHistory = false
                        overlayName = null
                    }
                } else AnimatedContent(
                    targetState = tab,
                    transitionSpec = {
                        if (reduce) {
                            fadeIn(tween(0)) togetherWith fadeOut(tween(0))
                        } else {
                            (fadeIn(tween(250)) + slideInHorizontally(tween(250)) { it / 10 }) togetherWith fadeOut(tween(200))
                        }
                    },
                    label = "tabs"
                ) { current ->
                    when (current) {
                        "dashboard" -> DashboardScreen(state, vm, openTx, { receipt = it }, { txAction = it }) { overlayName = it.name }
                        "transactions" -> TransactionsScreen(state, vm, openTx, { receipt = it }, { txAction = it })
                        "shared" -> SharedScreen(state, vm)
                        else -> ToolsScreen { overlayName = it.name }
                    }
                }
                if (overlay == null) {
                    FloatingSmsButton(
                        onClick = { smsOpen = true },
                        modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 12.dp)
                    )
                }
            }
        }
    }

    if (smsOpen) SmsParserDialog(state, vm) { smsOpen = false }
    if (showCalc) CalculatorDialog(onUse = { showCalc = false }, onDismiss = { showCalc = false })

    receipt?.let { r ->
        ReceiptDialog(
            r, state,
            onEdit = { receipt = null; openTx(r) },
            onDelete = { receipt = null; vm.remove("transactions", r.id()) }
        ) { receipt = null }
    }
    txAction?.let { a ->
        TxActionDialog(
            a,
            onEdit = { txAction = null; openTx(a) },
            onDelete = { txAction = null; vm.remove("transactions", a.id()) },
            onDismiss = { txAction = null }
        )
    }

    txEditor?.let { editor ->
        TransactionDialog(
            state = state,
            existing = editor.row,
            onDismiss = { txEditor = null },
            onSave = { body, id -> vm.save("transactions", body, id); txEditor = null; onSaved() }
        )
    }
}

/** Holds the transaction being edited; null row means a new entry. */
class TxEditor(val row: JsonObject?)

@Composable
private fun Header(
    demo: Boolean,
    privacy: Boolean,
    dark: Boolean,
    unread: Int,
    onInbox: () -> Unit,
    onCalc: () -> Unit,
    onToggleTheme: () -> Unit,
    onTogglePrivacy: () -> Unit,
    onSettings: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .topInsetPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("FinTrack", color = MaterialTheme.colorScheme.primary, fontSize = 26.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                if (demo) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "DEMO",
                        color = Color.White,
                        fontSize = 10.sp,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Rose500).padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
            Text("PREMIUM FINANCE", color = readableOn(Slate400), fontSize = 9.sp, letterSpacing = 3.sp)
        }
        InboxBell(unread, onInbox)
        HeaderIcon(Icons.Default.Calculate, tr("ক্যালকুলেটর", "Calculator"), onCalc)
        HeaderIcon(Icons.Default.WbSunny, if (dark) tr("লাইট থিম", "Light theme") else tr("ডার্ক থিম", "Dark theme"), onToggleTheme, tint = Amber400)
        HeaderIcon(if (privacy) Icons.Default.VisibilityOff else Icons.Default.Visibility, tr("প্রাইভেসি", "Privacy"), onTogglePrivacy)
        HeaderIcon(Icons.Default.Settings, tr("সেটিংস", "Settings"), onSettings)
    }
}

/** Notifications bell with the unread count as a badge. */
@Composable
private fun InboxBell(unread: Int, onClick: () -> Unit) {
    Box(
        Modifier
            .padding(start = 8.dp)
            .size(40.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, faint(0.08f), CircleShape)
            .pressScale()
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        BadgedBox(badge = {
            if (unread > 0) {
                Badge { Text(bnDigits(if (unread > 99) "99+" else unread.toString()), fontSize = 9.sp) }
            }
        }) {
            Icon(Icons.Default.Notifications, contentDescription = tr("ইনবক্স", "Inbox"), tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(20.dp))
        }
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
            .border(1.dp, faint(0.08f), CircleShape)
            .pressScale()
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = desc, tint = tint, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun OverlayHeader(title: String, onBack: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().topInsetPadding().padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(modifier = Modifier.pressScale(), onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = tr("ফিরে যান", "Back")) }
        Text(title, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, fontSize = 18.sp)
    }
}

@Composable
private fun OverlayContent(
    overlay: Overlay,
    state: UiState,
    vm: AppViewModel,
    openTx: (JsonObject?) -> Unit,
    counterHistory: Boolean,
    onCounterHistory: (Boolean) -> Unit,
    onClose: () -> Unit
) {
    when (overlay) {
        Overlay.CHAT -> ChatScreen(state, vm, onClose)
        Overlay.ZAKAT -> ZakatScreen(state)
        Overlay.TAX -> TaxScreen(state)
        Overlay.CURRENCY -> CurrencyScreen()
        Overlay.LOAN -> LoanScreen(state, vm)
        Overlay.CREDIT -> BakiScreen(state, vm)
        Overlay.DEBTS -> DebtsScreen(state, vm, onClose)
        Overlay.BUDGETS -> BudgetsScreen(state, vm)
        Overlay.GOALS -> GoalsScreen(state, vm)
        Overlay.INVESTMENTS -> InvestmentsScreen(state, vm)
        Overlay.SUBSCRIPTIONS -> SubscriptionsScreen(state, vm)
        Overlay.ACCOUNTS -> AccountsScreen(state, vm)
        Overlay.REPORTS -> ReportsScreen(state)
        Overlay.SETTINGS -> SettingsScreen(state, vm)
        Overlay.COUNTER -> MoneyCounterScreen(vm, counterHistory, onCounterHistory)
        Overlay.AGE -> AgeCalcScreen()
        Overlay.NOTES -> NotesScreen(vm, onClose)
        Overlay.INBOX -> Unit // shown by InboxScreen in MainScaffold
    }
}
