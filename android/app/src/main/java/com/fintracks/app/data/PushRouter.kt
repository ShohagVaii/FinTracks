package com.fintracks.app.data

import kotlinx.coroutines.flow.MutableStateFlow

/** Holds a push tap's target screen until the signed-in UI can open it. */
object PushRouter {
    const val EXTRA_TARGET = "fintrack_push_target"
    val pending = MutableStateFlow<String?>(null)

    fun put(target: String?) {
        if (!target.isNullOrBlank()) pending.value = target.lowercase().take(40)
    }

    fun clear() {
        pending.value = null
    }
}
