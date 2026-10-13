// Otzarot Yehudim changes. License: GPLv3.
package com.gitlab.mudlej.MjPdfReader.data

import android.content.SharedPreferences

enum class ScreenAwakeMode { MORE, ALWAYS, SYSTEM }

/** Reader-specific preferences share the existing store, without rewriting unrelated settings. */
class ReaderSettings(private val storage: SharedPreferences) {
    val upstream = Preferences(storage)

    val awakeMode: ScreenAwakeMode
        get() = runCatching {
            val default = if (upstream.getScreenOn()) ScreenAwakeMode.ALWAYS else ScreenAwakeMode.MORE
            storage.getString(AWAKE_KEY, null)?.let(ScreenAwakeMode::valueOf) ?: default
        }.getOrDefault(ScreenAwakeMode.MORE)

    fun setAwakeMode(mode: ScreenAwakeMode) {
        storage.edit().putString(AWAKE_KEY, mode.name).putBoolean(Preferences.screenOnKey, false).apply()
    }

    val lockedOrientation: Int
        get() = runCatching { storage.getInt(ROTATION_KEY, UNLOCKED) }.getOrDefault(UNLOCKED)
            .takeIf { it in LOCKED_ORIENTATIONS } ?: UNLOCKED

    fun setLockedOrientation(value: Int) {
        require(value == UNLOCKED || value in LOCKED_ORIENTATIONS)
        storage.edit().putInt(ROTATION_KEY, value).apply()
    }

    val language: String?
        get() = runCatching { storage.getString(LANGUAGE_KEY, null) }.getOrNull()
            ?.takeIf { it == "he" || it == "en" }

    fun setLanguage(value: String) {
        require(value == "he" || value == "en")
        storage.edit().putString(LANGUAGE_KEY, value).apply()
    }

    fun setResumeAtLastPosition(value: Boolean) {
        storage.edit().putBoolean(Preferences.alwaysOpenAtFirstPageKey, !value).apply()
    }

    companion object {
        const val AWAKE_KEY = "otzr.screenAwakeMode"
        const val ROTATION_KEY = "otzr.lockedOrientation"
        const val LANGUAGE_KEY = "otzr.language"
        const val UNLOCKED = -1
        private val LOCKED_ORIENTATIONS = setOf(0, 1, 8, 9)
    }
}
