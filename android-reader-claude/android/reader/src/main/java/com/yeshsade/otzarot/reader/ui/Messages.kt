// Lean Otzarot reader. License: GPLv3.
package com.yeshsade.otzarot.reader.ui

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.yeshsade.otzarot.reader.R

/**
 * Every message in the app, in one look (owner, 2026-10-16: "כל הודעה במכשיר תהיה מעוצבת לא סתם בלוק", and of
 * three directions he chose "ג"): no box floating in the middle of the screen. The words and the actions are a
 * rounded group of large buttons resting at the bottom of the screen, and "cancel" is a button of its own
 * under it, as on the iPhone. Short notices are a small white strip near the bottom that goes away by itself.
 */
object Messages {
    /** Red for actions that cannot be undone (deleting). */
    const val DANGER = 0xFFC62828.toInt()

    /** A question. [yes] and [no] are the buttons (for tests). */
    class Ask(val dialog: Dialog, val yes: View, val no: View?)

    /**
     * Words at the top of the group ([title] in bold, [boldLine] in bold under it, [body] under that),
     * the main action ([yes], red when [danger]) as a large button, and [no] as its own button below.
     */
    fun ask(
        context: Context, ui: Ui, title: CharSequence?, body: CharSequence?, yes: String, no: String?,
        danger: Boolean = false, boldLine: CharSequence? = null, cancelable: Boolean = true,
        onNo: () -> Unit = {}, onYes: () -> Unit,
    ): Ask {
        val dialog = Dialog(context)
        val sheet = sheet(ui)
        val group = group(ui)
        group.addView(header(ui, title, boldLine, body))
        val yesView = action(ui, yes, if (danger) dangerInk(ui) else ui.colors.accent) { dialog.dismiss(); onYes() }
        group.addView(divider(ui)); group.addView(yesView)
        sheet.addView(group)
        var noView: View? = null
        if (no != null) {
            noView = cancel(ui, no) { dialog.dismiss(); onNo() }
            sheet.addView(noView, gap(ui))
        }
        show(dialog, sheet, ui, cancelable)
        if (cancelable) dialog.setOnCancelListener { onNo() }
        return Ask(dialog, yesView, noView)
    }

    /** One row of a [menu]. */
    class Item(val label: String, val danger: Boolean = false, val tag: String? = null, val action: () -> Unit)

    /** A menu: the subject at the top (for example the document's name), a large button per action, and [cancel]. */
    fun menu(context: Context, ui: Ui, title: String, items: List<Item>, cancel: String): Dialog {
        val dialog = Dialog(context)
        val sheet = sheet(ui)
        val group = group(ui)
        group.addView(header(ui, null, null, title).apply {
            (getChildAt(0) as TextView).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
        })
        items.forEach { item ->
            group.addView(divider(ui))
            group.addView(action(ui, item.label, if (item.danger) dangerInk(ui) else ui.colors.accent) {
                dialog.dismiss(); item.action()
            }.apply { tag = item.tag })
        }
        sheet.addView(group)
        sheet.addView(cancel(ui, cancel) { dialog.dismiss() }, gap(ui))
        show(dialog, sheet, ui, cancelable = true)
        return dialog
    }

    /**
     * A short explanation with a "don't show again" box and an OK button (the bookmark hint). Closes only with
     * the button, as the owner asked. [onClose] gets whether the box was ticked.
     */
    fun info(context: Context, ui: Ui, title: String, heading: String?, lines: List<String>, never: String, ok: String,
             onClose: (Boolean) -> Unit): Ask {
        val dialog = Dialog(context)
        val sheet = sheet(ui)
        val group = group(ui)
        val words = ui.column().apply { setPadding(ui.dp(20), ui.dp(18), ui.dp(20), ui.dp(12)) }
        words.addView(text(ui, title, 17f, ui.colors.ink, bold = true).apply { gravity = Gravity.CENTER })
        heading?.let { words.addView(text(ui, it, 15f, ui.colors.ink, bold = true).apply { setPadding(0, ui.dp(14), 0, ui.dp(2)) }) }
        lines.forEach { words.addView(text(ui, it, 15f, ui.colors.muted).apply { setPadding(0, ui.dp(4), 0, 0) }) }
        group.addView(words)
        group.addView(divider(ui))
        val box = CheckBox(context).apply {
            buttonTintList = android.content.res.ColorStateList.valueOf(ui.colors.accent)
            isClickable = false; isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val neverRow = ui.row().apply {
            minimumHeight = ui.dp(52); setPadding(ui.dp(14), 0, ui.dp(16), 0)
            isClickable = true; tag = "never"; contentDescription = never
            addView(box, LinearLayout.LayoutParams(ui.dp(40), ui.dp(44)))
            addView(text(ui, never, 15f, ui.colors.ink), LinearLayout.LayoutParams(0, -2, 1f))
            setOnClickListener { box.isChecked = !box.isChecked }
        }
        group.addView(neverRow)
        sheet.addView(group)
        val okView = cancel(ui, ok) { dialog.dismiss(); onClose(box.isChecked) }
        sheet.addView(okView, gap(ui))
        show(dialog, sheet, ui, cancelable = false)
        return Ask(dialog, okView, neverRow)
    }

    /**
     * A short notice: a small white strip near the bottom of the screen that goes away by itself (a tap closes
     * it at once). Replaces Android's grey toast. [long] keeps it longer, for a sentence that takes time to read.
     */
    fun notice(activity: Activity, text: String, long: Boolean = false): View? {
        if (activity.isFinishing || activity.isDestroyed) return null
        val ui = Ui(activity)
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return null
        content.findViewWithTag<View>(NOTICE_TAG)?.let { content.removeView(it) }
        val strip = text(ui, text, 15f, ui.colors.ink).apply {
            tag = NOTICE_TAG
            gravity = Gravity.CENTER
            background = ui.rounded(ui.colors.card, 16f, border = ui.colors.border)
            setPadding(ui.dp(20), ui.dp(14), ui.dp(20), ui.dp(14))
            elevation = ui.dp(6).toFloat()
            alpha = 0f; translationY = ui.dp(16).toFloat()
        }
        content.addView(strip, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            setMargins(ui.dp(20), 0, ui.dp(20), ui.dp(110))
        })
        strip.animate().alpha(1f).translationY(0f).setDuration(200).start()
        val close = Runnable {
            strip.animate().alpha(0f).translationY(ui.dp(16).toFloat()).setDuration(200)
                .withEndAction { (strip.parent as? ViewGroup)?.removeView(strip) }.start()
        }
        strip.setOnClickListener { close.run() }
        Handler(Looper.getMainLooper()).postDelayed(close, if (long) 6000 else 3500)
        return strip
    }

