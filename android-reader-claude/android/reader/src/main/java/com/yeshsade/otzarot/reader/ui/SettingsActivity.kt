// Lean Otzarot reader — the approved settings screen (wording v4), ported from the earlier build. License: GPLv3.
package com.yeshsade.otzarot.reader.ui

import android.content.pm.ActivityInfo
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.Surface
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.yeshsade.otzarot.reader.R
import com.yeshsade.otzarot.reader.ReaderApp
import com.yeshsade.otzarot.reader.app
import com.yeshsade.otzarot.reader.data.AwakeMode
import com.yeshsade.otzarot.reader.data.FramePolicy
import com.yeshsade.otzarot.reader.data.ScrubberPolicy
import com.yeshsade.otzarot.reader.data.Settings
import com.google.android.material.bottomsheet.BottomSheetDialog
import org.json.JSONObject

/** The approved, small settings screen. */
class SettingsActivity : AppCompatActivity() {
    private lateinit var settings: Settings
    private lateinit var font: Typeface
    private lateinit var mediumFont: Typeface
    private lateinit var headingFont: Typeface
    private lateinit var wording: Map<String, JSONObject>
    private var sheet: BottomSheetDialog? = null
    private var scrollPosition = 0
    private lateinit var scroll: ScrollView

    private data class Choice(val value: String, val label: Int)
    private enum class Kind { RADIO, CHECKS, HOLD }
    private data class Setting(val key: String, val label: Int, val description: Int, val choices: List<Choice>,
                               val enabled: Boolean = true, val kind: Kind = Kind.RADIO)
    private data class Palette(val canvas: Int, val card: Int, val header: Int, val ink: Int, val muted: Int, val accent: Int, val border: Int, val selection: Int)
    private val night get() = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    private val colors get() = if (night) Palette(c("#171720"), c("#24232F"), c("#252047"), c("#F2EFF9"), c("#C1BBD0"), c("#C5B8FF"), c("#3E394C"), c("#443C64"))
        else Palette(c("#F1F0F8"), Color.WHITE, c("#342C69"), c("#252438"), c("#646279"), c("#57479F"), c("#E0DCEB"), c("#EEEAF9"))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = app.settings
        val approved = JSONObject(assets.open("settings-copy.json").bufferedReader().use { it.readText() })
        require(approved.getInt("version") == 4 && approved.getString("status") == "approved")
        val entries = approved.getJSONArray("entries")
        // New rows use a separate draft catalog until the owner approves their wording.
        val draft = JSONObject(assets.open("settings-copy-draft.json").bufferedReader().use { it.readText() }).getJSONArray("entries")
        wording = ((0 until draft.length()).map { draft.getJSONObject(it) } + (0 until entries.length()).map { entries.getJSONObject(it) })
            .associateBy { it.getString("id") }
        font = Typeface.createFromAsset(assets, "fonts/NotoSansHebrew-Regular.ttf")
        mediumFont = Typeface.createFromAsset(assets, "fonts/NotoSansHebrew-Medium.ttf")
        headingFont = Typeface.createFromAsset(assets, "fonts/NotoSansHebrew-SemiBold.ttf")
        scrollPosition = savedInstanceState?.getInt("settingsScroll", 0) ?: 0
        render()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("settingsScroll", scroll.scrollY)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        sheet?.dismiss()
        sheet = null
        super.onDestroy()
    }

    private fun definitions() = listOf(
        R.id.otzr_group_reading to listOf(
            Setting("scroll", R.id.otzr_scroll_label, R.id.otzr_scroll_desc, listOf(Choice("vertical", R.id.otzr_scroll_vertical), Choice("horizontal", R.id.otzr_scroll_horizontal))),
            Setting("resume", R.id.otzr_resume_label, R.id.otzr_resume_desc, listOf(Choice("last", R.id.otzr_resume_last), Choice("start", R.id.otzr_resume_start))),
            // Repeat marking requires the forthcoming inline pen/highlighter tools; do not offer a no-op control.
            Setting("marks", R.id.otzr_marks_label, R.id.otzr_marks_desc, listOf(Choice("once", R.id.otzr_marks_once), Choice("repeat", R.id.otzr_marks_repeat)), enabled = false),
            Setting("awake", R.id.otzr_awake_label, R.id.otzr_awake_desc, listOf(Choice("more", R.id.otzr_awake_more), Choice("always", R.id.otzr_awake_always), Choice("system", R.id.otzr_awake_system))),
            Setting("frame", R.id.otzr_frame_label, R.id.otzr_frame_desc, listOf(Choice(FramePolicy.AUTO, R.id.otzr_frame_auto), Choice(FramePolicy.TAP, R.id.otzr_frame_tap), Choice(FramePolicy.ALWAYS, R.id.otzr_frame_always))),
            Setting("info", R.id.otzr_info_label, R.id.otzr_info_desc, listOf(Choice("page", R.id.otzr_info_page), Choice("battery", R.id.otzr_info_battery), Choice("clock", R.id.otzr_info_clock)), kind = Kind.CHECKS),
            Setting("scrub_side", R.id.otzr_scrub_side_label, R.id.otzr_scrub_side_desc, listOf(Choice("right", R.id.otzr_scrub_right), Choice("left", R.id.otzr_scrub_left))),
            Setting("scrub_hold", R.id.otzr_scrub_hold_label, R.id.otzr_scrub_hold_desc, emptyList(), kind = Kind.HOLD),
        ),
        R.id.otzr_group_display to listOf(
            Setting("rotation", R.id.otzr_rotation_label, R.id.otzr_rotation_desc, listOf(Choice("phone", R.id.otzr_rotation_phone), Choice("lock", R.id.otzr_rotation_lock))),
            Setting("theme", R.id.otzr_theme_label, R.id.otzr_theme_desc, listOf(Choice("day", R.id.otzr_theme_day), Choice("night", R.id.otzr_theme_dark))),
        ),
        R.id.otzr_group_app to listOf(
            Setting("language", R.id.otzr_language_label, R.id.otzr_language_desc, listOf(Choice("he", R.id.otzr_language_he), Choice("en", R.id.otzr_language_en))),
        ),
    )

    private fun current(key: String): String = when (key) {
        "scroll" -> settings.scroll
        "resume" -> settings.resume
        "marks" -> "once"
        "awake" -> settings.awake.name.lowercase()
        "rotation" -> if (settings.lockedOrientation == Settings.UNLOCKED) "phone" else "lock"
        "theme" -> if (settings.night) "night" else "day"
        "language" -> settings.language ?: if (resources.configuration.localesLanguage() in setOf("he", "iw")) "he" else "en"
        "frame" -> settings.frameMode
        "scrub_side" -> settings.scrubberSide
        else -> error("Unknown setting")
    }

    private fun choose(key: String, value: String) {
        when (key) {
            "scroll" -> settings.scroll = value
            "resume" -> settings.resume = value
            "awake" -> settings.awake = AwakeMode.valueOf(value.uppercase())
            "rotation" -> settings.lockedOrientation = if (value == "phone") Settings.UNLOCKED else currentOrientation()
            "theme" -> {
                settings.night = value == "night"
                ReaderApp.applyTheme(settings)
            }
            "language" -> {
                settings.language = value
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(value))
            }
            "frame" -> settings.frameMode = value
            "scrub_side" -> settings.scrubberSide = value
        }
        scrollPosition = scroll.scrollY
        sheet?.dismiss()
        sheet = null
        render()
        // "hides by itself" was just chosen: show its time bar right away
        if (key == "frame" && value == FramePolicy.AUTO) definitions().flatMap { it.second }.firstOrNull { it.key == "frame" }?.let { showChoices(it) }
    }

    @Suppress("DEPRECATION")
    private fun currentOrientation(): Int {
        val rotation = if (Build.VERSION.SDK_INT >= 30) display?.rotation ?: Surface.ROTATION_0 else windowManager.defaultDisplay.rotation
        val reverse = rotation == Surface.ROTATION_180 || rotation == Surface.ROTATION_270
        return if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            if (reverse) ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        } else if (reverse) ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    }

    private fun render() {
        val root = column().apply { setBackgroundColor(colors.canvas); layoutDirection = if (language() == "he") View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val header = row().apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(12), 0)
            background = rounded(colors.header, 23f, bottomOnly = true)
        }
        header.addView(label(copy(R.id.otzr_settings_title), 25f, Color.WHITE, true).apply { typeface = headingFont }, LinearLayout.LayoutParams(0, dp(72), 1f))
        header.addView(icon(if (night) R.drawable.otzr_ic_sun else R.drawable.otzr_ic_moon, copy(if (night) R.id.otzr_theme_day else R.id.otzr_theme_dark), Color.WHITE) { choose("theme", if (night) "day" else "night") })
        header.addView(icon(R.drawable.otzr_ic_back, copy(R.id.otzr_settings_back), Color.WHITE) { finish() })
        root.addView(header)
        scroll = ScrollView(this).apply { isFillViewport = true; clipToPadding = false }
        val list = column().apply { setPadding(dp(14), dp(10), dp(14), dp(22)) }
        definitions().forEach { (title, settings) ->
            list.addView(label(copy(title), 13f, colors.accent, true).apply { setPadding(dp(9), dp(16), dp(9), dp(9)) })
            val card = column().apply { background = rounded(colors.card, 18f, border = colors.border) }
            settings.forEachIndexed { index, setting ->
                if (index != 0) card.addView(View(this).apply { setBackgroundColor(colors.border) }, LinearLayout.LayoutParams(-1, dp(1)))
                card.addView(settingRow(setting))
            }
            list.addView(card)
        }
        list.addView(label(copy(R.id.otzr_settings_footer), 12f, colors.muted).apply { setPadding(dp(9), dp(18), dp(9), 0) })
        scroll.addView(list)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            header.setPadding(dp(12), bars.top, dp(12), 0)
            root.setPadding(bars.left, 0, bars.right, bars.bottom)
            insets
        }
        setContentView(root)
        WindowCompat.getInsetsController(window, root).isAppearanceLightStatusBars = false
        WindowCompat.getInsetsController(window, root).isAppearanceLightNavigationBars = !night
        ViewCompat.requestApplyInsets(root)
        scroll.post { scroll.scrollTo(0, scrollPosition) }
    }

    private fun settingRow(setting: Setting): View = row().apply {
        tag = "setting_" + setting.key
        minimumHeight = dp(90)
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), dp(15), dp(16), dp(15))
        isEnabled = setting.enabled
        isFocusable = setting.enabled
        isClickable = setting.enabled
        alpha = if (setting.enabled) 1f else .55f
        val summary = summary(setting)
        val text = column()
        text.addView(label(copy(setting.label), 16f, colors.ink, true))
        text.addView(label(summary, 13f, colors.accent).apply { setPadding(0, dp(5), 0, 0) })
        contentDescription = copy(setting.label) + ", " + summary
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        text.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        addView(text, LinearLayout.LayoutParams(0, -2, 1f))
        addView(ImageView(this@SettingsActivity).apply { setImageResource(R.drawable.otzr_ic_chevron); imageTintList = ColorStateList.valueOf(colors.muted); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }, LinearLayout.LayoutParams(dp(18), dp(18)))
        if (setting.enabled) setOnClickListener { showChoices(setting) }
    }

    /** The line under a setting's name: what is chosen now. */
    private fun summary(setting: Setting): String = when (setting.kind) {
        Kind.RADIO -> choiceText(setting.choices.first { it.value == current(setting.key) })
        Kind.CHECKS -> setting.choices.filter { checked(it.value) }.joinToString(" · ") { copy(it.label) }
            .ifEmpty { copy(R.id.otzr_info_none) }
        Kind.HOLD -> holdText(settings.scrubberHoldMillis)
    }

    private fun choiceText(choice: Choice): String =
        if (choice.label == R.id.otzr_frame_auto) copy(choice.label).format(secondsText(settings.frameSeconds)) else copy(choice.label)

    private fun secondsText(seconds: Int) =
        if (seconds >= 60) copy(R.id.otzr_minute_one) else copy(R.id.otzr_seconds_n).format(seconds)

    private fun holdText(ms: Int): String = when (ms) {
        0 -> copy(R.id.otzr_hold_now)
        500 -> copy(R.id.otzr_hold_half)
        1000 -> copy(R.id.otzr_hold_one)
        1500 -> copy(R.id.otzr_hold_one_half)
        else -> copy(R.id.otzr_hold_n).format(java.text.DecimalFormat("0.##").format(ms / 1000.0))
    }

    private fun checked(value: String) = when (value) {
        "page" -> settings.infoPage
        "battery" -> settings.infoBattery
        else -> settings.infoClock
    }

    private fun setChecked(value: String, on: Boolean) = when (value) {
        "page" -> settings.infoPage = on
        "battery" -> settings.infoBattery = on
        else -> settings.infoClock = on
    }

    /** A bar inside a sheet; [text] describes the value, [changed] saves it. */
    private fun sliderBlock(max: Int, progress: Int, text: (Int) -> String, changed: (Int) -> Unit): View {
        val block = column().apply { setPadding(dp(10), dp(4), dp(10), dp(10)) }
        val value = label(text(progress), 15f, colors.accent, true)
        block.addView(value)
        block.addView(android.widget.SeekBar(this).apply {
            this.max = max
            this.progress = progress
            tag = "slider"
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: android.widget.SeekBar, p: Int, fromUser: Boolean) { value.text = text(p); changed(p) }
                override fun onStartTrackingTouch(bar: android.widget.SeekBar) {}
                override fun onStopTrackingTouch(bar: android.widget.SeekBar) {}
            })
        }, LinearLayout.LayoutParams(-1, dp(48)))
        return block
    }

    private fun showChoices(setting: Setting) {
        sheet?.dismiss()
        val dialog = BottomSheetDialog(this)
        val box = column().apply { setPadding(dp(12), dp(20), dp(12), dp(18)); background = rounded(colors.card, 24f, topOnly = true) }
        val title = row().apply { gravity = Gravity.CENTER_VERTICAL }
        title.addView(label(copy(setting.label), 18f, colors.ink, true), LinearLayout.LayoutParams(0, -2, 1f))
        title.addView(icon(R.drawable.otzr_ic_close, copy(R.id.otzr_settings_close), colors.ink) { dialog.dismiss() })
        box.addView(title)
        val description = copy(setting.description)
        if (description.isNotBlank()) box.addView(label(description, 13f, colors.muted).apply { setPadding(dp(9), dp(5), dp(9), dp(14)) })
        if (setting.kind == Kind.HOLD) {
            box.addView(sliderBlock(ScrubberPolicy.MAX_HOLD_MS / ScrubberPolicy.STEP_MS, settings.scrubberHoldMillis / ScrubberPolicy.STEP_MS,
                { holdText(it * ScrubberPolicy.STEP_MS) }) { settings.scrubberHoldMillis = it * ScrubberPolicy.STEP_MS })
        }
        if (setting.kind == Kind.CHECKS) setting.choices.forEach { choice ->
            val option = row().apply { minimumHeight = dp(58); gravity = Gravity.CENTER_VERTICAL; setPadding(dp(10), dp(8), dp(10), dp(8)) }
            val box2 = android.widget.CheckBox(this).apply {
                isChecked = checked(choice.value); buttonTintList = ColorStateList.valueOf(colors.accent)
                isClickable = false; isFocusable = false; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            option.addView(box2, LinearLayout.LayoutParams(dp(36), dp(42)))
            option.addView(label(copy(choice.label), 15f, colors.ink), LinearLayout.LayoutParams(0, -2, 1f))
            option.tag = "choice_" + choice.value
            option.isFocusable = true
            option.contentDescription = copy(choice.label)
            option.setOnClickListener { box2.isChecked = !box2.isChecked; setChecked(choice.value, box2.isChecked) }
            box.addView(option)
        }
        if (setting.kind == Kind.RADIO) setting.choices.forEach { choice ->
            val selected = choice.value == current(setting.key)
            val option = row().apply { minimumHeight = dp(58); gravity = Gravity.CENTER_VERTICAL; setPadding(dp(10), dp(8), dp(10), dp(8)); background = rounded(if (selected) colors.selection else Color.TRANSPARENT, 12f) }
            val radio = RadioButton(this).apply { isChecked = selected; isClickable = false; isFocusable = false; buttonTintList = ColorStateList.valueOf(if (selected) colors.accent else colors.ink); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
            option.addView(radio, LinearLayout.LayoutParams(dp(36), dp(42)))
            option.addView(label(choiceText(choice), 15f, if (selected) colors.accent else colors.ink), LinearLayout.LayoutParams(0, -2, 1f))
            option.tag = "choice_" + choice.value
            option.isFocusable = true
            option.contentDescription = copy(choice.label)
            option.setOnClickListener { choose(setting.key, choice.value) }
            box.addView(option)
            // "hides by itself": its time bar sits right under it, and the time can be changed in place
            if (choice.value == FramePolicy.AUTO && selected) {
                box.addView(sliderBlock(FramePolicy.MAX_SECONDS - FramePolicy.MIN_SECONDS, settings.frameSeconds - FramePolicy.MIN_SECONDS,
                    { secondsText(it + FramePolicy.MIN_SECONDS) }) { settings.frameSeconds = it + FramePolicy.MIN_SECONDS })
            }
        }
        dialog.setContentView(box)
        dialog.setOnShowListener { dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.setBackgroundColor(Color.TRANSPARENT) }
        dialog.setOnDismissListener {
            if (sheet === dialog) sheet = null
            // bars and check boxes save as they change; show the new value in the list
            if (setting.kind != Kind.RADIO || setting.key == "frame") { scrollPosition = scroll.scrollY; render() }
        }
        sheet = dialog
        dialog.show()
    }

    private fun icon(drawable: Int, description: String, tint: Int, action: () -> Unit) = ImageButton(this).apply {
        setImageResource(drawable)
        imageTintList = ColorStateList.valueOf(tint)
        contentDescription = description
        background = rounded(if (tint == Color.WHITE) c("#18FFFFFF") else Color.TRANSPARENT, 14f)
        setPadding(dp(12), dp(12), dp(12), dp(12))
        layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginStart = dp(4) }
        setOnClickListener { action() }
    }

    private fun label(value: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color); gravity = Gravity.START or Gravity.CENTER_VERTICAL
        typeface = if (bold) mediumFont else font
    }

    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun rounded(color: Int, radius: Float, border: Int? = null, topOnly: Boolean = false, bottomOnly: Boolean = false) = GradientDrawable().apply {
        setColor(color)
        val r = dp(radius).toFloat()
        cornerRadii = if (topOnly) floatArrayOf(r,r,r,r,0f,0f,0f,0f) else if (bottomOnly) floatArrayOf(0f,0f,0f,0f,r,r,r,r) else FloatArray(8) { r }
        border?.let { setStroke(dp(1), it) }
    }
    private fun dp(value: Int) = dp(value.toFloat())
    private fun dp(value: Float) = (value * resources.displayMetrics.density + .5f).toInt()
    private fun c(value: String) = Color.parseColor(value)
    private fun language() = settings.language ?: if (resources.configuration.localesLanguage() in setOf("he", "iw")) "he" else "en"
    private fun copy(id: Int) = wording.getValue(resources.getResourceEntryName(id).removePrefix("otzr_")).getString(language())
    @Suppress("DEPRECATION")
    private fun Configuration.localesLanguage() = if (Build.VERSION.SDK_INT >= 24) locales[0].language else locale.language
}
