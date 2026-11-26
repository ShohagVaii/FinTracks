package com.fintracks.app.ui.design

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fintracks.app.ui.AnimatedRing
import com.fintracks.app.ui.Amber500
import com.fintracks.app.ui.Emerald500
import com.fintracks.app.ui.GlassBorder
import com.fintracks.app.ui.GlassFill
import com.fintracks.app.ui.Rose500
import com.fintracks.app.ui.Sky500
import com.fintracks.app.ui.StampText
import com.fintracks.app.ui.bnDigits
import com.fintracks.app.ui.faint
import com.fintracks.app.ui.floatingBob
import com.fintracks.app.ui.readableOn
import com.fintracks.app.ui.rememberReduceMotion
import com.fintracks.app.ui.tr
import kotlinx.coroutines.delay

/** Shared glass surface: fill and border from the theme tokens, optional click. */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(20.dp),
    padding: PaddingValues = PaddingValues(16.dp),
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier
            .clip(shape)
            .background(GlassFill)
            .border(1.dp, GlassBorder, shape)
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(padding),
        content = content
    )
}

/** Label and one value in a glass tile. */
@Composable
fun MetricTile(label: String, value: String, modifier: Modifier = Modifier, color: Color = Color.Unspecified) {
    GlassCard(modifier, padding = PaddingValues(12.dp)) {
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        Spacer(Modifier.height(4.dp))
        Text(value, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = color, maxLines = 1)
    }
}

/** Two or more tabs with one indicator that slides to the chosen tab (220 ms). */
@Composable
fun SegmentedTabs(
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val idx = options.indexOfFirst { it.first == selected }.coerceAtLeast(0)
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    BoxWithConstraints(
        modifier.fillMaxWidth().clip(RoundedCornerShape(50)).background(faint(0.06f)).padding(4.dp)
    ) {
        val cell = maxWidth / options.size.coerceAtLeast(1)
        val x by animateDpAsState(cell * idx, tween(220), label = "segX")
        Box(
            Modifier.offset(x = x).width(cell).height(36.dp)
                .clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.primary)
        )
        Row(Modifier.fillMaxWidth()) {
            options.forEachIndexed { i, option ->
                val on = i == idx
                Box(
                    Modifier.weight(1f).height(36.dp).clickable { onSelect(option.first) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        option.second, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1,
                        color = if (on) MaterialTheme.colorScheme.onPrimary else muted
                    )
                }
            }
        }
    }
}

// A function, not a val: the accent colours read the saved preference, so they must not load at class init.
private fun avatarPalette() = listOf(Sky500, Emerald500, Amber500, Rose500, Color(0xFF8B5CF6), Color(0xFF14B8A6))

/** Initial in a circle; colour from the name; optional ring that fills to [percent] (0..1). */
@Composable
fun AvatarCircle(name: String, modifier: Modifier = Modifier, size: Dp = 40.dp, percent: Float? = null) {
    val palette = avatarPalette()
    val base = palette[(name.hashCode() and Int.MAX_VALUE) % palette.size]
    val ring by animateFloatAsState((percent ?: 0f).coerceIn(0f, 1f), tween(600), label = "avatarRing")
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        if (percent != null) AnimatedRing(ring, base, size, 3.dp)
        Box(
            Modifier.size(size - 8.dp).clip(CircleShape).background(base.copy(alpha = 0.22f)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                name.trim().firstOrNull()?.uppercase() ?: "?",
                fontWeight = FontWeight.Bold, fontSize = (size.value * 0.4f).sp, color = base
            )
        }
    }
}

