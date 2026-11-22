package com.fintracks.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import android.graphics.drawable.ColorDrawable
import android.os.Build
import kotlin.math.roundToInt

/** What the calculator makes of the typed text. */
sealed class CalcResult {
    data class Ok(val value: Double) : CalcResult()
    /** The text is not finished yet, for example "12+" or "(3". This is not an error. */
    object Incomplete : CalcResult()
    /** A real mistake in the calculation, such as dividing by zero. */
    object Invalid : CalcResult()
}

/**
 * Safe arithmetic evaluator (no eval, no scripting): numbers, + − × ÷, %, unary minus, parentheses.
 * Unfinished text is Incomplete; only a real mistake is Invalid.
 */
object CalcParser {
    /** The value of a finished expression, or null. */
    fun eval(input: String): Double? = (evaluate(input) as? CalcResult.Ok)?.value

    fun evaluate(input: String): CalcResult {
        val src = normalize(input)
        if (src.isEmpty()) return CalcResult.Incomplete
        return try {
            val p = Parser(src)
            val v = p.expr()
            when {
                !p.atEnd() -> CalcResult.Invalid
                v.isFinite() -> CalcResult.Ok(v)
                else -> CalcResult.Invalid
            }
        } catch (e: Unfinished) {
            CalcResult.Incomplete
        } catch (e: Exception) {
            CalcResult.Invalid
        }
    }

    /** What to show while typing: the result of the whole text, or the last finished value ("12+" shows 12). */
    data class Shown(val value: Double?, val lastComplete: Boolean, val invalid: Boolean)

    fun show(input: String): Shown {
        when (val r = evaluate(input)) {
            is CalcResult.Ok -> return Shown(r.value, false, false)
            CalcResult.Invalid -> return Shown(null, false, true)
            CalcResult.Incomplete -> Unit
        }
        val src = normalize(input)
        for (k in src.length - 1 downTo 1) {
            var candidate = src.take(k)
            val open = candidate.count { it == '(' } - candidate.count { it == ')' }
            if (open > 0) candidate += ")".repeat(open)
            when (val r = evaluate(candidate)) {
                is CalcResult.Ok -> return Shown(r.value, true, false)
                CalcResult.Invalid -> return Shown(null, false, true)
                CalcResult.Incomplete -> Unit
            }
        }
        return Shown(null, false, false)
    }

    private fun normalize(input: String): String =
        input.replace('×', '*').replace('÷', '/').replace('−', '-').replace(',', '.').replace(" ", "")

    /** Thrown when the text ends where a number or ')' is still needed. */
    private class Unfinished : RuntimeException()

    private class Parser(private val s: String) {
        private var i = 0
        fun atEnd() = i >= s.length

        fun expr(): Double {
            var v = term()
            while (!atEnd() && (s[i] == '+' || s[i] == '-')) {
                val op = s[i++]
                val r = term()
                v = if (op == '+') v + r else v - r
            }
            return v
        }

        private fun term(): Double {
            var v = factor()
            while (!atEnd() && (s[i] == '*' || s[i] == '/')) {
                val op = s[i++]
                val r = factor()
                v = if (op == '*') v * r else {
                    require(r != 0.0) { "division by zero" }
                    v / r
                }
            }
            return v
        }

        private fun factor(): Double {
            if (!atEnd() && s[i] == '-') { i++; return -factor() }
            if (!atEnd() && s[i] == '+') { i++; return factor() }
            var v = primary()
            // postfix percent: 50% = 0.5
            while (!atEnd() && s[i] == '%') { i++; v /= 100.0 }
            return v
        }

        private fun primary(): Double {
            if (atEnd()) throw Unfinished()
            if (s[i] == '(') {
                i++
                val v = expr()
                if (atEnd()) throw Unfinished()
                require(s[i] == ')') { "missing )" }
                i++
                return v
            }
            val start = i
            while (!atEnd() && (s[i].isDigit() || s[i] == '.')) i++
            if (i == start) throw IllegalArgumentException("number expected")
            return s.substring(start, i).toDouble()
        }
    }
}

