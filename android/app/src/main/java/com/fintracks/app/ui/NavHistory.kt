package com.fintracks.app.ui

/**
 * The app's one back stack. Each entry is a route string; the last entry is what is on screen.
 *   tab:<dashboard|transactions|shared|tools>      bottom-nav sections
 *   ov:<OVERLAY>[/<sub>]                           full-screen pages, e.g. ov:SETTINGS/login
 *   rcpt:<txId>, act:<txId>                        receipt and action dialogs (on top of a screen)
 * Pure Kotlin, no Android types, so the rules are unit-tested on the JVM.
 */
object NavHistory {
    const val HOME = "tab:dashboard"

    /** Going to the screen that is already on top changes nothing. */
    fun push(stack: List<String>, route: String): List<String> = when {
        // Home is the end of every trip: reaching it clears the history (back then asks to exit).
        route == HOME -> listOf(HOME)
        stack.lastOrNull() == route -> stack
        else -> stack + route
    }

    /** Back removes the top entry. The bottom entry (home) is never removed here. */
    fun pop(stack: List<String>): List<String> = if (stack.size > 1) stack.dropLast(1) else stack

    fun isDialog(route: String): Boolean = route.startsWith("rcpt:") || route.startsWith("act:")

    /** The screen under any dialog route: this is what the page shows. */
    fun base(stack: List<String>): String = stack.lastOrNull { !isDialog(it) } ?: HOME

    fun tab(route: String): String? = if (route.startsWith("tab:")) route.removePrefix("tab:") else null

    /** ("SETTINGS", "login") for "ov:SETTINGS/login"; ("INBOX", "") for "ov:INBOX"; null for other routes. */
    fun overlay(route: String): Pair<String, String>? {
        if (!route.startsWith("ov:")) return null
        val body = route.removePrefix("ov:")
        val slash = body.indexOf('/')
        return if (slash < 0) body to "" else body.substring(0, slash) to body.substring(slash + 1)
    }
}
