package com.fintracks.app.ui
import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.DataUsage
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/** Period and network filter state of one screen. Kept per screen so a detail page does not change the main page. */
internal class PeriodFilterState(today: LocalDate) {
    var period by mutableStateOf(DuPeriod.MONTH)
    var anchor by mutableStateOf(today)
    var customFrom by mutableStateOf(today.withDayOfMonth(1))
    var customTo by mutableStateOf(today)
    var medium by mutableStateOf(DuMedium.BOTH)
    var showFrom by mutableStateOf(false)
    var showTo by mutableStateOf(false)

    val range: DuRange get() = duRangeFor(period, anchor, customFrom, customTo)

    fun move(dir: Int) {
        val next = duShift(period, range, dir)
        if (period == DuPeriod.CUSTOM) {
            customFrom = next.from
            customTo = next.to
        } else {
            anchor = next.to
        }
    }
}

/** Period tabs, custom date chips, ‹ › navigation and the network filter. */
@Composable
internal fun PeriodFilterBar(fs: PeriodFilterState, today: LocalDate) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val range = fs.range
    val canNext = duShift(fs.period, range, 1).from <= today

    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(50)).background(faint(0.05f)).padding(4.dp)) {
        listOf(
            DuPeriod.DAY to tr("আজ", "Today"),
            DuPeriod.WEEK to tr("সপ্তাহ", "Week"),
            DuPeriod.MONTH to tr("মাস", "Month"),
            DuPeriod.CUSTOM to tr("কাস্টম", "Custom")
        ).forEach { (p, label) ->
            val on = fs.period == p
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(50)).background(if (on) DuGreen else Color.Transparent)
                    .clickable {
                        fs.period = p
                        if (p == DuPeriod.CUSTOM) fs.showFrom = true
                    }.padding(vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(label, color = if (on) Color.White else muted, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1)
            }
        }
    }

    if (fs.period == DuPeriod.CUSTOM) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DuChip(null, tr("শুরু ", "From ") + duRangeLabel(DuPeriod.DAY, DuRange(fs.customFrom, fs.customFrom), AppPrefs.english), false, Modifier.weight(1f)) { fs.showFrom = true }
            DuChip(null, tr("শেষ ", "To ") + duRangeLabel(DuPeriod.DAY, DuRange(fs.customTo, fs.customTo), AppPrefs.english), false, Modifier.weight(1f)) { fs.showTo = true }
        }
    }

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        NavArrow(Icons.Default.ChevronLeft, enabled = true) { fs.move(-1) }
        Text(duRangeLabel(fs.period, range, AppPrefs.english), fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1)
        NavArrow(Icons.Default.ChevronRight, enabled = canNext) { fs.move(1) }
    }

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        DuChip(Icons.Default.DataUsage, tr("ডাটা ও ওয়াইফাই", "Data and Wi-Fi"), fs.medium == DuMedium.BOTH, Modifier.weight(1f)) { fs.medium = DuMedium.BOTH }
        DuChip(Icons.Default.Wifi, "Wi-Fi", fs.medium == DuMedium.WIFI, Modifier.weight(1f)) { fs.medium = DuMedium.WIFI }
        DuChip(Icons.Default.CellTower, tr("মোবাইল ডাটা", "Mobile"), fs.medium == DuMedium.MOBILE, Modifier.weight(1f)) { fs.medium = DuMedium.MOBILE }
    }

    if (fs.showFrom) {
        DatePickerSheet(fs.customFrom, tr("শুরুর তারিখ", "Start date"), onDismiss = { fs.showFrom = false }, onPick = {
            fs.customFrom = it
            fs.showFrom = false
            fs.showTo = true
        })
    }
    if (fs.showTo) {
        DatePickerSheet(fs.customTo, tr("শেষ তারিখ", "End date"), onDismiss = { fs.showTo = false }, onPick = {
            fs.customTo = it
            fs.showTo = false
        })
    }
}