/** Calculator popup: a glass panel over a blurred backdrop. Drag the grip to move it inside the screen. "ব্যবহার" returns the shown value. */
@Composable
fun CalculatorDialog(onUse: (Double) -> Unit, onDismiss: () -> Unit) {
    var expr by remember { mutableStateOf("") }
    val shown = CalcParser.show(expr)
    val live = shown.value
    val keys = listOf(
        listOf("C", "(", ")", "÷"),
        listOf("7", "8", "9", "×"),
        listOf("4", "5", "6", "−"),
        listOf("1", "2", "3", "+"),
        listOf("0", ".", "%", "⌫")
    )
    fun press(k: String) {
        expr = when (k) {
            "C" -> ""
            "⌫" -> expr.dropLast(1)
            else -> expr + k
        }
    }
    // Position of the panel, measured from the centre of the screen, and the sizes used to keep it on screen.
    var offX by remember { mutableStateOf(0f) }
    var offY by remember { mutableStateOf(0f) }
    var boxW by remember { mutableStateOf(0) }
    var boxH by remember { mutableStateOf(0) }
    var panelW by remember { mutableStateOf(0) }
    var panelH by remember { mutableStateOf(0) }

    val view = LocalView.current
    // Blur what is behind the popup (Android 12+) and keep the window itself transparent.
    SideEffect {
        (view.parent as? DialogWindowProvider)?.window?.let { w ->
            w.setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
            w.setDimAmount(0.25f)
            if (Build.VERSION.SDK_INT >= 31) {
                w.setBackgroundBlurRadius(48)
            }
        }
    }
    // Android 12+ blurs the window behind the popup, so a lighter tint shows the blur.
    // Older devices cannot blur, so the panel gets a darker tint to stay readable.
    val glass = if (Build.VERSION.SDK_INT >= 31) Color(0x99101828) else Color(0xE6101828)
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            Modifier.fillMaxSize().padding(24.dp).onSizeChanged { boxW = it.width; boxH = it.height },
            contentAlignment = Alignment.Center
        ) {
            Column(
                Modifier
                    .offset { IntOffset(offX.roundToInt(), offY.roundToInt()) }
                    .onSizeChanged { panelW = it.width; panelH = it.height }
                    .width(340.dp)
                    .clip(RoundedCornerShape(28.dp))
                    .background(glass)
                    .border(1.dp, Color(0x40FFFFFF), RoundedCornerShape(28.dp))
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Grip: drag here. The panel stays inside the screen.
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(24.dp)
                        .pointerInput(Unit) {
                            detectDragGestures { change, drag ->
                                change.consume()
                                val maxX = ((boxW - panelW) / 2f).coerceAtLeast(0f)
                                val maxY = ((boxH - panelH) / 2f).coerceAtLeast(0f)
                                offX = (offX + drag.x).coerceIn(-maxX, maxX)
                                offY = (offY + drag.y).coerceIn(-maxY, maxY)
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Box(Modifier.width(44.dp).height(5.dp).background(Color(0x80FFFFFF), RoundedCornerShape(3.dp)))
                }
                Text(tr("ক্যালকুলেটর", "Calculator"), fontWeight = FontWeight.Bold, color = Color.White)
                Text(if (expr.isEmpty()) "0" else expr, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.fillMaxWidth())
                Text(
                    when {
                        expr.isEmpty() -> ""
                        shown.invalid -> tr("অবৈধ হিসাব (শূন্য দিয়ে ভাগ ইত্যাদি)", "Invalid calculation (e.g. divide by zero)")
                        live == null -> tr("লিখে যান…", "Keep typing…")
                        shown.lastComplete -> "= " + fmtNum(live) + tr("  (আগের সম্পূর্ণ মান)", "  (last complete value)")
                        else -> "= " + fmtNum(live)
                    },
                    fontSize = 13.sp, color = Color(0xCCFFFFFF)
                )
                keys.forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { k ->
                            Box(
                                Modifier
                                    .weight(1f)
                                    .height(52.dp)
                                    .background(Color(0x55FFFFFF), RoundedCornerShape(14.dp))
                                    .clickable { press(k) },
                                contentAlignment = Alignment.Center
                            ) { Text(k, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Color.White) }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text(tr("বাতিল", "Cancel"), color = Color.White) }
                    Spacer(Modifier.width(8.dp))
                    TextButton(enabled = live != null && live > 0, onClick = { onUse(live!!) }) { Text(tr("ব্যবহার", "Use")) }
                }
            }
        }
    }
}
