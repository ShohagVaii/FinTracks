package com.fintracks.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Safe arithmetic evaluator (no eval, no scripting): numbers, + − × ÷, %, unary minus, parentheses.
 * Returns null for anything invalid, including division by zero.
 */
object CalcParser {
    fun eval(input: String): Double? {
        val src = input.replace('×', '*').replace('÷', '/').replace('−', '-').replace(',', '.').replace(" ", "")
        if (src.isEmpty()) return null
        return runCatching {
            val p = Parser(src)
            val v = p.expr()
            if (p.atEnd() && v.isFinite()) v else null
        }.getOrNull()
    }

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
            if (!atEnd() && s[i] == '(') {
                i++
                val v = expr()
                require(!atEnd() && s[i] == ')') { "missing )" }
                i++
                return v
            }
            val start = i
            while (!atEnd() && (s[i].isDigit() || s[i] == '.')) i++
            require(i > start) { "number expected" }
            return s.substring(start, i).toDouble()
        }
    }
}

/** Calculator popup. "ব্যবহার" returns the result to the caller (for example, the amount field). */
@Composable
fun CalculatorDialog(onUse: (Double) -> Unit, onDismiss: () -> Unit) {
    var expr by remember { mutableStateOf("") }
    val live = CalcParser.eval(expr)
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
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("ক্যালকুলেটর", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (expr.isEmpty()) "0" else expr, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.fillMaxWidth())
                Text(
                    live?.let { "= " + fmtNum(it) } ?: "সম্পূর্ণ বা সঠিক নয়",
                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                keys.forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { k ->
                            Box(
                                Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
                                    .clickable { press(k) },
                                contentAlignment = Alignment.Center
                            ) { Text(k, fontSize = 18.sp, fontWeight = FontWeight.SemiBold) }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = live != null && live > 0, onClick = {
                onUse(live!!)
            }) { Text("ব্যবহার") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("বাতিল") } }
    )
}
