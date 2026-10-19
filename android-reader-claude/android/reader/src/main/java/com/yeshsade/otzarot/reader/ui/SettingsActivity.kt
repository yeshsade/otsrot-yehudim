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
    private var turnApproved = false
    private lateinit var scroll: ScrollView
    /** The groups opened on this screen ("only the headings show; a tap opens the options below"). */
    private val open = linkedSetOf<String>()

    private data class Choice(val value: String, val label: Int)
    private enum class Kind { RADIO, FRAME, HANDLE }
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
        // Wording approved on 2026-10-15 adds to v4 and overrides it where both have the same text id.
        val added = JSONObject(assets.open("settings-copy-2026-10-15.json").bufferedReader().use { it.readText() })
        require(added.getString("status") == "approved")
        val extra = added.getJSONArray("entries")
        // and the wording approved on 2026-10-16
        val third = JSONObject(assets.open("settings-copy-2026-10-16.json").bufferedReader().use { it.readText() })
        require(third.getString("status") == "approved")
        val more = third.getJSONArray("entries")
        // and on 2026-10-17 (the reading group's new name)
        val fourth = JSONObject(assets.open("settings-copy-2026-10-17.json").bufferedReader().use { it.readText() })
        require(fourth.getString("status") == "approved")
        val latest = fourth.getJSONArray("entries")
        // and on 2026-10-18 ("עמודים מרובים")
        val fifth = JSONObject(assets.open("settings-copy-2026-10-18.json").bufferedReader().use { it.readText() })
        require(fifth.getString("status") == "approved")
        val newest = fifth.getJSONArray("entries")
        // the page-turn animation: its row shows only once the owner approved its wording
        val sixth = JSONObject(assets.open("settings-copy-2026-10-18b.json").bufferedReader().use { it.readText() })
        turnApproved = sixth.getString("status") == "approved"
        val turnWording = if (turnApproved) sixth.getJSONArray("entries") else org.json.JSONArray()
        wording = ((0 until entries.length()).map { entries.getJSONObject(it) } + (0 until extra.length()).map { extra.getJSONObject(it) } +
            (0 until more.length()).map { more.getJSONObject(it) } + (0 until latest.length()).map { latest.getJSONObject(it) } +
            (0 until newest.length()).map { newest.getJSONObject(it) } +
            (0 until turnWording.length()).map { turnWording.getJSONObject(it) })
            .associateBy { it.getString("id") }
        font = Fonts.regular(this)
        mediumFont = Fonts.medium(this)
        headingFont = Fonts.heading(this)
        scrollPosition = savedInstanceState?.getInt("settingsScroll", 0) ?: 0
        savedInstanceState?.getStringArrayList("settingsOpen")?.let { open.addAll(it) }
        render()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("settingsScroll", scroll.scrollY)
        outState.putStringArrayList("settingsOpen", ArrayList(open))
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        sheet?.dismiss()
        sheet = null
        super.onDestroy()
    }

    /**
     * Groups and rows. Order and grouping set by the owner on 2026-10-17 (the settings board): "flipping"
     * is a sub-group inside the reading screen.
     */
    private data class Group(val id: String, val title: Int, val settings: List<Setting>, val subgroups: List<Group> = emptyList())

    private fun groups() = listOf(
        Group("reading", R.id.otzr_group_reading, listOf(
            Setting("resume", R.id.otzr_resume_label, R.id.otzr_resume_desc, listOf(Choice("last", R.id.otzr_resume_last), Choice("start", R.id.otzr_resume_start))),
            // Repeat marking requires the forthcoming inline pen/highlighter tools; do not offer a no-op control.
            Setting("marks", R.id.otzr_marks_label, R.id.otzr_marks_desc, listOf(Choice("once", R.id.otzr_marks_once), Choice("repeat", R.id.otzr_marks_repeat)), enabled = false),
        ), subgroups = listOf(Group("paging", R.id.otzr_group_paging, listOf(
            Setting("scroll", R.id.otzr_scroll_label, R.id.otzr_scroll_desc, listOf(Choice("vertical", R.id.otzr_scroll_vertical), Choice("horizontal", R.id.otzr_scroll_horizontal))),
            Setting("pages", R.id.otzr_pages_label, R.id.otzr_pages_desc, listOf(Choice("single", R.id.otzr_pages_single), Choice("continuous", R.id.otzr_pages_continuous))),
        ) + (if (!turnApproved) emptyList() else listOf(
            Setting("turn", R.id.otzr_turn_label, R.id.otzr_turn_desc, listOf(Choice("none", R.id.otzr_turn_none),
                Choice("slide", R.id.otzr_turn_slide), Choice("cover", R.id.otzr_turn_cover), Choice("fade", R.id.otzr_turn_fade),
                Choice("depth", R.id.otzr_turn_depth), Choice("book", R.id.otzr_turn_book)), enabled = settings.singlePage),
        )) + listOf(
            Setting("handle", R.id.otzr_handle_label, R.id.otzr_handle_desc, emptyList(), kind = Kind.HANDLE),
        )))),
        Group("display", R.id.otzr_group_display, displayRows()),
        Group("app", R.id.otzr_group_app, appRows()),
    )

    /** Every row, for finding one by its key. */
    private fun definitions(): List<Pair<Int, List<Setting>>> = groups().flatMap { g -> listOf(g.title to g.settings) + g.subgroups.map { it.title to it.settings } }

    private fun displayRows() = plainGroups()[0].second
    private fun appRows() = plainGroups()[1].second

    private fun plainGroups() = listOf(
        R.id.otzr_group_display to listOf(
            Setting("awake", R.id.otzr_awake_label, R.id.otzr_awake_desc, listOf(Choice("more", R.id.otzr_awake_more), Choice("always", R.id.otzr_awake_always), Choice("system", R.id.otzr_awake_system))),
            Setting("frame", R.id.otzr_frame_label, R.id.otzr_frame_desc, listOf(Choice(FramePolicy.AUTO, R.id.otzr_frame_auto), Choice(FramePolicy.TAP, R.id.otzr_frame_tap), Choice(FramePolicy.ALWAYS, R.id.otzr_frame_always)), kind = Kind.FRAME),
            Setting("rotation", R.id.otzr_rotation_label, R.id.otzr_rotation_desc, listOf(Choice("phone", R.id.otzr_rotation_phone), Choice("lock", R.id.otzr_rotation_lock))),
            Setting("theme", R.id.otzr_theme_label, R.id.otzr_theme_desc, listOf(Choice("day", R.id.otzr_theme_day), Choice("night", R.id.otzr_theme_dark))),
        ),
        R.id.otzr_group_app to listOf(
            Setting("language", R.id.otzr_language_label, R.id.otzr_language_desc, listOf(Choice("he", R.id.otzr_language_he), Choice("en", R.id.otzr_language_en))),
            Setting("sortsize", R.id.otzr_sortsize_label, R.id.otzr_sortsize_desc, listOf(Choice("off", R.id.otzr_sortsize_off), Choice("on", R.id.otzr_sortsize_on))),
        ),
    )

    private fun current(key: String): String = when (key) {
        "scroll" -> settings.scroll
        "pages" -> settings.pages
        "turn" -> settings.pageTurn
        "resume" -> settings.resume
        "marks" -> "once"
        "awake" -> settings.awake.name.lowercase()
        "rotation" -> if (settings.lockedOrientation == Settings.UNLOCKED) "phone" else "lock"
        "theme" -> if (settings.night) "night" else "day"
        "language" -> settings.language ?: if (resources.configuration.localesLanguage() in setOf("he", "iw")) "he" else "en"
        "frame" -> settings.frameMode
        "sortsize" -> if (settings.sortBySize) "on" else "off"
        else -> error("Unknown setting")
    }

    private fun choose(key: String, value: String) {
        when (key) {
            "scroll" -> settings.scroll = value
            "pages" -> settings.pages = value
            "turn" -> settings.pageTurn = value
            "resume" -> settings.resume = value
            "awake" -> settings.awake = AwakeMode.valueOf(value.uppercase())
            "rotation" -> settings.lockedOrientation = if (value == "phone") Settings.UNLOCKED else ScreenOrientation.current(this)
            "theme" -> {
                settings.night = value == "night"
                ReaderApp.applyTheme(settings)
            }
            "language" -> {
                settings.language = value
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(value))
            }
            "frame" -> settings.frameMode = value
            "sortsize" -> settings.sortBySize = value == "on"
        }
        scrollPosition = scroll.scrollY
        sheet?.dismiss()
        sheet = null
        render()
        // the frame's sheet holds more than the choice (its time bar, the thin line): keep it open
        if (key == "frame") definitions().flatMap { it.second }.firstOrNull { it.key == "frame" }?.let { showChoices(it) }
    }

    /** The menu (☰): the same as on every screen, without settings (this is it). */
    var lastMenu: Drawer.Shown? = null
        private set

    private fun openMenu() {
        val ui = Ui(this)
        lastMenu = Drawer.show(this, ui, null, null,
            top = listOf(AppMenu.rotationLock(this),
                Drawer.Item(R.drawable.ic_list, getString(R.string.menu_files), "menu_files") {
                    startActivity(android.content.Intent(this, LibraryActivity::class.java).putExtra(LibraryActivity.EXTRA_STAY, true)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP))
                    finish()
                }),
            more = emptyList(), pinned = emptyList())
    }


    private fun render() {
        val root = column().apply { setBackgroundColor(colors.canvas); layoutDirection = if (language() == "he") View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        // ☰ and day/night on the left, as on every screen; here the menu has no "settings" (owner, 2026-10-17/18)
        val (header, left, _) = Ui(this).menuBar(this) { openMenu() }
        left.addView(Ui(this).nightButton(this))
        root.addView(header)
        scroll = ScrollView(this).apply { isFillViewport = true; clipToPadding = false }
        val list = column().apply { setPadding(dp(14), dp(10), dp(14), dp(22)) }
        groups().forEach { group -> list.addView(groupView(group, nested = false)) }
        list.addView(label(copy(R.id.otzr_settings_footer), 12f, colors.muted).apply { setPadding(dp(9), dp(18), dp(9), 0) })
        scroll.addView(list)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            header.setPadding(dp(12), bars.top + dp(8), dp(12), dp(10))
            root.setPadding(bars.left, 0, bars.right, bars.bottom)
            insets
        }
        setContentView(root)
        WindowCompat.getInsetsController(window, root).isAppearanceLightStatusBars = false
        WindowCompat.getInsetsController(window, root).isAppearanceLightNavigationBars = !night
        ViewCompat.requestApplyInsets(root)
        scroll.post { scroll.scrollTo(0, scrollPosition) }
    }

    /**
     * One heading. Closed, only its bar shows; a tap opens its options right under it, another tap closes
     * them (owner, 2026-10-18). A sub-group (flipping, inside the reading screen) opens the same way.
     */
    private fun groupView(group: Group, nested: Boolean): View {
        val box = column().apply {
            if (!nested) background = rounded(colors.card, 18f)
            tag = "groupbox_" + group.id
        }
        val isOpen = group.id in open
        val bar = row().apply {
            tag = "group_" + group.id
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(if (nested) 54 else 62)
            setPadding(dp(18), dp(8), dp(14), dp(8))
            isClickable = true; isFocusable = true
            contentDescription = copy(group.title)
            setOnClickListener {
                if (!open.add(group.id)) open.remove(group.id)
                scrollPosition = scroll.scrollY
                render()
            }
        }
        bar.addView(label(copy(group.title), if (nested) 16f else 18f, if (nested) colors.accent else colors.ink, true), LinearLayout.LayoutParams(0, -2, 1f))
        bar.addView(ImageView(this).apply {
            setImageResource(R.drawable.otzr_ic_chevron)
            imageTintList = ColorStateList.valueOf(colors.muted)
            // points to the side while closed, down while open
            rotation = if (!isOpen) 0f else if (language() == "he") -90f else 90f
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(18), dp(18)))
        box.addView(bar)
        val content = column().apply { visibility = if (isOpen) View.VISIBLE else View.GONE }
        group.settings.forEach { setting ->
            content.addView(View(this).apply { setBackgroundColor(colors.border) }, LinearLayout.LayoutParams(-1, dp(1)).apply { marginStart = dp(18); marginEnd = dp(18) })
            content.addView(settingRow(setting))
        }
        group.subgroups.forEach { sub ->
            content.addView(View(this).apply { setBackgroundColor(colors.border) }, LinearLayout.LayoutParams(-1, dp(1)).apply { marginStart = dp(18); marginEnd = dp(18) })
            content.addView(groupView(sub, nested = true))
        }
        box.addView(content)
        if (nested) return box
        return box.also { it.layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) } }
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
        // a row that can't be used yet says why, instead of its value ("works only with One page")
        val summary = if (!setting.enabled && setting.key == "turn") copy(setting.description) else summary(setting)
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

    /** The line(s) under a setting's name: what is chosen now. */
    private fun summary(setting: Setting): String = when (setting.kind) {
        Kind.RADIO, Kind.FRAME -> choiceText(setting.choices.first { it.value == current(setting.key) })
        Kind.HANDLE -> listOf(
            copy(R.id.otzr_handle_place_value).format(copy(if (settings.scrubberSide == "left") R.id.otzr_scrub_left else R.id.otzr_scrub_right)),
            copy(R.id.otzr_hold_value).format(holdNumber(settings.scrubberHoldMillis)),
            copy(if (settings.scrubberAlways) R.id.otzr_handle_show_always else R.id.otzr_handle_show_frame),
        ).joinToString("\n")
    }

    private fun choiceText(choice: Choice): String =
        if (choice.label == R.id.otzr_frame_auto) copy(choice.label).format(secondsText(settings.frameSeconds)) else copy(choice.label)

    private fun secondsText(seconds: Int) =
        if (seconds >= 60) copy(R.id.otzr_minute_one) else copy(R.id.otzr_seconds_n).format(seconds)

    /** 0, 0.25, 0.5 … 2 — numbers only, as the owner asked. */
    private fun holdNumber(ms: Int): String = java.text.DecimalFormat("0.##", java.text.DecimalFormatSymbols(java.util.Locale.US)).format(ms / 1000.0)

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
        // the page-turn line ("works only with One page") belongs only under the grey row, not in its open sheet
        val description = if (setting.key == "turn") "" else copy(setting.description)
        if (description.isNotBlank()) box.addView(label(description, 13f, colors.muted).apply { setPadding(dp(9), dp(5), dp(9), if (setting.kind == Kind.FRAME) dp(2) else dp(14)) })
        if (setting.kind == Kind.FRAME) box.addView(label(copy(R.id.otzr_frame_desc_tap), 13f, colors.ink, true).apply { setPadding(dp(9), 0, dp(9), dp(14)) })
        if (setting.kind == Kind.HANDLE) handleSheet(box)
        if (setting.kind == Kind.RADIO || setting.kind == Kind.FRAME) setting.choices.forEach { choice ->
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
        if (setting.kind == Kind.FRAME) {
            // the thin line shown while the frame is hidden: its own part at the bottom of the same sheet
            box.addView(divider())
            box.addView(label(copy(R.id.otzr_info_label), 15f, colors.ink, true).apply { setPadding(dp(10), dp(6), dp(10), dp(4)) })
            listOf("page" to R.id.otzr_info_page, "battery" to R.id.otzr_info_battery, "clock" to R.id.otzr_info_clock).forEach { (value, text) ->
                box.addView(checkRow(copy(text), checked(value), "choice_$value") { setChecked(value, it) })
            }
        }
        dialog.setContentView(box)
        dialog.setOnShowListener { dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.setBackgroundColor(Color.TRANSPARENT) }
        dialog.setOnDismissListener {
            if (sheet === dialog) sheet = null
            // bars and check boxes save as they change; show the new value in the list
            if (setting.kind != Kind.RADIO) { scrollPosition = scroll.scrollY; render() }
        }
        sheet = dialog
        dialog.show()
    }

    /** The page handle's sheet: position, when it shows, hold time — three parts with lines between them. */
    private fun handleSheet(box: LinearLayout) {
        fun radios(title: Int, options: List<Pair<String, Int>>, selected: () -> String, choose: (String) -> Unit) {
            box.addView(label(copy(title), 15f, colors.ink, true).apply { setPadding(dp(10), dp(6), dp(10), dp(2)) })
            val row = row().apply { setPadding(dp(4), 0, dp(4), 0) }
            val buttons = options.map { (value, text) ->
                val option = row().apply { gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(52); setPadding(dp(6), 0, dp(10), 0); tag = "choice_$value" }
                val radio = RadioButton(this).apply { isClickable = false; isFocusable = false; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
                option.addView(radio, LinearLayout.LayoutParams(dp(36), dp(42)))
                val name = label(copy(text), 15f, colors.ink)
                option.addView(name)
                option.contentDescription = copy(text)
                row.addView(option, LinearLayout.LayoutParams(0, -2, 1f))
                Triple(value, radio, name) to option
            }
            fun paint() = buttons.forEach { (t, _) ->
                val on = t.first == selected()
                t.second.isChecked = on
                t.second.buttonTintList = ColorStateList.valueOf(if (on) colors.accent else colors.ink)
                t.third.setTextColor(if (on) colors.accent else colors.ink)
            }
            buttons.forEach { (t, option) -> option.setOnClickListener { choose(t.first); paint() } }
            paint()
            box.addView(row)
        }
        radios(R.id.otzr_handle_place_title, listOf("right" to R.id.otzr_scrub_right, "left" to R.id.otzr_scrub_left),
            { settings.scrubberSide }) { settings.scrubberSide = it }
        box.addView(divider())
        radios(R.id.otzr_handle_show_title, listOf("frame" to R.id.otzr_handle_show_frame, "always" to R.id.otzr_handle_show_always),
            { if (settings.scrubberAlways) "always" else "frame" }) { settings.scrubberAlways = it == "always" }
        box.addView(divider())
        box.addView(label(copy(R.id.otzr_hold_title), 15f, colors.ink, true).apply { setPadding(dp(10), dp(6), dp(10), dp(2)) })
        box.addView(label(copy(R.id.otzr_hold_desc), 13f, colors.muted).apply { setPadding(dp(10), 0, dp(10), dp(6)) })
        box.addView(holdBar())
    }

    /** The hold-time bar with its numbers under it: 0 … 2, the chosen one bold, "seconds" once at the end. */
    private fun holdBar(): View {
        val steps = ScrubberPolicy.MAX_HOLD_MS / ScrubberPolicy.STEP_MS
        val labels = (0..steps).map { holdNumber(it * ScrubberPolicy.STEP_MS) }
        val bar = android.widget.SeekBar(this).apply { max = steps; progress = settings.scrubberHoldMillis / ScrubberPolicy.STEP_MS; tag = "slider" }
        val ticks = TickLabels(this, bar, labels, font, mediumFont, colors.muted, colors.accent)
        bar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(b: android.widget.SeekBar, p: Int, fromUser: Boolean) {
                settings.scrubberHoldMillis = p * ScrubberPolicy.STEP_MS; ticks.invalidate()
            }
            override fun onStartTrackingTouch(b: android.widget.SeekBar) {}
            override fun onStopTrackingTouch(b: android.widget.SeekBar) {}
        })
        val track = column()
        track.addView(bar, LinearLayout.LayoutParams(-1, dp(44)))
        track.addView(ticks, LinearLayout.LayoutParams(-1, dp(24)))
        val row = row().apply { gravity = Gravity.BOTTOM; setPadding(dp(4), 0, dp(10), dp(10)) }
        row.addView(track, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(label(copy(R.id.otzr_seconds_word), 13f, colors.muted).apply { setPadding(dp(4), 0, 0, dp(3)) })
        return row
    }

    private fun divider() = View(this).apply { setBackgroundColor(colors.border) }.also {
        it.layoutParams = LinearLayout.LayoutParams(-1, dp(1)).apply { setMargins(dp(10), dp(10), dp(10), dp(10)) }
    }

    private fun checkRow(text: String, on: Boolean, tag: String, changed: (Boolean) -> Unit): View {
        val option = row().apply { minimumHeight = dp(52); gravity = Gravity.CENTER_VERTICAL; setPadding(dp(10), dp(4), dp(10), dp(4)); this.tag = tag }
        val box = android.widget.CheckBox(this).apply {
            isChecked = on; buttonTintList = ColorStateList.valueOf(colors.accent)
            isClickable = false; isFocusable = false; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        option.addView(box, LinearLayout.LayoutParams(dp(36), dp(42)))
        option.addView(label(text, 15f, colors.ink), LinearLayout.LayoutParams(0, -2, 1f))
        option.isFocusable = true
        option.contentDescription = text
        option.setOnClickListener { box.isChecked = !box.isChecked; changed(box.isChecked) }
        return option
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

/** Numbers under a bar, each exactly under its stop, the chosen one bold. Follows the bar's direction (RTL too). */
@android.annotation.SuppressLint("ViewConstructor")
private class TickLabels(
    context: android.content.Context, private val bar: android.widget.SeekBar, private val labels: List<String>,
    private val regular: Typeface, private val bold: Typeface, private val color: Int, private val chosen: Int,
) : View(context) {
    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = android.graphics.Paint.Align.CENTER
        textSize = 11.5f * resources.displayMetrics.scaledDensity
    }

    override fun onDraw(canvas: android.graphics.Canvas) {
        val start = bar.paddingLeft.toFloat(); val end = (width - bar.paddingRight).toFloat()
        val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
        val last = labels.size - 1
        labels.forEachIndexed { i, text ->
            val f = i.toFloat() / last
            val x = if (rtl) end - f * (end - start) else start + f * (end - start)
            val on = i == bar.progress
            paint.typeface = if (on) bold else regular
            paint.color = if (on) chosen else color
            paint.isFakeBoldText = on
            canvas.drawText(text, x, height * .7f, paint)
        }
    }
}