    const val NOTICE_TAG = "notice"

    // ---------- the parts (also used by windows with their own content, like the sort window) ----------

    /** The column that rests at the bottom of the screen: one or more groups. */
    fun sheet(ui: Ui) = ui.column().apply { setPadding(0, 0, 0, 0) }

    /** A rounded white group of rows. */
    fun group(ui: Ui) = ui.column().apply {
        background = ui.rounded(ui.colors.card, 18f)
        clipToOutline = true
    }

    /** The words at the top of a group: small and centred, the title a little stronger. */
    fun header(ui: Ui, title: CharSequence?, boldLine: CharSequence?, body: CharSequence?) = ui.column().apply {
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(ui.dp(22), ui.dp(16), ui.dp(22), ui.dp(14))
        title?.let { addView(text(ui, it, 16f, ui.colors.ink, bold = true).apply { gravity = Gravity.CENTER }) }
        boldLine?.let { addView(text(ui, it, 14f, ui.colors.ink, bold = true).apply { gravity = Gravity.CENTER; setPadding(0, ui.dp(8), 0, 0) }) }
        body?.let {
            addView(text(ui, it, 14f, ui.colors.muted).apply {
                gravity = Gravity.CENTER
                if (title != null || boldLine != null) setPadding(0, ui.dp(6), 0, 0)
            })
        }
    }

    /** A large button row inside a group. */
    fun action(ui: Ui, label: String, ink: Int, bold: Boolean = false, onTap: () -> Unit) =
        text(ui, label, 17f, ink, bold = bold).apply {
            gravity = Gravity.CENTER
            minHeight = ui.dp(56)
            setPadding(ui.dp(16), 0, ui.dp(16), 0)
            isClickable = true
            contentDescription = label
            setOnClickListener { onTap() }
        }

    /** The separate button under the group ("ביטול", "סגירה", "אישור"). */
    fun cancel(ui: Ui, label: String, onTap: () -> Unit) = text(ui, label, 17f, ui.colors.accent, bold = true).apply {
        gravity = Gravity.CENTER
        minHeight = ui.dp(56)
        background = ui.rounded(ui.colors.card, 18f)
        isClickable = true
        contentDescription = label
        setOnClickListener { onTap() }
    }

    fun divider(ui: Ui) = View(ui.row().context).apply {
        setBackgroundColor(ui.colors.border)
        layoutParams = LinearLayout.LayoutParams(-1, ui.dp(1))
    }

    fun gap(ui: Ui) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(8) }

    fun show(dialog: Dialog, content: View, ui: Ui, cancelable: Boolean) {
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(content)
        dialog.setCancelable(cancelable)
        dialog.setCanceledOnTouchOutside(cancelable)
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            val width = (content.context.resources.displayMetrics.widthPixels - ui.dp(20)).coerceAtMost(ui.dp(480))
            setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
            attributes = attributes.apply { y = ui.dp(10) }
            setDimAmount(.3f)
            setWindowAnimations(R.style.OtzrSheetAnimation)
        }
        dialog.show()
    }

    private fun dangerInk(ui: Ui) = if (ui.night) 0xFFFF8A80.toInt() else DANGER

    /** Text that follows the app's language direction even when it starts with an English file name. */
    private fun text(ui: Ui, value: CharSequence, size: Float, color: Int, bold: Boolean = false) =
        ui.label(value, size, color, bold).apply {
            textDirection = View.TEXT_DIRECTION_LOCALE
            setLineSpacing(0f, 1.15f)
        }

}
