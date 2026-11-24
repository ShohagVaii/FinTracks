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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.Notifications
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
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
    BIKE("বাইক টুল", "Bike tool"),
    TRASH("আবর্জনা", "Trash"),
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
    UpdateHost()
    val state by vm.state.collectAsState()
    // Full-screen mode for the whole app, including the PIN lock screen. Status icons follow the app theme.
    ImmersiveEffect(dark = state.dark)
    val locked by AppLock.locked.collectAsState()
    val ctx = LocalContext.current
    // 1 = sign-in or Google, 2 = sign-up. Set when the user submits; read when the request finishes.
    var pendingAuth by rememberSaveable { mutableStateOf(0) }
    var celebration by remember { mutableStateOf<Celebrate?>(null) }
    var holdAuth by remember { mutableStateOf(false) }
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
    val reduce = rememberReduceMotion()
    // The one back stack (see NavHistory). Stored as one string; routes never contain a newline.
    var navRaw by rememberSaveable { mutableStateOf(NavHistory.HOME) }
    val stack = navRaw.split("\n")
    val base = NavHistory.base(stack)
    val tab = NavHistory.tab(base) ?: "dashboard"
    val overlayRoute = NavHistory.overlay(base)
    val overlay = overlayRoute?.let { runCatching { Overlay.valueOf(it.first) }.getOrNull() }
    val subPage = overlayRoute?.second.orEmpty()
    // Money counter: its history page is a sub-page of the counter overlay.
    val counterHistory = overlay == Overlay.COUNTER && subPage == "hist"
    var exitAsk by remember { mutableStateOf(false) }
    val exitContext = LocalContext.current
    fun commit(next: List<String>) { navRaw = next.joinToString("\n") }
    val go: (String) -> Unit = { commit(NavHistory.push(stack, it)) }
    // Back: previous entry; else home; else ask before leaving the app.
    val goBack: () -> Unit = {
        when {
            stack.size > 1 -> commit(NavHistory.pop(stack))
            stack.last() != NavHistory.HOME -> commit(listOf(NavHistory.HOME))
            else -> exitAsk = true
        }
    }
    var txEditor by remember { mutableStateOf<TxEditor?>(null) }
    val snackbar = remember { SnackbarHostState() }
    var showCalc by remember { mutableStateOf(false) }
    var smsOpen by remember { mutableStateOf(false) }
    // Receipt and action dialogs are routes on the stack (rcpt:<id>, act:<id>); the transaction comes from the live rows.
    val dialogRoute = stack.last()
    val receipt = dialogRoute.takeIf { it.startsWith("rcpt:") }
        ?.let { r -> state.rows("transactions").firstOrNull { it.id() == r.removePrefix("rcpt:") } }
    val txAction = dialogRoute.takeIf { it.startsWith("act:") }
        ?.let { r -> state.rows("transactions").firstOrNull { it.id() == r.removePrefix("act:") } }

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

    BackHandler { goBack() }

    val openTx: (JsonObject?) -> Unit = { txEditor = TxEditor(it) }

    // Inbox: load on start and after sign-in changes; a push's target opens its screen.
    LaunchedEffect(state.signedIn) { com.fintracks.app.data.Inbox.refresh(vm.repo) }
    // Push: save this device's FCM token for the signed-in user.
    val pushContext = LocalContext.current
    LaunchedEffect(state.signedIn) {
        if (state.signedIn) com.fintracks.app.data.PushSetup.register(pushContext, vm.repo)
    }
    val openTarget: (String) -> Unit = { target ->
        val key = target.lowercase()
        val screen = runCatching { Overlay.valueOf(key.uppercase()) }.getOrNull()?.takeIf { it != Overlay.INBOX }
        if (screen != null) {
            go("ov:${screen.name}")
        } else {
            go("tab:" + when (key) {
                "transactions", "shared", "tools" -> key
                else -> "dashboard"
            })
        }
    }

    // A push tap waits here until the user is signed in, then opens its target screen.
    val pendingPush = com.fintracks.app.data.PushRouter.pending.collectAsState().value
    LaunchedEffect(pendingPush, state.signedIn) {
        val target = pendingPush
        if (target != null && state.signedIn) {
            openTarget(target)
            com.fintracks.app.data.PushRouter.clear()
        }
    }

    // The status bar height is added by the header (topInsetPadding) only. The Scaffold must not add it again.
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal),
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (overlay == null) BottomBar(selected = tab, onSelect = { go("tab:$it") }, onAdd = { openTx(null) })
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
                    onInbox = { go("ov:${Overlay.INBOX.name}") },
                    onSettings = { go("ov:${Overlay.SETTINGS.name}") }
                )
            } else if (overlay != Overlay.CHAT && overlay != Overlay.DEBTS && overlay != Overlay.NOTES && !(overlay == Overlay.COUNTER && counterHistory)) {
                // ধার screen draws its own header (it shows the person's name on the detail page).
                OverlayHeader(overlay.title) { goBack() }
            }

            Box(Modifier.fillMaxSize()) {
                if (overlay == Overlay.INBOX) {
                    InboxScreen(vm.repo) { item -> openTarget(item.target) }
                } else if (overlay != null) {
                    OverlayContent(overlay, state, vm, openTx, subPage, go, counterHistory) { goBack() }
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
                        "dashboard" -> DashboardScreen(state, vm, openTx, { go("rcpt:${it.id()}") }, { go("act:${it.id()}") }) { go("ov:${it.name}") }
                        "transactions" -> TransactionsScreen(state, vm, openTx, { go("rcpt:${it.id()}") }, { go("act:${it.id()}") }, { txEditor = TxEditor(null, transfer = true) })
                        "shared" -> SharedScreen(state, vm)
                        else -> ToolsScreen { go("ov:${it.name}") }
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

    // Leave the app only after confirming (the back press on home).
    if (exitAsk) {
        AlertDialog(
            onDismissRequest = { exitAsk = false },
            title = { Text(tr("অ্যাপ থেকে বের হতে চান?", "Exit the app?")) },
            confirmButton = {
                TextButton(onClick = { exitAsk = false; (exitContext as? android.app.Activity)?.finish() }) {
                    Text(tr("বের হবো", "Exit"))
                }
            },
            dismissButton = { TextButton(onClick = { exitAsk = false }) { Text(tr("না", "No")) } }
        )
    }

    receipt?.let { r ->
        ReceiptDialog(
            r, state,
            onEdit = { goBack(); openTx(r) },
            onDelete = { goBack(); vm.remove("transactions", r.id()) }
        ) { goBack() }
    }
    txAction?.let { a ->
        TxActionDialog(
            a,
            onEdit = { goBack(); openTx(a) },
            onDelete = { goBack(); vm.remove("transactions", a.id()) },
            onDismiss = { goBack() }
        )
    }

    txEditor?.let { editor ->
        TransactionDialog(
            state = state,
            existing = editor.row,
            transfer = editor.transfer,
            onDismiss = { txEditor = null },
            onSave = { body, id -> vm.save("transactions", body, id); txEditor = null; onSaved() }
        )
    }
}

/** Holds the transaction being edited; null row means a new entry. */
class TxEditor(val row: JsonObject?, val transfer: Boolean = false)

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
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("FinTrack", color = FinTrackCyan, fontSize = 22.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, maxLines = 1, softWrap = false)
                if (demo) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "DEMO",
                        color = Color.White,
                        fontSize = 9.sp,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Rose500).padding(horizontal = 5.dp, vertical = 1.dp)
                    )
                }
            }
            Text("PREMIUM FINANCE", color = readableOn(Slate400), fontSize = 8.sp, letterSpacing = 2.sp, maxLines = 1, softWrap = false)
        }
        InboxBell(unread, onInbox)
        HeaderIcon(Icons.Default.Calculate, tr("ক্যালকুলেটর", "Calculator"), onCalc)
        HeaderIcon(Icons.Default.WbSunny, if (dark) tr("লাইট থিম", "Light theme") else tr("ডার্ক থিম", "Dark theme"), onToggleTheme)
        HeaderIcon(if (privacy) Icons.Default.VisibilityOff else Icons.Default.Visibility, tr("প্রাইভেসি", "Privacy"), onTogglePrivacy)
        HeaderIcon(Icons.Default.Settings, tr("সেটিংস", "Settings"), onSettings)
    }
}

