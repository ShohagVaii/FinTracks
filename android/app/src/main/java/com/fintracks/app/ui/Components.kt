package com.fintracks.app.ui

import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.Work
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Sms
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Icon as M3Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Work
import androidx.compose.foundation.border
import androidx.compose.foundation.verticalScroll

/** Glass-style card, like the web app's `.glass` class. */
@Composable
@OptIn(ExperimentalFoundationApi::class)
fun AppCard(onClick: (() -> Unit)? = null, borderColor: Color = Color.White.copy(alpha = 0.08f), onLongClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    val border = BorderStroke(1.dp, borderColor)
    val inner: @Composable ColumnScope.() -> Unit = {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp), content = content)
    }
    if (onClick != null || onLongClick != null) {
        Card(modifier = Modifier.fillMaxWidth().combinedClickable(onClick = onClick ?: {}, onLongClick = onLongClick), colors = colors, border = border, shape = RoundedCornerShape(24.dp)) { inner() }
    } else {
        Card(modifier = Modifier.fillMaxWidth(), colors = colors, border = border, shape = RoundedCornerShape(24.dp)) { inner() }
    }
}

/** Gradient "SritiSetu" heading, like the web header. */
@Composable
fun GradientText(text: String, size: Int = 20, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier,
        style = TextStyle(
            brush = Brush.horizontalGradient(listOf(Sky400, Blue600)),
            fontSize = size.sp,
            fontWeight = FontWeight.Bold
        )
    )
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier = modifier.padding(top = 4.dp), fontWeight = FontWeight.Bold, fontSize = 16.sp)
}

@Composable
fun ChoiceChips(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEach { (key, label) ->
            FilterChip(
                selected = key == selected,
                onClick = { onSelect(key) },
                label = { Text(label) }
            )
        }
    }
}

@Composable
fun EmptyHint(text: String) {
    Text(
        text,
        modifier = Modifier.fillMaxWidth().padding(24.dp),
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/** Thin progress bar used for budgets and goals. */
@Composable
fun ProgressBar(fraction: Float, color: Color, modifier: Modifier = Modifier) {
    val f = fraction.coerceIn(0f, 1f)
    Box(
        modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(Color.White.copy(alpha = 0.08f))
    ) {
        Box(Modifier.fillMaxWidth(f).height(8.dp).background(color))
    }
}

/** Simple area-style line chart drawn on a Canvas. */
@Composable
fun LineChart(values: List<Double>, color: Color, modifier: Modifier = Modifier) {
    androidx.compose.foundation.Canvas(modifier.fillMaxWidth().height(160.dp)) {
        if (values.isEmpty()) return@Canvas
        val max = (values.maxOrNull() ?: 0.0).coerceAtLeast(1.0)
        val stepX = if (values.size > 1) size.width / (values.size - 1) else size.width
        val points = values.mapIndexed { i, v ->
            Offset(i * stepX, size.height - (v / max * size.height * 0.9f).toFloat() - 4f)
        }
        val line = Path().apply {
            points.forEachIndexed { i, p -> if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) }
        }
        val fill = Path().apply {
            addPath(line)
            lineTo(points.last().x, size.height)
            lineTo(points.first().x, size.height)
            close()
        }
        drawPath(fill, Brush.verticalGradient(listOf(color.copy(alpha = 0.35f), Color.Transparent)))
        drawPath(line, color, style = Stroke(width = 4f, cap = StrokeCap.Round))
    }
}

@Composable
fun FormDialog(
    title: String,
    confirmEnabled: Boolean = true,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontWeight = FontWeight.Bold) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                content = content
            )
        },
        confirmButton = { TextButton(onClick = onConfirm, enabled = confirmEnabled) { Text("সেভ") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("বাতিল") } }
    )
}

/** Glass bottom bar with a blue halo, outlined icons and a raised centre "+" — matches the video's nav. */
@Composable
fun BottomBar(selected: String, onSelect: (String) -> Unit, onAdd: () -> Unit) {
    val items = listOf(
        "dashboard" to ("হোম" to Icons.Outlined.Home),
        "transactions" to ("লেনদেন" to Icons.AutoMirrored.Outlined.List),
        "shared" to ("শেয়ারড" to Icons.Outlined.Group),
        "tools" to ("টুলস" to Icons.Outlined.Work)
    )
    val barShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    Box(Modifier.fillMaxWidth().height(100.dp)) {
        // Blue halo above the bar.
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(100.dp)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Sky500.copy(alpha = 0.16f))))
        )
        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(68.dp)
                .clip(barShape)
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.96f))
                .border(1.dp, Color.White.copy(alpha = 0.08f), barShape)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceAround
        ) {
            items.take(2).forEach { (id, pair) -> NavItem(pair.first, pair.second, selected == id) { onSelect(id) } }
            Spacer(Modifier.width(72.dp))
            items.drop(2).forEach { (id, pair) -> NavItem(pair.first, pair.second, selected == id) { onSelect(id) } }
        }
        // Centre "+" sits on the bar's top edge with a dark ring and a sky glow.
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .size(68.dp)
                .clip(CircleShape)
                .background(Slate950)
                .padding(4.dp)
                .clip(CircleShape)
                .background(Brush.linearGradient(listOf(Sky400, Sky500, Blue600)))
                .clickable(onClick = onAdd),
            contentAlignment = Alignment.Center
        ) {
            M3Icon(Icons.Default.Add, contentDescription = "যোগ করুন", tint = Color.White, modifier = Modifier.size(32.dp))
        }
    }
}

@Composable
private fun NavItem(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, active: Boolean, onClick: () -> Unit) {
    val tint = if (active) Sky400 else Slate500
    Column(
        Modifier.clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        M3Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(24.dp))
        Text(label, fontSize = 11.sp, fontWeight = if (active) FontWeight.Bold else FontWeight.Medium, color = tint)
    }
}

/** Round AI chat button that floats above the bottom bar, like the video's chat bubble. */
@Composable
fun FloatingSmsButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(56.dp)
            .clip(CircleShape)
            .background(Slate950.copy(alpha = 0.9f))
            .border(2.dp, Sky500.copy(alpha = 0.7f), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        M3Icon(Icons.Outlined.Sms, contentDescription = "SMART SMS PARSER", tint = Sky400, modifier = Modifier.size(24.dp))
    }
}