/** Overlapping avatars with a "+N" tail when there are more than [max]. */
@Composable
fun AvatarStack(names: List<String>, modifier: Modifier = Modifier, max: Int = 4, size: Dp = 32.dp) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(-size / 4)) {
        names.take(max).forEach { AvatarCircle(it, size = size) }
        if (names.size > max) {
            Box(
                Modifier.size(size).clip(CircleShape).background(faint(0.1f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "+" + bnDigits("${names.size - max}"), fontSize = (size.value * 0.35f).sp,
                    fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

/** Bar with zero in the middle: right of zero is emerald (owed to you), left is rose. [value] is clamped to ±[maxAbs]. */
@Composable
fun DivergingBar(value: Double, maxAbs: Double, modifier: Modifier = Modifier, height: Dp = 8.dp) {
    val frac = if (maxAbs > 0) (value / maxAbs).toFloat().coerceIn(-1f, 1f) else 0f
    val f by animateFloatAsState(frac, tween(400), label = "diverge")
    val track = faint(0.08f)
    val centre = faint(0.5f)
    Canvas(modifier.fillMaxWidth().height(height)) {
        val r = CornerRadius(size.height / 2)
        val half = size.width / 2
        val len = half * kotlin.math.abs(f)
        drawRoundRect(track, Offset(0f, 0f), size, r)
        if (f > 0f) drawRoundRect(Emerald500, Offset(half, 0f), Size(len, size.height), r)
        if (f < 0f) drawRoundRect(Rose500, Offset(half - len, 0f), Size(len, size.height), r)
        drawLine(centre, Offset(half, 0f), Offset(half, size.height), strokeWidth = 2f)
    }
}

/** Spend against a limit: emerald, then amber from 80 %, then rose at 100 % and above. */
@Composable
fun LimitBar(fraction: Float, modifier: Modifier = Modifier, height: Dp = 8.dp) {
    val f by animateFloatAsState(fraction.coerceIn(0f, 1f), tween(400), label = "limit")
    val color = when {
        fraction >= 1f -> Rose500
        fraction >= 0.8f -> Amber500
        else -> Emerald500
    }
    Box(
        modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(50)).background(faint(0.08f))
    ) {
        Box(Modifier.fillMaxWidth(f).fillMaxHeight().clip(RoundedCornerShape(50)).background(color))
    }
}

/** Small pill with a tinted background, text readable on it. */
@Composable
fun StatusChip(text: String, color: Color, modifier: Modifier = Modifier) {
    Text(
        text, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = readableOn(color), maxLines = 1,
        modifier = modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.18f))
            .padding(horizontal = 8.dp, vertical = 2.dp)
    )
}

/** Tilted stamp word (paid, overdue, ...). */
@Composable
fun StampLabel(text: String, color: Color, modifier: Modifier = Modifier) {
    StampText(text, color, modifier)
}

/** Quick amount chips in Bengali digits. */
@Composable
fun QuickAmountChips(
    onPick: (Int) -> Unit,
    modifier: Modifier = Modifier,
    amounts: List<Int> = listOf(100, 500, 1000, 5000)
) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        amounts.forEach { a ->
            Text(
                bnDigits("$a"), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(faint(0.06f))
                    .border(1.dp, faint(0.1f), RoundedCornerShape(50))
                    .clickable { onPick(a) }
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }
    }
}

/** Every form opens as a bottom sheet with a title, content and a footer (the save button). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FormSheet(
    title: String,
    onDismiss: () -> Unit,
    footer: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            content()
            footer()
        }
    }
}

/** Digit keys in Bengali plus a backspace key. */
@Composable
fun NumberPad(onKey: (String) -> Unit, onBackspace: () -> Unit, modifier: Modifier = Modifier) {
    val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", ".", "0", "⌫")
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        keys.chunked(3).forEach { rowKeys ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rowKeys.forEach { k ->
                    Box(
                        Modifier.weight(1f).height(48.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(faint(0.05f))
                            .border(1.dp, faint(0.08f), RoundedCornerShape(14.dp))
                            .clickable { if (k == "⌫") onBackspace() else onKey(k) },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            if (k == "⌫") k else bnDigits(k), fontSize = 20.sp,
                            fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    }
}

/** Floating button that opens a column of labelled actions. */
@Composable
fun SpeedDialFab(
    actions: List<Triple<String, ImageVector, () -> Unit>>,
    modifier: Modifier = Modifier
) {
    var open by remember { mutableStateOf(false) }
    val rot by animateFloatAsState(if (open) 45f else 0f, tween(220), label = "fabRot")
    Column(modifier, horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        AnimatedVisibility(visible = open) {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                actions.forEach { (label, icon, onClick) ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.clip(RoundedCornerShape(50))
                                .background(MaterialTheme.colorScheme.surface)
                                .border(1.dp, faint(0.1f), RoundedCornerShape(50))
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                        SmallFloatingActionButton(onClick = { open = false; onClick() }) {
                            Icon(icon, contentDescription = label)
                        }
                    }
                }
            }
        }
        FloatingActionButton(onClick = { open = !open }) {
            Icon(Icons.Default.Add, contentDescription = tr("যোগ করুন", "Add"), modifier = Modifier.rotate(rot))
        }
    }
}

/** Empty list: icon and one line. The icon bobs unless reduce-motion is on. */
@Composable
fun EmptyState(icon: ImageVector, text: String, modifier: Modifier = Modifier) {
    val reduced = rememberReduceMotion()
    Column(
        modifier.fillMaxWidth().padding(vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = (if (reduced) Modifier else Modifier.floatingBob()).size(40.dp)
        )
        Text(text, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Shown after a delete: undo for 5 seconds, then [onExpire] runs once. */
@Composable
fun UndoBar(visible: Boolean, message: String, onUndo: () -> Unit, onExpire: () -> Unit, modifier: Modifier = Modifier) {
    LaunchedEffect(visible) {
        if (visible) {
            delay(5000L)
            onExpire()
        }
    }
    AnimatedVisibility(visible = visible, modifier = modifier) {
        Row(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surface)
                .border(1.dp, faint(0.12f), RoundedCornerShape(16.dp))
                .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(message, fontSize = 14.sp, maxLines = 1, modifier = Modifier.weight(1f))
            TextButton(onClick = onUndo) {
                Text(tr("ফেরত আনুন", "Undo"), color = Sky500, fontWeight = FontWeight.Bold)
            }
        }
    }
}
