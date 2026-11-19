package com.fintracks.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun SettingsScreen(state: UiState, vm: AppViewModel) {
    val p = state.profile
    var name by remember(p?.name) { mutableStateOf(p?.name.orEmpty()) }
    val isDark = p?.theme != "light"

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("সেটিংস", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

        AppCard {
            Text("প্রোফাইল", fontWeight = FontWeight.Bold)
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("নাম") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                enabled = name.isNotBlank() && name.trim() != p?.name,
                onClick = { vm.updateProfile(name = name.trim()) }
            ) { Text("নাম সেভ করুন") }
        }

        AppCard {
            Text("থিম", fontWeight = FontWeight.Bold)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("ডার্ক মোড", modifier = Modifier.weight(1f))
                Switch(
                    checked = isDark,
                    onCheckedChange = { vm.updateProfile(theme = if (it) "dark" else "light") }
                )
            }
            Text("অ্যাকসেন্ট রং", color = MaterialTheme.colorScheme.onSurfaceVariant)
            ChoiceChips(
                options = AccentColor.entries.map { it.key to it.label },
                selected = p?.themeColor ?: "sky",
                onSelect = { vm.updateProfile(themeColor = it) }
            )
        }

        AppCard {
            Text("গোপনীয়তা", fontWeight = FontWeight.Bold)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("প্রাইভেসি মোড")
                    Text(
                        "টাকার পরিমাণ লুকানো থাকবে",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = p?.isPrivacyMode == true,
                    onCheckedChange = { vm.updateProfile(privacy = it) }
                )
            }
        }

        AppCard {
            Text("অ্যাকাউন্ট", fontWeight = FontWeight.Bold)
            Text(
                "ডেটা Supabase-এ নিরাপদে সংরক্ষিত। শুধু আপনার অ্যাকাউন্টে দেখা যায়।",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            OutlinedButton(onClick = { vm.signOut() }, enabled = !state.busy) { Text("সাইন আউট") }
        }

        Text(
            "FinTracks v1.0.0",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
