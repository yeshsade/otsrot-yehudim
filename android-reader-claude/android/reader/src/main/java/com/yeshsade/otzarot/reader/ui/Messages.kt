// Lean Otzarot reader. License: GPLv3.
package com.yeshsade.otzarot.reader.ui

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.TextView

/**
 * Every message in the app in one designed look (owner, 2026-10-16: "כל הודעה במכשיר תהיה מעוצבת לא סתם בלוק"):
 * questions and choices are rounded cards in the app's colours and fonts, short notices are a rounded bubble
 * near the bottom of the screen instead of Android's grey toast.
 */
object Messages {
    /** Filled red for actions that cannot be undone (deleting). */
    const val DANGER = 0xFFC62828.toInt()

    /** A question card. [yes] and [no] are the buttons (for tests). */
    class Ask(val dialog: Dialog, val yes: View, val no: View?)

    /**
     * Question with one or two buttons. [boldLine] is shown under the title in bold (as the all-files
     * explanation was approved). [danger] paints the main button red.
     */
    fun ask(
        context: Context, ui: Ui, title: CharSequence?, body: CharSequence?, yes: String, no: String?,
        danger: Boolean = false, boldLine: CharSequence? = null, cancelable: Boolean = true,
        onNo: () -> Unit = {}, onYes: () -> Unit,
    ): Ask {
        val dialog = Dialog(context)
        val box = card(ui)
        title?.let { box.addView(ui.label(it, 19f, ui.colors.ink, bold = true).localeDirection().apply { setLineSpacing(0f, 1.1f) }) }
        boldLine?.let {
            box.addView(ui.label(it, 15f, ui.colors.ink, bold = true).localeDirection().apply { setPadding(0, ui.dp(10), 0, 0) })
        }
        body?.let {
            val first = title == null && boldLine == null
            box.addView(ui.label(it, if (first) 17f else 15f, if (first) ui.colors.ink else ui.colors.muted).localeDirection().apply {
                setLineSpacing(0f, 1.15f)
                setPadding(0, if (first) 0 else ui.dp(8), 0, 0)
            })
        }
        val row = ui.row().apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL; setPadding(0, ui.dp(22), 0, 0) }
        var noView: View? = null
        if (no != null) {
            noView = button(ui, no, filled = false, color = ui.colors.accent) { dialog.dismiss(); onNo() }
            row.addView(noView, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = ui.dp(10) })
        }
        val yesView = button(ui, yes, filled = true, color = if (danger) DANGER else ui.colors.button) { dialog.dismiss(); onYes() }
        row.addView(yesView, LinearLayout.LayoutParams(-2, -2))
        box.addView(row, LinearLayout.LayoutParams(-1, -2))
        show(dialog, box, ui, cancelable, Gravity.CENTER)
        if (cancelable) dialog.setOnCancelListener { onNo() }
        return Ask(dialog, yesView, noView)
    }

    /** One row of a [menu]. */
    class Item(val label: String, val icon: Int, val danger: Boolean = false, val tag: String? = null, val action: () -> Unit)

    /** A small menu card near the bottom of the screen, with a title (for example the document's name). */
    fun menu(context: Context, ui: Ui, title: String, items: List<Item>): Dialog {
        val dialog = Dialog(context)
        val box = card(ui).apply { setPadding(ui.dp(10), ui.dp(18), ui.dp(10), ui.dp(10)) }
        box.addView(ui.label(title, 17f, ui.colors.ink, bold = true).localeDirection().apply {
            maxLines = 2; ellipsize = TextUtils.TruncateAt.END
            setPadding(ui.dp(12), 0, ui.dp(12), ui.dp(10))
        })
        items.forEach { item ->
            val ink = if (item.danger) dangerInk(ui) else ui.colors.ink
            val row = ui.row().apply {
                minimumHeight = ui.dp(56)
                setPadding(ui.dp(12), 0, ui.dp(12), 0)
                background = ui.rounded(Color.TRANSPARENT, 14f)
                isClickable = true
                tag = item.tag
                contentDescription = item.label
                setOnClickListener { dialog.dismiss(); item.action() }
            }
            row.addView(android.widget.ImageView(context).apply {
                setImageResource(item.icon)
                imageTintList = ColorStateList.valueOf(ink)
            }, LinearLayout.LayoutParams(ui.dp(22), ui.dp(22)).apply { marginEnd = ui.dp(16) })
            row.addView(ui.label(item.label, 16f, ink), LinearLayout.LayoutParams(0, -2, 1f))
            box.addView(row, LinearLayout.LayoutParams(-1, -2))
        }
        show(dialog, box, ui, cancelable = true, gravity = Gravity.BOTTOM)
        return dialog
    }

    /** Choosing one of several options (like the list order); the chosen one is marked. */
    fun choose(context: Context, ui: Ui, title: String, options: List<String>, selected: Int, onChoose: (Int) -> Unit): Dialog {
        val dialog = Dialog(context)
        val box = card(ui).apply { setPadding(ui.dp(10), ui.dp(18), ui.dp(10), ui.dp(10)) }
        box.addView(ui.label(title, 18f, ui.colors.ink, bold = true).apply { setPadding(ui.dp(12), 0, ui.dp(12), ui.dp(8)) })
        options.forEachIndexed { i, text ->
            val on = i == selected
            val row = ui.row().apply {
                minimumHeight = ui.dp(54)
                setPadding(ui.dp(8), 0, ui.dp(12), 0)
                background = ui.rounded(if (on) ui.colors.selection else Color.TRANSPARENT, 14f)
                isClickable = true
                tag = "option_$i"
                contentDescription = text
                setOnClickListener { dialog.dismiss(); onChoose(i) }
            }
            row.addView(RadioButton(context).apply {
                isChecked = on; isClickable = false; isFocusable = false
                buttonTintList = ColorStateList.valueOf(if (on) ui.colors.accent else ui.colors.muted)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(ui.dp(40), ui.dp(44)))
            row.addView(ui.label(text, 16f, if (on) ui.colors.accent else ui.colors.ink), LinearLayout.LayoutParams(0, -2, 1f))
            box.addView(row, LinearLayout.LayoutParams(-1, -2))
        }
        show(dialog, box, ui, cancelable = true, gravity = Gravity.CENTER)
        return dialog
    }

    /**
     * A short notice: a rounded bubble above the bottom of the screen that fades away by itself (tap closes it).
     * Replaces Android's grey toast. [long] keeps it longer, for a sentence that takes time to read.
     */
    fun notice(activity: Activity, text: String, long: Boolean = false): View? {
        if (activity.isFinishing || activity.isDestroyed) return null
        val ui = Ui(activity)
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return null
        content.findViewWithTag<View>(NOTICE_TAG)?.let { content.removeView(it) }
        val bubble = TextView(activity).apply {
            this.text = text
            tag = NOTICE_TAG
            textSize = 15f
            textDirection = View.TEXT_DIRECTION_LOCALE
            typeface = ui.font
            setTextColor(Color.WHITE)
            setLineSpacing(0f, 1.15f)
            gravity = Gravity.CENTER
            background = ui.rounded(if (ui.night) 0xFF3A3360.toInt() else ui.colors.header, 20f)
            setPadding(ui.dp(20), ui.dp(14), ui.dp(20), ui.dp(14))
            elevation = ui.dp(8).toFloat()
            alpha = 0f; translationY = ui.dp(16).toFloat()
        }
        val params = FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            setMargins(ui.dp(24), 0, ui.dp(24), ui.dp(110))
        }
        content.addView(bubble, params)
        bubble.animate().alpha(1f).translationY(0f).setDuration(200).start()
        val close = Runnable {
            bubble.animate().alpha(0f).translationY(ui.dp(16).toFloat()).setDuration(200)
                .withEndAction { (bubble.parent as? ViewGroup)?.removeView(bubble) }.start()
        }
        bubble.setOnClickListener { close.run() }
        Handler(Looper.getMainLooper()).postDelayed(close, if (long) 6000 else 3500)
        return bubble
    }

    const val NOTICE_TAG = "notice"

    // ---------- shared ----------

    /** Lines follow the app's language direction, even when they start with an English file name. */
    private fun TextView.localeDirection() = apply { textDirection = View.TEXT_DIRECTION_LOCALE }

    private fun card(ui: Ui) = ui.column().apply {
        background = ui.rounded(ui.colors.card, 24f)
        setPadding(ui.dp(22), ui.dp(22), ui.dp(22), ui.dp(16))
        elevation = ui.dp(10).toFloat()
    }

    private fun dangerInk(ui: Ui) = if (ui.night) 0xFFFF8A80.toInt() else DANGER

    private fun button(ui: Ui, text: String, filled: Boolean, color: Int, action: () -> Unit) =
        ui.label(text, 16f, if (filled) (if (color == ui.colors.button) ui.colors.buttonInk else Color.WHITE) else color, bold = true).apply {
            gravity = Gravity.CENTER
            minHeight = ui.dp(48)
            minWidth = ui.dp(88)
            setPadding(ui.dp(18), 0, ui.dp(18), 0)
            background = ui.rounded(if (filled) color else ui.colors.selection, 16f)
            isClickable = true
            setOnClickListener { action() }
        }

    private fun show(dialog: Dialog, box: View, ui: Ui, cancelable: Boolean, gravity: Int) {
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(box)
        dialog.setCancelable(cancelable)
        dialog.setCanceledOnTouchOutside(cancelable)
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            val width = (box.context.resources.displayMetrics.widthPixels * .9f).toInt().coerceAtMost(ui.dp(440))
            setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(gravity)
            if (gravity == Gravity.BOTTOM) attributes = attributes.apply { y = ui.dp(20) }
            setDimAmount(.35f)
        }
        dialog.show()
    }
}
