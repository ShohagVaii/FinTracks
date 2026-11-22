package com.fintracks.app.ui

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.time.LocalDate
import java.time.LocalTime
import java.util.concurrent.TimeUnit
import com.fintracks.app.data.NotifyStyle

/**
 * Daily "add today's transactions" reminder. A background job checks every 15 minutes (the shortest
 * WorkManager allows) and notifies once a day after the chosen time. Android may delay it in battery saver mode.
 */
object DailyReminder {
    private const val PREFS = "fintrack_daily"
    private const val CHANNEL = "daily_reminder"
    private const val WORK = "fintrack-daily-reminder"
    private const val NOTIFY_ID = 4401

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun enabled(ctx: Context): Boolean = prefs(ctx).getBoolean("enabled", false)

    /** "HH:mm", 24-hour. */
    fun time(ctx: Context): String = prefs(ctx).getString("time", "22:00") ?: "22:00"

    fun set(ctx: Context, on: Boolean, time: String) {
        prefs(ctx).edit().putBoolean("enabled", on).putString("time", time).apply()
        if (on) schedule(ctx) else cancel(ctx)
    }

    /** Called at app start, so the job survives an app update or reboot when it was on. */
    fun ensureScheduled(ctx: Context) {
        if (enabled(ctx)) schedule(ctx)
    }

    private fun schedule(ctx: Context) {
        val req = PeriodicWorkRequestBuilder<DailyReminderWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.UPDATE, req)
    }

    fun cancel(ctx: Context) {
        WorkManager.getInstance(ctx).cancelUniqueWork(WORK)
    }

    /** Returns true when the reminder is due now and has not fired today. Marks it fired when it returns true. */
    internal fun claimIfDue(ctx: Context, now: LocalTime = LocalTime.now(), today: LocalDate = LocalDate.now()): Boolean {
        val p = prefs(ctx)
        if (!p.getBoolean("enabled", false)) return false
        val at = runCatching { LocalTime.parse(time(ctx)) }.getOrDefault(LocalTime.of(22, 0))
        if (now.isBefore(at)) return false
        if (p.getString("fired_on", null) == today.toString()) return false
        p.edit().putString("fired_on", today.toString()).apply()
        return true
    }

    internal fun notify(ctx: Context) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, tr("দৈনিক রিমাইন্ডার", "Daily reminder"), NotificationManager.IMPORTANCE_DEFAULT))
        }
        val launch = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName) ?: return
        val pending = PendingIntent.getActivity(ctx, 0, launch, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(NotifyStyle.SMALL)
            .setLargeIcon(NotifyStyle.large(ctx))
            .setContentTitle(tr("FinTrack রিমাইন্ডার", "FinTrack reminder"))
            .setContentText(tr("আজকের আয় ও ব্যয় লিখে ফেলেছেন? এখনই যোগ করুন।", "Have you logged today's income and expenses? Add them now."))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        nm.notify(NOTIFY_ID, n)
    }
}

class DailyReminderWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        if (DailyReminder.claimIfDue(applicationContext)) DailyReminder.notify(applicationContext)
        return Result.success()
    }
}
