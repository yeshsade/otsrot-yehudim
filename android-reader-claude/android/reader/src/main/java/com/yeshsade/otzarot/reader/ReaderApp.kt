// Lean Otzarot reader. License: GPLv3.
package com.yeshsade.otzarot.reader

import android.app.Activity
import android.app.Application
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.WindowManager
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.yeshsade.otzarot.reader.data.AwakeMode
import com.yeshsade.otzarot.reader.data.AwakePolicy
import com.yeshsade.otzarot.reader.data.Library
import com.yeshsade.otzarot.reader.data.Settings
import com.yeshsade.otzarot.reader.ui.ReaderActivity
import java.io.File
import java.util.IdentityHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class ReaderApp : Application() {
    lateinit var settings: Settings
    lateinit var library: Library
    lateinit var windowPolicy: WindowPolicy
    /** One background thread for file work, so saves never overlap. */
    val io: ExecutorService = Executors.newSingleThreadExecutor()
    /** Saving documents and preparing a document for display, in order, apart from scans of the device. */
    val saves: ExecutorService = Executors.newSingleThreadExecutor()
    /** Work that must not wait behind saves and scans: covers. */
    val quick: ExecutorService = Executors.newFixedThreadPool(2)
    /** Small covers already decoded (doc id → bitmap), so returning to the list does not decode them again. */
    val covers = object : android.util.LruCache<String, android.graphics.Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: android.graphics.Bitmap) = value.byteCount
    }

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
        library = Library(File(filesDir, "library.json"))
        applyTheme(settings)
        settings.language?.let { AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(it)) }
        windowPolicy = WindowPolicy(settings)
        registerActivityLifecycleCallbacks(windowPolicy)
    }

    companion object {
        fun applyTheme(settings: Settings) = AppCompatDelegate.setDefaultNightMode(
            if (settings.night) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO)
    }
}

val Activity.app get() = application as ReaderApp

/**
 * Screen-on and in-app rotation lock for every screen while the app is shown; released in the
 * background. "Always on until leaving the app" covers all screens (owner's clarification).
 */
class WindowPolicy(private val settings: Settings) : Application.ActivityLifecycleCallbacks,
    SharedPreferences.OnSharedPreferenceChangeListener {
    private val handler = Handler(Looper.getMainLooper())
    private val resumed = IdentityHashMap<Activity, Long>()
    private val expire = Runnable { refresh() }

    init { settings.prefs.registerOnSharedPreferenceChangeListener(this) }

    fun touched(activity: Activity) {
        if (resumed.containsKey(activity)) { resumed[activity] = SystemClock.elapsedRealtime(); refresh() }
    }

    override fun onActivityResumed(activity: Activity) { resumed[activity] = SystemClock.elapsedRealtime(); refresh() }
    override fun onActivityPaused(activity: Activity) {
        resumed.remove(activity)
        activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        refresh()
    }

    private fun refresh() {
        handler.removeCallbacks(expire)
        val now = SystemClock.elapsedRealtime()
        var next: Long? = null
        resumed.forEach { (activity, last) ->
            val idle = (now - last).coerceAtLeast(0)
            val keep = AwakePolicy.keepScreenOn(settings.awake, true, activity is ReaderActivity, idle)
            if (keep) activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (keep && settings.awake == AwakeMode.MORE) {
                val remaining = AwakePolicy.EXTRA_MILLIS - idle
                next = minOf(next ?: remaining, remaining)
            }
            activity.requestedOrientation = if (settings.lockedOrientation != Settings.UNLOCKED) settings.lockedOrientation
                else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        next?.let { handler.postDelayed(expire, it.coerceAtLeast(1)) }
    }

    override fun onSharedPreferenceChanged(p: SharedPreferences?, key: String?) {
        if (key == Settings.AWAKE || key == Settings.ROTATION) refresh()
    }

    override fun onActivityCreated(a: Activity, s: Bundle?) = Unit
    override fun onActivityStarted(a: Activity) = Unit
    override fun onActivityStopped(a: Activity) = Unit
    override fun onActivitySaveInstanceState(a: Activity, s: Bundle) = Unit
    override fun onActivityDestroyed(a: Activity) { resumed.remove(a); refresh() }
}
