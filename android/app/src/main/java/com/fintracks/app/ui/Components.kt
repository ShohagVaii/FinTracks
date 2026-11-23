package com.fintracks.app.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlin.math.roundToInt

import androidx.compose.ui.graphics.luminance
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
fun AppCard(onClick: (() -> Unit)? = null, borderColor: Color? = null, onLongClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    // Dark mode uses the web glass card (white 3% fill, white 10% border); light mode keeps its surface.
    val darkNow = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val colors = CardDefaults.cardColors(containerColor = if (darkNow) GlassFill else MaterialTheme.colorScheme.surface)
    val border = BorderStroke(1.dp, borderColor ?: if (darkNow) GlassBorder else Color.Black.copy(alpha = 0.08f))
    val inner: @Composable ColumnScope.() -> Unit = {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp), content = content)
    }
    if (onClick != null || onLongClick != null) {
        Card(modifier = Modifier.fillMaxWidth().pressScale(enabled = onClick != null || onLongClick != null).combinedClickable(onClick = onClick ?: {}, onLongClick = onLongClick), colors = colors, border = border, shape = RoundedCornerShape(24.dp)) { inner() }
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
    Column(
        Modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        M3Icon(
            Icons.Outlined.Inbox,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(36.dp).floatingBob()
        )
        Spacer(Modifier.height(8.dp))
        Text(text, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Thin progress bar used for budgets and goals. */
@Composable
fun ProgressBar(fraction: Float, color: Color, modifier: Modifier = Modifier) {
    val f by animateFloatAsState(fraction.coerceIn(0f, 1f), tween(400, easing = FastOutSlowInEasing), label = "progressBar")
    Box(
        modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(faint(0.08f))
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

/**
 * Two series on one canvas with one shared scale (income green, expense red in the home trend).
 * Both lists must have the same length; each point is placed by its index.
 */
@Composable
fun DualLineChart(first: List<Double>, firstColor: Color, second: List<Double>, secondColor: Color, modifier: Modifier = Modifier) {
    androidx.compose.foundation.Canvas(modifier.fillMaxWidth().height(160.dp)) {
        val n = maxOf(first.size, second.size)
        if (n == 0) return@Canvas
        val max = (maxOf(first.maxOrNull() ?: 0.0, second.maxOrNull() ?: 0.0)).coerceAtLeast(1.0)
        val stepX = if (n > 1) size.width / (n - 1) else size.width
        fun drawSeries(values: List<Double>, color: Color, withFill: Boolean) {
            if (values.isEmpty()) return
            val points = values.mapIndexed { i, v ->
                Offset(i * stepX, size.height - (v / max * size.height * 0.9f).toFloat() - 4f)
            }
            val line = Path().apply {
                points.forEachIndexed { i, p -> if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) }
            }
            if (withFill) {
                val fill = Path().apply {
                    addPath(line)
                    lineTo(points.last().x, size.height)
                    lineTo(points.first().x, size.height)
                    close()
                }
                drawPath(fill, Brush.verticalGradient(listOf(color.copy(alpha = 0.22f), Color.Transparent)))
            }
            drawPath(line, color, style = Stroke(width = 4f, cap = StrokeCap.Round))
        }
        drawSeries(second, secondColor, withFill = false)
        drawSeries(first, firstColor, withFill = true)
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
        confirmButton = { TextButton(modifier = Modifier.pressScale(), onClick = onConfirm, enabled = confirmEnabled) { Text(tr("সেভ", "Save")) } },
        dismissButton = { TextButton(modifier = Modifier.pressScale(), onClick = onDismiss) { Text(tr("বাতিল", "Cancel")) } }
    )
}

/** Glass bottom bar with a blue halo, outlined icons and a raised centre "+". The selected tab's pill slides, and its icon bounces. */
@Composable
private val BOTTOM_GAP = 84.dp

fun BottomBar(selected: String, onSelect: (String) -> Unit, onAdd: () -> Unit) {
    val items = listOf(
        "dashboard" to (tr("হোম", "Home") to Icons.Outlined.Home),
        "transactions" to (tr("লেনদেন", "Transactions") to Icons.AutoMirrored.Outlined.List),
        "shared" to (tr("শেয়ারড", "Shared") to Icons.Outlined.Group),
        "tools" to (tr("টুলস", "Tools") to Icons.Outlined.Work)
    )
    val barShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    val reduce = rememberReduceMotion()
    val selIndex = items.indexOfFirst { it.first == selected }.coerceAtLeast(0)
    val slotIndex by animateFloatAsState(
        selIndex.toFloat(),
        if (reduce) tween(0) else spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow),
        label = "navSlide"
    )
    var barWidth by remember { mutableStateOf(0) }
    Box(Modifier.fillMaxWidth().height(100.dp)) {
        // Blue halo above the bar.
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(100.dp)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Sky500.copy(alpha = 0.16f))))
        )
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(68.dp)
                .clip(barShape)
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.96f))
                .border(1.dp, faint(0.08f), barShape)
                .onSizeChanged { barWidth = it.width }
        ) {
            // Sliding pill behind the selected tab (four equal slots; the "+" sits over the centre).
            if (barWidth > 0) {
                Box(
                    Modifier
                        .offset {
                            val gap = BOTTOM_GAP.toPx()
                            val slotW = (barWidth - 16.dp.toPx() - gap) / 4f
                            val afterGap = (slotIndex - 1f).coerceIn(0f, 1f) * gap
                            IntOffset(
                                (8.dp.toPx() + slotIndex * slotW + afterGap + slotW / 2f - 26.dp.toPx()).roundToInt(),
                                8.dp.toPx().roundToInt()
                            )
                        }
                        .size(width = 52.dp, height = 52.dp)
                        .clip(RoundedCornerShape(26.dp))
                        .background(Sky500.copy(alpha = 0.16f))
                )
            }
            Row(
                Modifier.fillMaxSize().padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                items.forEachIndexed { i, (id, pair) ->
                    // Gap between Transactions and Shared, where the centre "+" sits.
                    if (i == 2) Spacer(Modifier.width(BOTTOM_GAP))
                    NavItem(pair.first, pair.second, selected == id, Modifier.weight(1f)) { onSelect(id) }
                }
            }
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
                .pressScale()
                .clickable(onClick = onAdd),
            contentAlignment = Alignment.Center
        ) {
            M3Icon(Icons.Default.Add, contentDescription = tr("যোগ করুন", "Add"), tint = Color.White, modifier = Modifier.size(32.dp))
        }
    }
}

