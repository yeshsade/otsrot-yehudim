// Lean Otzarot reader. License: GPLv3.
package com.yeshsade.otzarot.reader.ui

import android.app.Activity
import android.content.Intent
import com.yeshsade.otzarot.reader.R
import com.yeshsade.otzarot.reader.app
import com.yeshsade.otzarot.reader.data.Settings

/**
 * The items every screen's menu shares, in the owner's order (drawer board, 2026-10-17). The reading
 * screen adds its own around them; settings is always there, except on the settings screen itself.
 */
object AppMenu {
    fun rotationLock(activity: Activity): Drawer.Item {
        val settings = activity.app.settings
        val locked = settings.lockedOrientation != Settings.UNLOCKED
        return Drawer.Item(R.drawable.ic_rotate_lock, activity.getString(R.string.menu_rotation_lock), "menu_rotation", on = locked) {
            settings.lockedOrientation = if (locked) Settings.UNLOCKED else ScreenOrientation.current(activity)
        }
    }

    fun settings(activity: Activity) = Drawer.Item(R.drawable.ic_settings, activity.getString(R.string.library_settings), "menu_settings") {
        activity.startActivity(Intent(activity, SettingsActivity::class.java))
    }
}
