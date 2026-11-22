package com.fintracks.app.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Add
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color
import com.fintracks.app.data.num
import com.fintracks.app.data.str
import com.fintracks.app.data.strOrNull
import com.fintracks.app.data.id
import kotlinx.serialization.json.JsonObject

/** Text input used across forms. */
@Composable
fun TextIn(label: String, value: String, onChange: (String) -> Unit, number: Boolean = false, modifier: Modifier = Modifier.fillMaxWidth()) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        modifier = modifier,
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
            keyboardType = if (number) KeyboardType.Decimal else KeyboardType.Text
        )
    )
}

/** A label/value line used in summaries. */
@Composable
fun KeyValue(label: String, value: String, valueColor: Color = Color.Unspecified) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        Text(value, fontWeight = FontWeight.Bold, color = readableOn(valueColor), fontSize = 14.sp)
    }
}

@Composable
fun DeleteButton(onClick: () -> Unit) {
    IconButton(modifier = Modifier.pressScale(), onClick = onClick) { Icon(Icons.Default.Delete, contentDescription = tr("মুছুন", "Delete"), tint = com.fintracks.app.ui.Rose500) }
}

@Composable
fun AddButton(text: String, onClick: () -> Unit) {
    TextButton(modifier = Modifier.pressScale(), onClick = onClick) {
        Icon(Icons.Default.Add, contentDescription = null)
        Spacer(Modifier.width(4.dp))
        Text(text)
    }
}

@Composable
fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** Shared numeric/date helpers for rows. */
fun monthPrefix(): String = todayIso().substring(0, 7)
fun dateOf(r: JsonObject, key: String): String = r.str(key).take(10)
fun nonEmpty(s: String?): String? = s?.takeIf { it.isNotBlank() }
fun JsonObject.accountId(): String? = strOrNull("account_id")
fun JsonObject.toAccountId(): String? = strOrNull("to_account_id")
fun rowId(r: JsonObject): String = r.id()