@Composable
private fun NavItem(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, active: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val tint = if (active) Sky400 else Slate500
    val reduce = rememberReduceMotion()
    val bounce by animateFloatAsState(
        if (active || reduce) 1f else 0.92f,
        if (reduce) tween(0) else spring(dampingRatio = 0.4f, stiffness = Spring.StiffnessMedium),
        label = "navBounce"
    )
    Column(
        modifier
            .pressScale()
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        M3Icon(
            icon,
            contentDescription = label,
            tint = tint,
            modifier = Modifier.size(24.dp).graphicsLayer { scaleX = bounce; scaleY = bounce }
        )
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
            .pressScale()
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        M3Icon(Icons.Outlined.Sms, contentDescription = "SMART SMS PARSER", tint = Sky400, modifier = Modifier.size(24.dp))
    }
}

/** The catch-all category. Picking it, or a custom name typed in the box, is saved as the category itself. */
const val OTHER_CAT = "অন্যান্য"

/**
 * Text box that opens when "অন্যান্য" is picked. Whatever is typed becomes the category name.
 * Left empty, the category stays "অন্যান্য". The caller decides when to show it.
 */
@Composable
fun OtherCategoryField(value: String, onChange: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf(if (value == OTHER_CAT) "" else value) }
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it.take(40)
            onChange(text.trim().ifEmpty { OTHER_CAT })
        },
        label = { Text(tr("অন্যান্য: নিজের খাতের নাম (ঐচ্ছিক)", "Other: your own category name (optional)")) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
}
