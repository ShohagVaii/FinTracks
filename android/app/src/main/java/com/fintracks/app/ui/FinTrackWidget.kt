package com.fintracks.app.ui

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.fintracks.app.R
import com.fintracks.app.data.bool
import com.fintracks.app.data.num
import com.fintracks.app.data.str
import kotlinx.serialization.json.JsonObject

/** Home-screen widget. The app stores the latest numbers in SharedPreferences after every load. */
class FinTrackWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { updateOne(context, manager, it) }
    }

    companion object {
        private const val PREFS = "fintrack_widget"

        private fun updateOne(ctx: Context, mgr: AppWidgetManager, id: Int) {
            val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val views = RemoteViews(ctx.packageName, R.layout.widget_balance)
            views.setTextViewText(R.id.widget_balance, p.getString("balance", "৳ 0") ?: "৳ 0")
            views.setTextViewText(R.id.widget_expense, p.getString("expense", tr("এই মাসের খরচ: ৳ 0", "This month's expense: ৳ 0")) ?: "")
            val launch = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)
            if (launch != null) {
                views.setOnClickPendingIntent(
                    R.id.widget_title,
                    PendingIntent.getActivity(ctx, 0, launch, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                )
            }
            mgr.updateAppWidget(id, views)
        }

        /** Writes fresh numbers and redraws every placed widget. */
        fun refresh(ctx: Context, tables: Map<String, List<JsonObject>>, privacy: Boolean) {
            val txs = tables["transactions"].orEmpty()
            val accounts = tables["accounts"].orEmpty()
            val month = monthPrefix()
            val total = accounts.sumOf { accountBalance(it, txs) } +
                (if (accounts.isEmpty()) txs.sumOf { if (it.str("type") == "Income") it.num("amount") else if (it.str("type") == "Expense") -it.num("amount") else 0.0 } else 0.0)
            val expense = txs.filter { it.str("type") == "Expense" && dateOf(it, "tx_date").startsWith(month) }.sumOf { it.num("amount") }
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("balance", taka(total, privacy))
                .putString("expense", tr("এই মাসের খরচ: ${taka(expense, privacy)}", "This month's expense: ${taka(expense, privacy)}"))
                .apply()
            val mgr = AppWidgetManager.getInstance(ctx)
            mgr.getAppWidgetIds(ComponentName(ctx, FinTrackWidgetProvider::class.java)).forEach { updateOne(ctx, mgr, it) }
        }
    }
}
