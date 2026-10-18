// Lean Otzarot reader. Visual language "B" chosen by the owner: deep header with rounded bottom
// corners, filled icons, strong button, floating reading bar; Noto Sans Hebrew. License: GPLv3.
package com.yeshsade.otzarot.reader.ui

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

data class Palette(
    val canvas: Int, val card: Int, val header: Int, val ink: Int, val muted: Int,
    val accent: Int, val border: Int, val selection: Int, val page: Int, val button: Int, val buttonInk: Int,
)

/** Shared building blocks so every screen looks the same. */
class Ui(private val context: Context) {
    val night = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    val colors = if (night)
        Palette(c("#171720"), c("#24232F"), c("#252047"), c("#F2EFF9"), c("#C1BBD0"), c("#C5B8FF"), c("#3E394C"), c("#443C64"), c("#1E1D26"), c("#C5B8FF"), c("#1C1830"))
    else
        Palette(c("#F1F0F8"), Color.WHITE, c("#342C69"), c("#252438"), c("#646279"), c("#57479F"), c("#E0DCEB"), c("#EEEAF9"), Color.WHITE, c("#57479F"), Color.WHITE)

    val font: Typeface = Fonts.regular(context)
    val medium: Typeface = Fonts.medium(context)
    val heading: Typeface = Fonts.heading(context)

    fun dp(v: Int) = dp(v.toFloat())
    fun dp(v: Float) = (v * context.resources.displayMetrics.density + .5f).toInt()

    fun row() = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    fun column() = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    fun label(value: CharSequence, size: Float, color: Int, bold: Boolean = false) = TextView(context).apply {
        text = value; textSize = size; setTextColor(color)
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        typeface = if (bold) medium else font
    }

    fun rounded(color: Int, radius: Float, border: Int? = null, topOnly: Boolean = false, bottomOnly: Boolean = false) = GradientDrawable().apply {
        setColor(color)
        val r = dp(radius).toFloat()
        cornerRadii = when {
            topOnly -> floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            bottomOnly -> floatArrayOf(0f, 0f, 0f, 0f, r, r, r, r)
            else -> FloatArray(8) { r }
        }
        border?.let { setStroke(dp(1), it) }
    }

    /** Square 48dp header button (touch target suitable for older readers). */
    fun headerButton(icon: Int, description: String, action: () -> Unit) = ImageButton(context).apply {
        setImageResource(icon)
        imageTintList = ColorStateList.valueOf(Color.WHITE)
        contentDescription = description
        background = rounded(c("#22FFFFFF"), 14f)
        setPadding(dp(12), dp(12), dp(12), dp(12))
        layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginStart = dp(8) }
        setOnClickListener { action() }
    }

    /** The deep header with rounded bottom corners. Returns (header, titleColumn). */
    fun header(title: String, subtitle: String? = null): Pair<LinearLayout, LinearLayout> {
        val header = row().apply {
            setPadding(dp(16), 0, dp(12), dp(14))
            background = rounded(colors.header, 26f, bottomOnly = true)
            minimumHeight = dp(84)
        }
        val titles = column()
        titles.addView(label(title, 24f, Color.WHITE, true).apply { typeface = heading; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
        subtitle?.let { titles.addView(label(it, 13f, c("#D9D3F2")).apply { tag = "subtitle" }) }
        header.addView(titles, LinearLayout.LayoutParams(0, -2, 1f))
        return header to titles
    }

    /**
     * The top bar as the owner set it on 2026-10-17: nothing in it but ☰ on the left ("I don't want anything
     * in the header, only a hamburger on the left"). Returns (header, extras): [extras] is the right side, for
     * the few buttons that must stay outside the menu (undo and redo on the reading screen).
     */
    fun menuBar(context: Context, openMenu: () -> Unit): Pair<LinearLayout, LinearLayout> {
        val header = row().apply {
            // ☰ is on the physical left in Hebrew and English alike
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            setPadding(dp(12), 0, dp(12), dp(10))
            background = rounded(colors.header, 22f, bottomOnly = true)
            minimumHeight = dp(64)
        }
        header.addView(Drawer.button(context, this, openMenu), LinearLayout.LayoutParams(dp(48), dp(48)))
        header.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
        val extras = row().apply { layoutDirection = View.LAYOUT_DIRECTION_LOCALE }
        header.addView(extras, LinearLayout.LayoutParams(-2, -2))
        return header to extras
    }

    /** A quiet button for the top bar: no box around it, dimmed while it can't be used. */
    fun quietButton(icon: Int, description: String, action: () -> Unit) = ImageButton(context).apply {
        setImageResource(icon)
        imageTintList = ColorStateList.valueOf(Color.WHITE)
        contentDescription = description
        background = android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(c("#33FFFFFF")), null, rounded(Color.WHITE, 20f))
        setPadding(dp(10), dp(10), dp(10), dp(10))
        layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginStart = dp(2) }
        setOnClickListener { action() }
    }

    /** Edge-to-edge with the header painted under the status bar. */
    fun install(activity: Activity, root: LinearLayout, header: View) {
        WindowCompat.setDecorFitsSystemWindows(activity.window, false)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            header.setPadding(header.paddingLeft, bars.top + dp(8), header.paddingRight, header.paddingBottom)
            root.setPadding(bars.left, 0, bars.right, bars.bottom)
            insets
        }
        activity.setContentView(root)
        WindowCompat.getInsetsController(activity.window, root).isAppearanceLightStatusBars = false
        WindowCompat.getInsetsController(activity.window, root).isAppearanceLightNavigationBars = !night
        ViewCompat.requestApplyInsets(root)
    }

    fun rtl(language: String?) = (language ?: androidx.core.os.ConfigurationCompat.getLocales(context.resources.configuration)[0]?.language) in setOf("he", "iw")

    companion object {
        fun c(value: String) = Color.parseColor(value)
    }
}

/** The Hebrew fonts, loaded once for the whole app (loading them for every screen slowed moving between screens). */
object Fonts {
    @Volatile private var loaded: Array<Typeface>? = null

    private fun all(context: Context): Array<Typeface> = loaded ?: synchronized(this) {
        loaded ?: context.applicationContext.assets.let { a ->
            arrayOf(
                Typeface.createFromAsset(a, "fonts/NotoSansHebrew-Regular.ttf"),
                Typeface.createFromAsset(a, "fonts/NotoSansHebrew-Medium.ttf"),
                Typeface.createFromAsset(a, "fonts/NotoSansHebrew-SemiBold.ttf"),
            )
        }.also { loaded = it }
    }

    fun regular(context: Context) = all(context)[0]
    fun medium(context: Context) = all(context)[1]
    fun heading(context: Context) = all(context)[2]
}
