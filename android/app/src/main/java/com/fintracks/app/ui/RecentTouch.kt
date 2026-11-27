package com.fintracks.app.ui

import android.content.Context

/**
 * Local-only "last saved or edited" time for each transaction. It is never sent to the server
 * (the server has no such column); it only orders the home "recent" list.
 */
internal object RecentTouch {
    private const val KEY = "tx_touch"
    private const val KEEP = 500

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("fintracks", Context.MODE_PRIVATE)

    fun touch(ctx: Context, id: String) {
        if (id.isEmpty()) return
        val m = all(ctx).toMutableMap()
        m[id] = System.currentTimeMillis()
        val kept = m.entries.sortedByDescending { it.value }.take(KEEP)
        prefs(ctx).edit().putString(KEY, kept.joinToString("\n") { "${it.key}\t${it.value}" }).apply()
    }

    fun all(ctx: Context): Map<String, Long> =
        prefs(ctx).getString(KEY, "").orEmpty().lines().mapNotNull { line ->
            val parts = line.split('\t')
            if (parts.size == 2) parts[1].toLongOrNull()?.let { parts[0] to it } else null
        }.toMap()
}
