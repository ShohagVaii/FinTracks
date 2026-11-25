package com.fintracks.app

import com.fintracks.app.ui.uidLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class UidLabelTest {
    @Test fun systemUidsHaveFixedLabels() {
        assertEquals("Android সিস্টেম", uidLabel(0, "android"))
        assertEquals("Android OS", uidLabel(1000, null))
        assertEquals("নেটওয়ার্ক স্ট্যাক", uidLabel(1073, "com.android.networkstack"))
        assertEquals("হটস্পট ও টিথারিং", uidLabel(1052, null))
    }

    @Test fun userAppUsesVisibleLabel() {
        assertEquals("Telegram", uidLabel(10039, "Telegram"))
        assertEquals("Google Drive", uidLabel(10317, "  Google Drive "))
    }

    @Test fun missingPackageFallsBackWithoutRawUid() {
        assertEquals("মুছে ফেলা অ্যাপ", uidLabel(10253, null))
        assertEquals("মুছে ফেলা অ্যাপ", uidLabel(10258, ""))
        assertEquals("Android সিস্টেম", uidLabel(1234, null))
        assertEquals("Removed app", uidLabel(10243, null, english = true))
    }

    @Test fun neverShowsRawUidText() {
        listOf(0, 1000, 1052, 1073, 1234, 10039, 10317, 99999).forEach { uid ->
            assertFalse(uidLabel(uid, null).contains("UID"))
        }
    }
}