/** Notifications bell with the unread count as a badge. */
@Composable
private fun InboxBell(unread: Int, onClick: () -> Unit) {
    Box(
        Modifier
            .padding(start = 6.dp)
            .size(36.dp)
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
            .padding(start = 6.dp)
            .size(36.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, faint(0.08f), CircleShape)
            .pressScale()
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = desc, tint = tint, modifier = Modifier.size(18.dp))
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
    subPage: String,
    go: (String) -> Unit,
    counterHistory: Boolean,
    onClose: () -> Unit
) {
    when (overlay) {
        Overlay.CHAT -> ChatScreen(state, vm, onClose)
        Overlay.ZAKAT -> ZakatScreen(state)
        Overlay.TAX -> TaxScreen(state)
        Overlay.CURRENCY -> CurrencyScreen()
        Overlay.LOAN -> LoanScreen(state, vm)
        Overlay.CREDIT -> BakiScreen(state, vm, subPage, go, onClose)
        Overlay.DEBTS -> DebtsScreen(state, vm, subPage.ifEmpty { null }, go, onClose, onClose)
        Overlay.BUDGETS -> BudgetsScreen(state, vm)
        Overlay.GOALS -> GoalsScreen(state, vm)
        Overlay.INVESTMENTS -> InvestmentsScreen(state, vm)
        Overlay.SUBSCRIPTIONS -> SubscriptionsScreen(state, vm)
        Overlay.ACCOUNTS -> AccountsScreen(state, vm)
        Overlay.REPORTS -> ReportsScreen(state)
        Overlay.SETTINGS -> SettingsScreen(state, vm, subPage, { go("ov:SETTINGS/$it") }, onClose)
        Overlay.COUNTER -> MoneyCounterScreen(vm, counterHistory) { on -> if (on) go("ov:COUNTER/hist") else onClose() }
        Overlay.AGE -> AgeCalcScreen()
        Overlay.NOTES -> NotesScreen(vm, subPage, go, onClose)
        Overlay.BIKE -> BikeScreen(state, vm)
        Overlay.TRASH -> TrashScreen(state, vm)
        Overlay.INBOX -> Unit // shown by InboxScreen in MainScaffold
    }
}
