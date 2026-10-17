// Lean Otzarot reader. License: GPLv3.
package com.yeshsade.otzarot.reader.ui

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.Window
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.yeshsade.otzarot.reader.data.FramePolicy
import com.yeshsade.otzarot.reader.data.Settings

/**
 * The reading screen's frame: the top bar, the toolbar and the page handle, together with the phone's own
 * bars, shown and hidden as one (owner's decisions 2026-10-14, "like MX Player"). While hidden, a thin
 * [InfoLine] can show the page, the battery and the time.
 */
class Chrome(
    private val window: Window,
    private val root: View,
    /** Slides up when hiding. */
    private val top: View,
    /** Slides down when hiding. */
    private val bottom: View,
    /** Fade out when hiding (the page handle, unless it stays on screen). */
    private val faders: List<View>,
    private val info: InfoLine,
    private val settings: Settings,
) {
    private val main = Handler(Looper.getMainLooper())
    private val hideLater = Runnable { hide() }
    private val bars = WindowCompat.getInsetsController(window, root).apply {
        // a swipe from the edge brings the phone's buttons back for a moment, so nobody gets stuck
        systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    val mode: String = settings.frameMode
    var shown = true
        private set

    /** Called after the frame starts showing or hiding. */
    var changed: () -> Unit = {}

    /** The frame starts shown, then follows the mode. */
    fun start() = show()

    /** All parts that come and go (for tests). */
    val parts get() = listOf(top, bottom) + faders

    fun show() {
        shown = true
        // smooth, a quarter of a second (owner's decision): bars slide back in and the handle fades in
        top.animateIn(); bottom.animateIn(); faders.forEach { it.animateIn() }
        info.animate().cancel()
        info.animate().alpha(0f).setDuration(DURATION).withEndAction { if (shown) info.visibility = View.GONE }.start()
        bars.show(WindowInsetsCompat.Type.systemBars())
        schedule()
        changed()
    }

    fun hide() {
        main.removeCallbacks(hideLater)
        if (!FramePolicy.canHide(mode)) return
        shown = false
        top.animateOut(translateY = -(top.height.toFloat()))
        bottom.animateOut(translateY = bottom.height.toFloat() + ((bottom.layoutParams as? android.view.ViewGroup.MarginLayoutParams)?.bottomMargin ?: 0))
        faders.forEach { it.animateOut(translateY = 0f) }
        if (info.hasContent) {
            info.refresh()
            info.alpha = 0f
            info.visibility = View.VISIBLE
            info.animate().cancel()
            info.animate().alpha(1f).setDuration(DURATION).start()
        } else info.visibility = View.GONE
        bars.hide(WindowInsetsCompat.Type.systemBars())
        changed()
    }

    private fun View.animateIn() {
        animate().cancel()
        visibility = View.VISIBLE
        animate().translationY(0f).alpha(1f).setDuration(DURATION)
            .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
    }

    private fun View.animateOut(translateY: Float) {
        animate().cancel()
        animate().translationY(translateY).alpha(0f).setDuration(DURATION)
            .setInterpolator(android.view.animation.AccelerateInterpolator())
            .withEndAction { if (!shown) visibility = View.INVISIBLE }.start()
    }

    /** A tap on the page. */
    fun toggle() = if (shown) hide() else show()

    /** The reader used the frame (a button, the handle): give it the full time again. */
    fun used() {
        if (shown) schedule()
    }

    /** Keeps the frame up while something needs it (a dialog, dragging the handle). */
    fun hold() = main.removeCallbacks(hideLater)

    fun stop() = main.removeCallbacks(hideLater)

    private companion object { const val DURATION = 250L }

    private fun schedule() {
        main.removeCallbacks(hideLater)
        val delay = FramePolicy.hideAfterMillis(mode, settings.frameSeconds) ?: return
        main.postDelayed(hideLater, delay)
    }
}

/** The thin line shown while the frame is hidden: page on one side, battery and time on the other. */
@SuppressLint("ViewConstructor")
class InfoLine(context: Context, private val settings: Settings) : LinearLayout(context) {
    private val page = text()
    private val status = text()
    private var pageText = ""
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) = refresh()
    }
    private var listening = false

    val hasContent get() = settings.infoPage || settings.infoBattery || settings.infoClock

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(0x99000000.toInt())
        val pad = (12 * resources.displayMetrics.density).toInt()
        setPadding(pad, pad / 4, pad, pad / 4)
        addView(page, LayoutParams(0, -2, 1f))
        addView(status, LayoutParams(-2, -2))
        visibility = GONE
        tag = "info_line"
    }

    private fun text() = TextView(context).apply {
        textSize = 12f
        setTextColor(Color.WHITE)
        maxLines = 1
    }

    fun setPage(text: String) {
        pageText = text
        if (visibility == VISIBLE) refresh()
    }

    fun refresh() {
        page.text = if (settings.infoPage) pageText else ""
        val parts = mutableListOf<String>()
        if (settings.infoBattery) battery()?.let { parts += "$it%" }
        if (settings.infoClock) parts += android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date())
        status.text = parts.joinToString("   ")
    }

    private fun battery(): Int? {
        val sticky = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val level = sticky.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = sticky.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        return if (level >= 0 && scale > 0) level * 100 / scale else null
    }

    /** Keep battery and time current while the screen is in front. */
    fun listen(on: Boolean) {
        if (on == listening) return
        listening = on
        if (on) {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_TIME_TICK); addAction(Intent.ACTION_TIME_CHANGED); addAction(Intent.ACTION_BATTERY_CHANGED)
            }
            ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            refresh()
        } else runCatching { context.unregisterReceiver(receiver) }
    }
}
