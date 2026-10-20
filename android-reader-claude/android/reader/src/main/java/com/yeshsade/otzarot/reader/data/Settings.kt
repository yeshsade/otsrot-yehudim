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
    val scrollHorizontal get() = scroll == "horizontal"
    /** "עמודים מרובים" (owner, 2026-10-18): "single" = one whole page per swipe, "continuous" = a free feed (as before). */
    var pages: String
        get() = prefs.getString(PAGES, "continuous")!!
        set(v) = prefs.edit().putString(PAGES, v).apply()
    val singlePage get() = pages == "single"
    /** The page-turn animation in one page mode: none, slide, cover, fade, depth, book (owner, 2026-10-18). */
    /**
     * The topics the reader learns ("מה אתה לומד?", owner 2026-10-15): remembered from booklet to booklet; a
     * topic not in it is off. Null until the reader chose for the first time (then the screen asks first).
     */
    var studyTopics: Set<String>?
        get() = if (prefs.contains(STUDY)) prefs.getStringSet(STUDY, emptySet())!!.toSet() else null
        set(v) { if (v == null) prefs.edit().remove(STUDY).apply() else prefs.edit().putStringSet(STUDY, v).apply() }
    var pageTurn: String
        get() = prefs.getString(TURN, "slide")!!
        set(v) = prefs.edit().putString(TURN, v).apply()
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
    /** The other way of the chosen order (e.g. oldest first, ת–א). */
    var sortReversed: Boolean
        get() = prefs.getBoolean(SORT_REVERSED, false)
        set(v) = prefs.edit().putBoolean(SORT_REVERSED, v).apply()
    /** "Size" in the sort window — hidden unless switched on in the settings (owner, 2026-10-16). */
    var sortBySize: Boolean
        get() = prefs.getBoolean(SORT_SIZE, false)
        set(v) = prefs.edit().putBoolean(SORT_SIZE, v).apply()
    val sortOrder get() = SortOrder.restore(sort, sortReversed, sortBySize)
    /** The bookmark hint card: never again, once the reader ticked "don't show again". */
    var bookmarkHintNever: Boolean
        get() = prefs.getBoolean(BM_HINT_NEVER, false)
        set(v) = prefs.edit().putBoolean(BM_HINT_NEVER, v).apply()
    /** Bookmark choices: the last one made becomes the next default (owner's decision). */
    var bookmarkColor: Int
        get() = prefs.getInt(BM_COLOR, Bookmark.DEFAULT_COLOR)
        set(v) = prefs.edit().putInt(BM_COLOR, v).apply()
    var bookmarkThickness: Float
        get() = prefs.getFloat(BM_THICKNESS, Bookmark.DEFAULT_THICKNESS)
        set(v) = prefs.edit().putFloat(BM_THICKNESS, v).apply()
    var bookmarkHorizontal: Boolean
        get() = prefs.getBoolean(BM_HORIZONTAL, false)
        set(v) = prefs.edit().putBoolean(BM_HORIZONTAL, v).apply()
    /** Hiding the top bar, the toolbar and the phone's bars while reading (owner's decisions 2026-10-14). */
    var frameMode: String
        get() = prefs.getString(FRAME, FramePolicy.AUTO)!!.takeIf { it in FramePolicy.MODES } ?: FramePolicy.AUTO
        set(v) = prefs.edit().putString(FRAME, v).apply()
    var frameSeconds: Int
        get() = FramePolicy.clampSeconds(prefs.getInt(FRAME_SECONDS, FramePolicy.DEFAULT_SECONDS))
        set(v) = prefs.edit().putInt(FRAME_SECONDS, FramePolicy.clampSeconds(v)).apply()
    /** What the thin line shows while the frame is hidden. Default: battery and clock. */
    var infoPage: Boolean
        get() = prefs.getBoolean(INFO_PAGE, false)
        set(v) = prefs.edit().putBoolean(INFO_PAGE, v).apply()
    var infoBattery: Boolean
        get() = prefs.getBoolean(INFO_BATTERY, true)
        set(v) = prefs.edit().putBoolean(INFO_BATTERY, v).apply()
    var infoClock: Boolean
        get() = prefs.getBoolean(INFO_CLOCK, true)
        set(v) = prefs.edit().putBoolean(INFO_CLOCK, v).apply()
    /** Page scrubber: side of the screen ("right" by default) and how long to hold before it moves. */
    var scrubberSide: String
        get() = if (prefs.getString(SCRUB_SIDE, "right") == "left") "left" else "right"
        set(v) = prefs.edit().putString(SCRUB_SIDE, v).apply()
    var scrubberHoldMillis: Int
        get() = ScrubberPolicy.clampHold(prefs.getInt(SCRUB_HOLD, ScrubberPolicy.DEFAULT_HOLD_MS))
        set(v) = prefs.edit().putInt(SCRUB_HOLD, ScrubberPolicy.clampHold(v)).apply()
    /** The page handle stays on screen even while the frame is hidden (default: no, it comes and goes with the frame). */
    var scrubberAlways: Boolean
        get() = prefs.getBoolean(SCRUB_ALWAYS, false)
        set(v) = prefs.edit().putBoolean(SCRUB_ALWAYS, v).apply()
    var allFilesPromptDismissed: Boolean
        get() = prefs.getBoolean(ALL_FILES_DISMISSED, false)
        set(v) = prefs.edit().putBoolean(ALL_FILES_DISMISSED, v).apply()
    var whatsAppPromptDismissed: Boolean
        get() = prefs.getBoolean(WA_DISMISSED, false)
        set(v) = prefs.edit().putBoolean(WA_DISMISSED, v).apply()

    companion object {
        const val UNLOCKED = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        const val SCROLL = "scroll"
        const val PAGES = "pages"
        const val TURN = "page_turn"
        const val STUDY = "study_topics"
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
        const val BM_COLOR = "bookmark_color"
        const val BM_THICKNESS = "bookmark_thickness"
        const val FRAME = "frame"
        const val FRAME_SECONDS = "frame_seconds"
        const val INFO_PAGE = "info_page"
        const val INFO_BATTERY = "info_battery"
        const val INFO_CLOCK = "info_clock"
        const val SCRUB_SIDE = "scrubber_side"
        const val SCRUB_HOLD = "scrubber_hold_ms"
        const val SCRUB_ALWAYS = "scrubber_always"
        const val BM_HORIZONTAL = "bookmark_horizontal"
        const val SORT_REVERSED = "sort_reversed"
        const val SORT_SIZE = "sort_by_size"
        const val BM_HINT_NEVER = "bookmark_hint_never"
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

/** When the frame hides by itself. Pure logic; unit-tested. */
object FramePolicy {
    const val AUTO = "auto"
    const val TAP = "tap"
    const val ALWAYS = "always"
    val MODES = setOf(AUTO, TAP, ALWAYS)
    const val DEFAULT_SECONDS = 10
    const val MIN_SECONDS = 3
    const val MAX_SECONDS = 60

    fun clampSeconds(seconds: Int) = seconds.coerceIn(MIN_SECONDS, MAX_SECONDS)

    /** Milliseconds until the shown frame hides by itself; null = it never hides by itself. */
    fun hideAfterMillis(mode: String, seconds: Int): Long? =
        if (mode == AUTO) clampSeconds(seconds) * 1000L else null

    /** Whether the frame can be hidden at all. */
    fun canHide(mode: String) = mode != ALWAYS
}

/** The page scrubber. Pure logic; unit-tested. */
object ScrubberPolicy {
    const val DEFAULT_HOLD_MS = 500
    const val MAX_HOLD_MS = 2000
    const val STEP_MS = 250

    fun clampHold(ms: Int) = (((ms.coerceIn(0, MAX_HOLD_MS) + STEP_MS / 2) / STEP_MS) * STEP_MS).coerceAtMost(MAX_HOLD_MS)

    /** Page (0-based) for a position along the track, 0..1. */
    fun pageAt(fraction: Float, pages: Int): Int =
        if (pages <= 0) 0 else (fraction.coerceIn(0f, 1f) * pages).toInt().coerceAtMost(pages - 1)
}
