package com.fintracks.app.ui

import android.content.Context
import android.content.SharedPreferences

/** One built-in category as it is shown now: [orig] is the built-in name (the key), [name] and [icon] are the current values. */
data class DefRow(val type: String, val orig: String, val name: String, val icon: String)

/**
 * Per-phone edits to the built-in categories and to the payment methods. A built-in category can be renamed,
 * re-iconed or hidden (deleted); payment methods are an editable list. Custom categories stay in the synced
 * "categories" table. Loaded once from [init] in the Application class.
 */
object CatStore {
    private val DEFAULT_METHODS = listOf(
        "Cash" to "ক্যাশ", "Bkash" to "বিকাশ", "Nagad" to "নগদ", "Rocket" to "রকেট", "Card" to "কার্ড", "Bank" to "ব্যাংক"
    )
    private const val PREFS = "fintrack_catstore"
    private const val K_DEF = "defaults"
    private const val K_METHODS = "methods"

    private var prefs: SharedPreferences? = null
    /** "Type|orig" -> (current name, icon, hidden). */
    private val overrides = mutableMapOf<String, Triple<String, String, Boolean>>()
    private var methods: List<Pair<String, String>> = DEFAULT_METHODS

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
        prefs?.edit()?.putString(K_METHODS, list.joinToString("\n") { "${it.first}\t${it.second}" })?.apply()
    }

    fun clean(s: String): String = s.replace('|', '/').replace('\t', ' ').replace('\n', ' ').trim()

    private fun persistDefaults() {
        prefs?.edit()?.putStringSet(
            K_DEF,
            overrides.map { (k, v) -> "$k|${v.first}|${v.second}|${if (v.third) "1" else "0"}" }.toSet()
        )?.apply()
    }
}
