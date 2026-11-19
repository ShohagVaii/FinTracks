package com.fintracks.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.fintracks.app.data.row
import com.fintracks.app.data.str

/** Settings: profile, theme, accent colour, currency, privacy, sign-out. */
@Composable
fun SettingsScreen(state: UiState, vm: AppViewModel) {
    var name by remember(state.name) { mutableStateOf(state.name) }
    Page {
        AppCard {
            Text("প্রোফাইল", fontWeight = FontWeight.Bold)
            TextIn("নাম", name, { name = it })
            Button(onClick = { vm.saveProfile(row("name" to name)) }, enabled = name.isNotBlank() && name != state.name) { Text("নাম সেভ") }
        }
        AppCard {
            Text("থিম", fontWeight = FontWeight.Bold)
            ChoiceChips(listOf("dark" to "ডার্ক", "light" to "লাইট"), if (state.dark) "dark" else "light") {
                vm.saveProfile(row("theme" to it))
            }
            Text("অ্যাকসেন্ট কালার", fontSize = 12.sp)
            ChoiceChips(AccentColor.entries.map { it.key to it.label }, state.accent.key) {
                vm.saveProfile(row("theme_color" to it))
            }
        }
        AppCard {
            Text("কারেন্সি", fontWeight = FontWeight.Bold)
            ChoiceChips(listOf("BDT" to "৳ BDT", "USD" to "$ USD", "EUR" to "€ EUR", "GBP" to "£ GBP"), state.currency) {
                vm.saveProfile(row("currency" to it))
            }
        }
        AppCard {
            SwitchRow("প্রাইভেসি মোড (টাকার পরিমাণ লুকানো)", state.privacy) { vm.saveProfile(row("is_privacy_mode" to it)) }
        }
        AppCard {
            Text("অ্যাকাউন্ট", fontWeight = FontWeight.Bold)
            Text(state.profile?.str("email").orEmpty().ifEmpty { "ইমেইল পাওয়া যায়নি" }, fontSize = 12.sp)
            OutlinedButton(onClick = { vm.signOut() }) { Text("লগআউট") }
        }
    }
}
