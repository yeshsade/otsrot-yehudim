// Lean Otzarot reader. License: GPLv3.
package com.yeshsade.otzarot.reader.data

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ActivityInfo

/** All user preferences. Values mirror the approved settings catalog (version 4). */
class Settings(context: Context) {
    val prefs: SharedPreferences = context.getSharedPreferences("reader", Context.MODE_PRIVATE)

    var scroll: String
        get() = prefs.getString(SCROLL, "vertical")!!
        set(v) = prefs.edit().putString(SCROLL, v).apply()
    var resume: String
        get() = prefs.getString(RESUME, "last")!!
        set(v) = prefs.edit().putString(RESUME, v).apply()
    var awake: AwakeMode
        get() = runCatching { AwakeMode.valueOf(prefs.getString(AWAKE, AwakeMode.MORE.name)!!) }.getOrDefault(AwakeMode.MORE)
        set(v) = prefs.edit().putString(AWAKE, v.name).apply()
    var lockedOrientation: Int
        get() = prefs.getInt(ROTATION, UNLOCKED)
        set(v) = prefs.edit().putInt(ROTATION, v).apply()
    var night: Boolean
        get() = prefs.getString(THEME, "day") == "night"
        set(v) = prefs.edit().putString(THEME, if (v) "night" else "day").apply()
    var language: String?
        get() = prefs.getString(LANGUAGE, null)
        set(v) = prefs.edit().putString(LANGUAGE, v).apply()
    /** What opens with the app: "last" (default, owner's decision) or "library". No settings row yet: wording not approved. */
    var startScreen: String
        get() = prefs.getString(START, "last")!!
        set(v) = prefs.edit().putString(START, v).apply()
    var whatsAppTree: String?
        get() = prefs.getString(WA_TREE, null)
        set(v) = prefs.edit().putString(WA_TREE, v).apply()
    /** List order; default "received" = newest received first (owner's requirement). */
    var sort: String
        get() = prefs.getString(SORT, "received")!!
        set(v) = prefs.edit().putString(SORT, v).apply()
    var allFilesPromptDismissed: Boolean
        get() = prefs.getBoolean(ALL_FILES_DISMISSED, false)
        set(v) = prefs.edit().putBoolean(ALL_FILES_DISMISSED, v).apply()
    var whatsAppPromptDismissed: Boolean
        get() = prefs.getBoolean(WA_DISMISSED, false)
        set(v) = prefs.edit().putBoolean(WA_DISMISSED, v).apply()

    companion object {
        const val UNLOCKED = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        const val SCROLL = "scroll"
        const val RESUME = "resume"
        const val AWAKE = "awake"
        const val ROTATION = "rotation"
        const val THEME = "theme"
        const val LANGUAGE = "language"
        const val START = "start"
        const val WA_TREE = "whatsapp_tree"
        const val WA_DISMISSED = "whatsapp_prompt_dismissed"
        const val SORT = "sort"
        const val ALL_FILES_DISMISSED = "all_files_prompt_dismissed"
    }
}

enum class AwakeMode { MORE, ALWAYS, SYSTEM }

/** Extra hold time is followed by the phone's normal timeout; never changes system settings. */
object AwakePolicy {
    const val EXTRA_MILLIS = 10 * 60 * 1000L

    fun keepScreenOn(mode: AwakeMode, foreground: Boolean, reading: Boolean, idleMillis: Long): Boolean {
        if (!foreground) return false
        return when (mode) {
            AwakeMode.ALWAYS -> true
            AwakeMode.SYSTEM -> false
            AwakeMode.MORE -> reading && idleMillis < EXTRA_MILLIS
        }
    }
}
