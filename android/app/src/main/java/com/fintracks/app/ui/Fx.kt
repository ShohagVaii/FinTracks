package com.fintracks.app.ui

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/** One saved set of rates. [rates] are units per 1 USD. [updatedUtc] is the provider's own timestamp. */
data class FxSnapshot(val rates: Map<String, Double>, val updatedUtc: String, val fetchedAt: Long)

/**
 * Live rates from the ExchangeRate-API open endpoint (no key, updated about once a day).
 * The last good answer is kept in SharedPreferences so the converter works offline and shows its date.
 */
object FxStore {
    const val URL = "https://open.er-api.com/v6/latest/USD"
    val CURRENCIES = listOf("BDT", "USD", "EUR", "GBP")
    /** After this age the converter tries to refresh when it opens. */
    const val MAX_AGE_MS = 12L * 60 * 60 * 1000
    private const val PREFS = "fintrack_fx"
    private const val KEY = "snapshot"

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    fun load(ctx: Context): FxSnapshot? {
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return null
        return runCatching {
            val o = JSONObject(raw)
            val r = o.getJSONObject("rates")
            FxSnapshot(CURRENCIES.associateWith { r.getDouble(it) }, o.getString("updated"), o.getLong("fetched"))
        }.getOrNull()
    }

    private fun save(ctx: Context, s: FxSnapshot) {
        val r = JSONObject()
        s.rates.forEach { (k, v) -> r.put(k, v) }
        val o = JSONObject().put("rates", r).put("updated", s.updatedUtc).put("fetched", s.fetchedAt)
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, o.toString()).apply()
    }

    /** Blocking network call: run it on Dispatchers.IO. Errors come back as a failed Result, never a crash. */
    fun fetch(ctx: Context): Result<FxSnapshot> = runCatching {
        val req = Request.Builder().url(URL).build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code}")
            val o = JSONObject(resp.body?.string().orEmpty())
            if (o.optString("result") != "success") error("API result: ${o.optString("result")}")
            val r = o.getJSONObject("rates")
            val snap = FxSnapshot(
                CURRENCIES.associateWith { r.getDouble(it) },
                o.optString("time_last_update_utc"),
                System.currentTimeMillis()
            )
            save(ctx, snap)
            snap
        }
    }
}

/** Amount in [from] converted to [to]; null when a currency is missing from the rates. */
fun fxConvert(amount: Double, from: String, to: String, rates: Map<String, Double>): Double? {
    val f = rates[from] ?: return null
    val t = rates[to] ?: return null
    return amount / f * t
}

/** The provider timestamp in this device's time zone, or null if it cannot be read. */
fun fxUpdatedLocal(utc: String): LocalDateTime? = runCatching {
    ZonedDateTime.parse(utc, DateTimeFormatter.RFC_1123_DATE_TIME)
        .withZoneSameInstant(ZoneId.systemDefault())
        .toLocalDateTime()
}.getOrNull()
