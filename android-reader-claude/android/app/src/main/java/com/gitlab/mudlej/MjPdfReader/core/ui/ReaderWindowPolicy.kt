// Otzarot Yehudim changes. License: GPLv3.
package com.gitlab.mudlej.MjPdfReader.core.ui

import android.app.Activity
import android.app.Application
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.WindowManager
import com.gitlab.mudlej.MjPdfReader.data.ReaderSettings
import com.gitlab.mudlej.MjPdfReader.data.ScreenAwakeMode
import com.gitlab.mudlej.MjPdfReader.ui.reader.MainActivity
import java.util.IdentityHashMap

class ReaderWindowPolicy(private val settings: ReaderSettings, storage: SharedPreferences) :
    Application.ActivityLifecycleCallbacks, SharedPreferences.OnSharedPreferenceChangeListener {

    private val handler = Handler(Looper.getMainLooper())
    private val resumed = IdentityHashMap<Activity, Long>()
    private val expires = Runnable { refreshAll() }

    init { storage.registerOnSharedPreferenceChangeListener(this) }

    fun onUserInteraction(activity: Activity) {
        if (resumed.containsKey(activity)) {
            resumed[activity] = SystemClock.elapsedRealtime()
            refreshAll()
        }
    }

    override fun onActivityResumed(activity: Activity) {
        resumed[activity] = SystemClock.elapsedRealtime()
        refreshAll()
    }

    override fun onActivityPaused(activity: Activity) {
        resumed.remove(activity)
        activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        refreshAll()
    }

    private fun refreshAll() {
        handler.removeCallbacks(expires)
        val now = SystemClock.elapsedRealtime()
        var nextExpiry: Long? = null
        resumed.forEach { (activity, lastInteraction) ->
            val reading = activity is MainActivity && activity.hasDocumentForScreenTimeout()
            val idle = (now - lastInteraction).coerceAtLeast(0)
            val keep = ReaderAwakePolicy.keepScreenOn(settings.awakeMode, true, reading, idle)
            if (keep) activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (keep && settings.awakeMode == ScreenAwakeMode.MORE) {
                val remaining = ReaderAwakePolicy.EXTRA_MILLIS - idle
                nextExpiry = minOf(nextExpiry ?: remaining, remaining)
            }
            if (settings.lockedOrientation != ReaderSettings.UNLOCKED) {
                activity.requestedOrientation = settings.lockedOrientation
            } else if (activity !is MainActivity) {
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }
        nextExpiry?.let { handler.postDelayed(expires, it.coerceAtLeast(1)) }
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        if (key == ReaderSettings.AWAKE_KEY || key == ReaderSettings.ROTATION_KEY) {
            resumed.keys.toList().forEach { activity ->
                if (activity is MainActivity) activity.applyOrientationPolicy()
            }
            refreshAll()
        }
    }

    override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) {
        resumed.remove(activity)
        refreshAll()
    }
}
