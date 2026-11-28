package com.fintracks.app.ui

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * True once supabase/migrations/20261128000100_user_settings.sql is applied live. Until then the overrides
 * stay on this phone only, exactly as before, and no sync request is made.
 */
internal const val SETTINGS_SYNC_LIVE = false

/** One built-in category as it is shown now: [orig] is the built-in name (the key), [name] and [icon] are the current values. */
data class DefRow(val type: String, val orig: String, val name: String, val icon: String)

/**
 * Per-phone edits to the built-in categories and to the payment methods. A built-in category can be renamed,
 * re-iconed or hidden (deleted); payment methods are an editable list. Custom categories stay in the synced
 * "categories" table. Loaded once from [init] in the Application class. With the settings sync live, the whole
 * state is also kept on the server as one value (see [snapshot] and [applyRemote]).
 */
object CatStore {
    private val DEFAULT_METHODS = listOf(
        "Cash" to "ক্যাশ", "Bkash" to "বিকাশ", "Nagad" to "নগদ", "Rocket" to "রকেট", "Card" to "কার্ড", "Bank" to "ব্যাংক"
    )
    private const val PREFS = "fintrack_catstore"
    private const val K_DEF = "defaults"
    private const val K_METHODS = "methods"
    private const val K_STAMP = "stamp"

    private var prefs: SharedPreferences? = null
    /** "Type|orig" -> (current name, icon, hidden). */
    private val overrides = mutableMapOf<String, Triple<String, String, Boolean>>()
    private var methods: List<Pair<String, String>> = DEFAULT_METHODS
    /** Time of the last change, epoch millis (0 = never changed). Last write wins between phones. */
    private var localStamp = 0L

    /** Set by the app: called after every local change, so it can be pushed to the server. */
    var onChange: (() -> Unit)? = null

    fun init(ctx: Context) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        overrides.clear()
        p.getStringSet(K_DEF, emptySet()).orEmpty().forEach { line ->
            val f = line.split("|")
            if (f.size == 5) overrides["${f[0]}|${f[1]}"] = Triple(f[2], f[3], f[4] == "1")
        }
        methods = p.getString(K_METHODS, null)?.let { saved ->
            saved.split("\n").mapNotNull { l -> l.split("\t").takeIf { it.size == 2 }?.let { it[0] to it[1] } }
        } ?: DEFAULT_METHODS
        localStamp = p.getLong(K_STAMP, 0L)
    }

    private fun base(type: String) = if (type == "Income") INCOME_CATS else EXPENSE_CATS

    /** Built-in categories of [type] that are not hidden, with the current name and icon. */
    fun defaultRows(type: String): List<DefRow> = base(type).mapNotNull { c ->
        val o = overrides["$type|${c.name}"]
        when {
            o == null -> DefRow(type, c.name, c.name, c.icon)
            o.third -> null
            else -> DefRow(type, c.name, o.first, o.second)
        }
    }

    fun overrideDefault(type: String, orig: String, name: String, icon: String) {
        overrides["$type|$orig"] = Triple(clean(name), clean(icon).ifEmpty { "📦" }, false)
        persistDefaults()
    }

    fun hideDefault(type: String, orig: String) {
        val cur = defaultRows(type).firstOrNull { it.orig == orig }
        overrides["$type|$orig"] = Triple(cur?.name ?: orig, cur?.icon ?: "📦", true)
        persistDefaults()
    }

    /** Payment methods as (stored key, label). The key is what old transactions keep as their payment_mode. */
    fun paymentMethods(): List<Pair<String, String>> = methods

    fun setPaymentMethods(list: List<Pair<String, String>>) {
        methods = list
        changed()
    }

    fun clean(s: String): String = s.replace('|', '/').replace('\t', ' ').replace('\n', ' ').trim()

    /** Time of the last change (0 = never). */
    fun stampMs(): Long = localStamp

    /** The whole override state as one JSON text: the value synced as key catstore_v1. */
    fun snapshot(): String {
        val defs = JSONArray()
        overrides.forEach { (k, v) -> defs.put(JSONArray(listOf(k, v.first, v.second, if (v.third) "1" else "0"))) }
        val ms = JSONArray()
        methods.forEach { (k, l) -> ms.put(JSONArray(listOf(k, l))) }
        return JSONObject().put("defaults", defs).put("methods", ms).toString()
    }

    /** Takes the server copy as the current state. It is not a local change, so it is not pushed back. */
    fun applyRemote(json: String, remoteStamp: Long) {
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return
        val newOverrides = mutableMapOf<String, Triple<String, String, Boolean>>()
        o.optJSONArray("defaults")?.let { arr ->
            for (i in 0 until arr.length()) {
                val a = arr.getJSONArray(i)
                newOverrides[a.getString(0)] = Triple(a.getString(1), a.getString(2), a.getString(3) == "1")
            }
        }
        val newMethods = o.optJSONArray("methods")?.let { arr ->
            (0 until arr.length()).map { i -> arr.getJSONArray(i).let { it.getString(0) to it.getString(1) } }
        } ?: DEFAULT_METHODS
        overrides.clear(); overrides.putAll(newOverrides)
        methods = newMethods
        localStamp = remoteStamp
        saveAll()
    }

    private fun changed() {
        localStamp = System.currentTimeMillis()
        saveAll()
        onChange?.invoke()
    }

    private fun persistDefaults() = changed()

    private fun saveAll() {
        prefs?.edit()
            ?.putStringSet(K_DEF, overrides.map { (k, v) -> "$k|${v.first}|${v.second}|${if (v.third) "1" else "0"}" }.toSet())
            ?.putString(K_METHODS, methods.joinToString("\n") { "${it.first}\t${it.second}" })
            ?.putLong(K_STAMP, localStamp)
            ?.apply()
    }
}
