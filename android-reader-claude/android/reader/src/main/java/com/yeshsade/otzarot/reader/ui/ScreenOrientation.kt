// Lean Otzarot reader. License: GPLv3.
package com.yeshsade.otzarot.reader.ui

import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Build
import android.view.Surface

/** The orientation the screen has right now, to lock it there ("lock screen rotation"). */
object ScreenOrientation {
    @Suppress("DEPRECATION")
    fun current(activity: Activity): Int {
        val rotation = if (Build.VERSION.SDK_INT >= 30) activity.display?.rotation ?: Surface.ROTATION_0 else activity.windowManager.defaultDisplay.rotation
        val reverse = rotation == Surface.ROTATION_180 || rotation == Surface.ROTATION_270
        return if (activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            if (reverse) ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        } else if (reverse) ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    }
}
