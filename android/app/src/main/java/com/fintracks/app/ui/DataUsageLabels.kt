package com.fintracks.app.ui

/** Fixed Android system UIDs. Hotspot and tethering traffic is attributed to the dns_tether UID (1052). */
internal const val UID_ANDROID_SYSTEM = 0
internal const val UID_ANDROID_OS = 1000
internal const val UID_TETHERING = 1052
internal const val UID_NETWORK_STACK = 1073

/**
 * Display name for a UID. The app's own label wins when a package is visible; otherwise a fixed
 * Bengali label. A raw "UID n" text never reaches the UI.
 */
internal fun uidLabel(uid: Int, appLabel: String?, english: Boolean = false): String {
    fun pick(bn: String, en: String) = if (english) en else bn
    return when (uid) {
        UID_ANDROID_SYSTEM -> pick("Android সিস্টেম", "Android system")
        UID_ANDROID_OS -> pick("Android OS", "Android OS")
        UID_TETHERING -> pick("হটস্পট ও টিথারিং", "Hotspot and tethering")
        UID_NETWORK_STACK -> pick("নেটওয়ার্ক স্ট্যাক", "Network stack")
        else -> appLabel?.trim()?.takeIf { it.isNotEmpty() }
            ?: if (uid < 10000) pick("Android সিস্টেম", "Android system")
            else pick("মুছে ফেলা অ্যাপ", "Removed app")
    }
}
