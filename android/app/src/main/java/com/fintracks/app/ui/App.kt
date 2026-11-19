package com.fintracks.app.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.viewmodel.compose.viewModel

enum class Tab(val label: String, val icon: ImageVector) {
    HOME("হোম", Icons.Default.Home),
    TXN("লেনদেন", Icons.AutoMirrored.Filled.List),
    CREDIT("বাকি", Icons.Default.Receipt),
    SETTINGS("সেটিংস", Icons.Default.Settings)
}

@Composable
fun FinTracksApp(vm: AppViewModel = viewModel()) {
    val state by vm.state.collectAsState()

    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
    LaunchedEffect(state.signedIn) {
        if (state.signedIn) {
            permissions.launch(arrayOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.POST_NOTIFICATIONS))
        }
    }

    FinTracksTheme(
        dark = state.profile?.theme != "light",
        accent = AccentColor.fromKey(state.profile?.themeColor)
    ) {
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
                else -> MainScaffold(state, vm)
            }
        }
    }
}

@Composable
private fun MainScaffold(state: UiState, vm: AppViewModel) {
    var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
    var showAddTx by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val privacy = state.profile?.isPrivacyMode == true

    LaunchedEffect(state.message) {
        state.message?.let { snackbar.showSnackbar(it) }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = { Icon(t.icon, contentDescription = t.label) },
                        label = { Text(t.label) }
                    )
                }
            }
        },
        floatingActionButton = {
            if (tab == Tab.HOME || tab == Tab.TXN) {
                FloatingActionButton(onClick = { showAddTx = true }) {
                    Icon(Icons.Default.Add, contentDescription = "লেনদেন যোগ করুন")
                }
            }
        }
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (tab) {
                Tab.HOME -> HomeScreen(
                    state = state,
                    privacy = privacy,
                    onAcceptSms = { vm.acceptSms(it) },
                    onDismissSms = { vm.dismissSms(it) }
                )
                Tab.TXN -> TransactionsScreen(
                    state = state,
                    privacy = privacy,
                    onDelete = { vm.deleteTransaction(it) }
                )
                Tab.CREDIT -> CreditScreen(
                    state = state,
                    privacy = privacy,
                    onAddShop = { n, p, note -> vm.addShop(n, p, note) },
                    onDeleteShop = { vm.deleteShop(it) },
                    onAddEntry = { shop, item, qty, price, date, due, note ->
                        vm.addEntry(shop, item, qty, price, date, due, note)
                    },
                    onDeleteEntry = { vm.deleteEntry(it) },
                    onAddPayment = { shop, amount, date, note -> vm.addPayment(shop, amount, date, note) },
                    onDeletePayment = { vm.deletePayment(it) }
                )
                Tab.SETTINGS -> SettingsScreen(state = state, vm = vm)
            }
        }
    }

    if (showAddTx) {
        AddTransactionDialog(
            onDismiss = { showAddTx = false },
            onSave = { type, amount, category, note, date, mode ->
                vm.addTransaction(type, amount, category, note, date, mode)
                showAddTx = false
            }
        )
    }
}
