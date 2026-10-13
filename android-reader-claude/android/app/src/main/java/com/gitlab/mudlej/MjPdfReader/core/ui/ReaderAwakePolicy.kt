// Otzarot Yehudim changes. License: GPLv3.
package com.gitlab.mudlej.MjPdfReader.core.ui

import com.gitlab.mudlej.MjPdfReader.data.ScreenAwakeMode

/** Extra hold time is followed by the phone's normal timeout; never changes system settings. */
object ReaderAwakePolicy {
    const val EXTRA_MILLIS = 10 * 60 * 1000L

    fun keepScreenOn(mode: ScreenAwakeMode, foreground: Boolean, reading: Boolean, idleMillis: Long): Boolean {
        if (!foreground) return false
        return when (mode) {
            ScreenAwakeMode.ALWAYS -> true
            ScreenAwakeMode.SYSTEM -> false
            ScreenAwakeMode.MORE -> reading && idleMillis < EXTRA_MILLIS
        }
    }
}
