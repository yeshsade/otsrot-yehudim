// Lean Otzarot reader. License: GPLv3.
package com.yeshsade.otzarot.reader.ui

import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import com.yeshsade.otzarot.reader.R

/**
 * The menu behind ☰ (owner, 2026-10-17): it slides in from the left, with clean rows and no boxes ("without
 * old-fashioned frames"). On top, the items always shown; then "More", which opens the rest in place; and
 * pinned at the bottom, settings. Which item goes where is the owner's arrangement (the drawer board).
 */
object Drawer {
    class Item(
        val icon: Int, val label: String, val tag: String,
        /** A switch-like item shows whether it is on. */
        val on: Boolean? = null,
        val action: () -> Unit,
    )

    /** The open drawer (for tests). */
    class Shown(val dialog: Dialog, val root: View)

    fun show(
        context: Context, ui: Ui, title: String?, subtitle: String?,
        top: List<Item>, more: List<Item>, pinned: List<Item>,
        /** The progress button at the head of the drawer ("מד התקדמות"), with the week's percentage; null = none. */
        progress: Pair<Float, () -> Unit>? = null,
    ): Shown {
        val dialog = Dialog(context)
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ui.colors.card)
            tag = "drawer"
        }
        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(0, ui.dp(8), 0, ui.dp(8)) }
        if (title != null) {
            list.addView(ui.label(title, 21f, ui.colors.ink, true).apply {
                typeface = ui.heading; maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
                textDirection = View.TEXT_DIRECTION_LOCALE
                setPadding(ui.dp(24), ui.dp(12), ui.dp(24), 0)
            })
            subtitle?.let { list.addView(ui.label(it, 13.5f, ui.colors.muted).apply { setPadding(ui.dp(24), ui.dp(2), ui.dp(24), ui.dp(14)) }) }
        }
        if (progress != null) {
            val (fraction, open) = progress
            val button = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                tag = "menu_progress"
                isClickable = true; isFocusable = true
                background = android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(0x22000000),
                    ui.rounded(ui.colors.selection, 16f), null)
                setPadding(ui.dp(10), ui.dp(8), ui.dp(16), ui.dp(8))
                contentDescription = context.getString(R.string.pr_title)
                setOnClickListener { dialog.dismiss(); open() }
            }
            val gauge = Gauge(context, ui, big = false).apply { this.fraction = fraction; mini = true }
            button.addView(gauge, LinearLayout.LayoutParams(ui.dp(54), ui.dp(44)).apply { marginEnd = ui.dp(12) })
            val words = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            words.addView(ui.label(context.getString(R.string.pr_title), 17f, ui.colors.ink, true))
            words.addView(ui.label(context.getString(R.string.menu_progress_week, (fraction * 100).toInt()), 13f, ui.colors.muted))
            button.addView(words, LinearLayout.LayoutParams(0, -2, 1f))
            button.addView(ImageView(context).apply {
                setImageResource(R.drawable.otzr_ic_chevron)
                imageTintList = ColorStateList.valueOf(ui.colors.accent)
            }, LinearLayout.LayoutParams(ui.dp(18), ui.dp(18)))
            list.addView(button, LinearLayout.LayoutParams(-1, -2).apply { setMargins(ui.dp(16), ui.dp(2), ui.dp(16), ui.dp(10)) })
        }
        fun row(item: Item, muted: Boolean = false): View = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = ui.dp(52)
            setPadding(ui.dp(24), 0, ui.dp(20), 0)
            tag = item.tag
            isClickable = true; isFocusable = true
            background = ripple(ui)
            contentDescription = item.label
            setOnClickListener { dialog.dismiss(); item.action() }
            addView(ImageView(context).apply {
                setImageResource(item.icon)
                imageTintList = ColorStateList.valueOf(ui.colors.accent)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(ui.dp(22), ui.dp(22)).apply { marginEnd = ui.dp(18) })
            addView(ui.label(item.label, 16.5f, if (muted) ui.colors.muted else ui.colors.ink), LinearLayout.LayoutParams(0, -2, 1f))
            if (item.on == true) addView(ui.label("✓", 17f, ui.colors.accent, true))
        }
        top.forEach { list.addView(row(it)) }
        if (more.isNotEmpty()) {
            list.addView(line(ui), LinearLayout.LayoutParams(-1, ui.dp(1)).apply { setMargins(ui.dp(24), ui.dp(6), ui.dp(24), ui.dp(6)) })
            val hidden = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE; tag = "drawer_more_items" }
            val arrow = ui.label("⌄", 16f, ui.colors.accent, true)
            val moreRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = ui.dp(52)
                setPadding(ui.dp(24), 0, ui.dp(20), 0)
                tag = "drawer_more"
                isClickable = true; isFocusable = true
                background = ripple(ui)
                contentDescription = context.getString(R.string.menu_more)
                setOnClickListener {
                    val open = hidden.visibility != View.VISIBLE
                    hidden.visibility = if (open) View.VISIBLE else View.GONE
                    arrow.text = if (open) "⌃" else "⌄"
                }
                addView(ImageView(context).apply {
                    setImageResource(R.drawable.ic_more)
                    imageTintList = ColorStateList.valueOf(ui.colors.accent)
                }, LinearLayout.LayoutParams(ui.dp(22), ui.dp(22)).apply { marginEnd = ui.dp(18) })
                addView(ui.label(context.getString(R.string.menu_more), 16.5f, ui.colors.accent, true))
                addView(arrow, LinearLayout.LayoutParams(-2, -2).apply { marginStart = ui.dp(6) })
            }
            list.addView(moreRow)
            more.forEach { hidden.addView(row(it)) }
            list.addView(hidden)
        }
        val scroll = ScrollView(context).apply { addView(list) }
        panel.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        if (pinned.isNotEmpty()) {
            panel.addView(line(ui), LinearLayout.LayoutParams(-1, ui.dp(1)).apply { setMargins(ui.dp(24), 0, ui.dp(24), 0) })
            pinned.forEach { panel.addView(row(it)) }
            panel.addView(View(context), LinearLayout.LayoutParams(-1, ui.dp(10)))
        }
        // the phone's bars stay clear of the rows
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(panel) { v, insets ->
            val bars = insets.getInsetsIgnoringVisibility(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, bars.top + ui.dp(12), 0, bars.bottom)
            insets
        }
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(panel)
        dialog.setCanceledOnTouchOutside(true)
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            val width = (context.resources.displayMetrics.widthPixels * .82f).toInt().coerceAtMost(ui.dp(340))
            setLayout(width, ViewGroup.LayoutParams.MATCH_PARENT)
            setGravity(Gravity.LEFT or Gravity.FILL_VERTICAL)
            setDimAmount(.32f)
            setWindowAnimations(R.style.OtzrDrawerAnimation)
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(this, false)
        }
        dialog.show()
        return Shown(dialog, panel)
    }

    private fun line(ui: Ui) = View(ui.row().context).apply { setBackgroundColor(ui.colors.border) }

    private fun ripple(ui: Ui) = android.graphics.drawable.RippleDrawable(
        ColorStateList.valueOf(ui.colors.selection), null, android.graphics.drawable.ColorDrawable(android.graphics.Color.WHITE))

    /** The ☰ button for a screen's top bar: on the left, white on the deep header. */
    fun button(context: Context, ui: Ui, open: () -> Unit) = android.widget.ImageButton(context).apply {
        setImageResource(R.drawable.ic_menu)
        imageTintList = ColorStateList.valueOf(android.graphics.Color.WHITE)
        contentDescription = context.getString(R.string.menu_open)
        background = ui.rounded(Ui.c("#22FFFFFF"), 14f)
        setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(12))
        tag = "menu_button"
        setOnClickListener { open() }
    }

}
