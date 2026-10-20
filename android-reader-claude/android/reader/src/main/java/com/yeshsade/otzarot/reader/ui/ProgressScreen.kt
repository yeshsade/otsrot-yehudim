// Lean Otzarot reader. License: GPLv3.
package com.yeshsade.otzarot.reader.ui

import android.animation.ValueAnimator
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.yeshsade.otzarot.reader.R
import com.yeshsade.otzarot.reader.data.OpenDay
import com.yeshsade.otzarot.reader.data.Progress
import com.yeshsade.otzarot.reader.data.ProgressMap
import com.yeshsade.otzarot.reader.data.Settings
import kotlin.random.Random

/**
 * The progress screen (stage 2; owner's decisions 2026-10-14…19): one screen with two tabs, "marking" and
 * "progress". Marking shows, for the day, each chosen topic "like the printed page": its columns side by side,
 * three places under each, the places a column does not have greyed out (owner, 2026-10-19, "2"). Progress shows
 * a big gauge for the day and a small one for the week, and the details by day or by topic.
 *
 * The boxes' state lives only in the PDF: [ticked] is what the PDF holds now, and every change goes through
 * [write], which ticks the real boxes (and saves the file like a tap on the page does).
 */
class ProgressScreen(
    private val activity: Activity,
    private val ui: Ui,
    private val settings: Settings,
    private val map: ProgressMap,
    private val ticked: MutableMap<String, Boolean>,
    private val write: (Map<String, Boolean>) -> Unit,
) {
    enum class Tab { MARK, PROGRESS }

    private lateinit var dialog: Dialog
    private lateinit var body: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var tabs: LinearLayout
    private lateinit var dayChips: LinearLayout
    private lateinit var overlay: FrameLayout
    private var tab = Tab.MARK
    private var day: String? = null
    private var byTopics = false
    private var celebrated = mutableSetOf<String?>()
    private val colors = ui.colors

    private val chosen: Set<String> get() = settings.studyTopics.orEmpty()
    private fun progress() = Progress(map, chosen) { ticked[it] == true }

    /** The dialog (for tests). */
    val shown: Dialog get() = dialog

    fun show(start: Tab): ProgressScreen {
        tab = start
        val days = progress().days.map { it.id }
        val (y, m, d) = OpenDay.today()
        day = OpenDay.choose(days, map.booklet.weekStart, y, m, d, ticked.values.any { it })
        build()
        // the first time: "what do you learn?" — on the next turn of the main thread, so neither window waits for the other
        if (settings.studyTopics == null) dialog.window?.decorView?.post { if (dialog.isShowing) chooseTopics(first = true) }
        return this
    }

    // ---------- frame ----------

    private fun build() {
        dialog = Dialog(activity, if (ui.night) android.R.style.Theme_Material_NoActionBar else android.R.style.Theme_Material_Light_NoActionBar)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val root = ui.column().apply { setBackgroundColor(colors.canvas); tag = "progress_screen" }
        val (header, titles) = ui.header(activity.getString(R.string.pr_title), listOfNotNull(map.booklet.parsha, map.booklet.issue?.let { "גיליון $it" }).joinToString(" · ").ifEmpty { null })
        header.setPadding(header.paddingLeft, ui.dp(14), header.paddingRight, header.paddingBottom)
        header.addView(textButton(activity.getString(R.string.pr_topics), Color.WHITE, "progress_topics") { chooseTopics(first = false) })
        header.addView(ui.headerButton(R.drawable.otzr_ic_close, activity.getString(R.string.pr_close)) { dialog.dismiss() }.apply { tag = "progress_close" })
        root.addView(header, LinearLayout.LayoutParams(-1, -2))
        tabs = ui.row().apply {
            background = ui.rounded(colors.selection, 14f)
            setPadding(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(4))
        }
        root.addView(tabs, LinearLayout.LayoutParams(-1, -2).apply { setMargins(ui.dp(16), ui.dp(12), ui.dp(16), 0) })
        dayChips = ui.row()
        val chipsScroll = android.widget.HorizontalScrollView(activity).apply { isHorizontalScrollBarEnabled = false; addView(dayChips) }
        root.addView(chipsScroll, LinearLayout.LayoutParams(-1, -2).apply { setMargins(ui.dp(12), ui.dp(10), ui.dp(12), 0) })
        scroll = ScrollView(activity).apply { isFillViewport = true; clipToPadding = false }
        body = ui.column().apply { setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(28)) }
        scroll.addView(body)
        overlay = FrameLayout(activity)
        overlay.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        root.addView(overlay, LinearLayout.LayoutParams(-1, 0, 1f))
        dialog.setContentView(root)
        dialog.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            statusBarColor = colors.header
            addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        }
        render()
        dialog.show()
    }

    private fun render() {
        tabs.removeAllViews()
        for ((t, text, tagName) in listOf(Triple(Tab.MARK, R.string.pr_tab_mark, "progress_tab_mark"), Triple(Tab.PROGRESS, R.string.pr_tab_progress, "progress_tab_progress"))) {
            val on = t == tab
            tabs.addView(ui.label(activity.getString(text), 15.5f, if (on) colors.accent else colors.muted, true).apply {
                gravity = Gravity.CENTER
                tag = tagName
                minimumHeight = ui.dp(40)
                background = if (on) ui.rounded(colors.card, 11f) else null
                setOnClickListener { if (tab != t) { tab = t; render(); scroll.scrollTo(0, 0) } }
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        dayChips.removeAllViews()
        val p = progress()
        for (d in p.days) chip(shortDay(d.label), d.id == day, "progress_day_${d.id}") { day = d.id; render() }
        chip(activity.getString(R.string.pr_whole_week), day == null, "progress_day_week") { day = null; render() }
        ++renders; counters.clear()
        body.removeAllViews()
        if (tab == Tab.MARK) renderMarking() else renderProgress()
    }

    private fun chip(text: String, on: Boolean, tagName: String, action: () -> Unit) {
        dayChips.addView(ui.label(text, 14.5f, if (on) Color.WHITE else colors.ink, on).apply {
            tag = tagName
            gravity = Gravity.CENTER
            setPadding(ui.dp(14), ui.dp(7), ui.dp(14), ui.dp(7))
            background = if (on) ui.rounded(colors.accent, 18f) else ui.rounded(colors.card, 18f, colors.border)
            setOnClickListener { action(); scroll.scrollTo(0, 0) }
        }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = ui.dp(6) })
    }

    private fun shortDay(label: String) = label.removePrefix("יום ").replace("שבת קודש", "שבת")

    // ---------- marking ----------

    private fun renderMarking() {
        body.addView(ui.label(activity.getString(R.string.pr_explain), 14f, colors.muted).apply { setPadding(ui.dp(6), ui.dp(4), ui.dp(6), ui.dp(4)) })
        val topics = progress().topics.filter { it.id in chosen }
        if (topics.isEmpty()) {
            body.addView(ui.label(activity.getString(R.string.pr_nothing_chosen), 15f, colors.ink).apply { setPadding(ui.dp(6), ui.dp(16), ui.dp(6), 0) })
            return
        }
        // a card per turn of the main thread: a whole week has hundreds of boxes, and the first cards show at once
        val generation = ++renders
        val queue = topics.mapNotNull { topic ->
            map.checks.filter { it.topicId == topic.id && (day == null || it.dayId == day) }.takeIf { it.isNotEmpty() }?.let { topic to it }
        }.toMutableList()
        fun next() {
            if (generation != renders || queue.isEmpty() || !dialog.isShowing) return
            val (topic, checks) = queue.removeAt(0)
            val card = ui.column().apply {
                background = ui.rounded(colors.card, 18f)
                setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(14))
                tag = "progress_topic_${topic.id}"
            }
            fillCard(card, topic, checks)
            body.addView(card, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(10) })
            body.post { next() }
        }
        next()
    }

    private var renders = 0
    /** Each card's counters, refreshed when one box changes (without building the card again). */
    private val counters = HashMap<LinearLayout, MutableList<() -> Unit>>()

    /** Whether all of the screen is built (for tests). */
    val built: Boolean get() = body.childCount > 0 && renders > 0

    private fun fillCard(card: LinearLayout, topic: ProgressMap.Topic, checks: List<ProgressMap.Check>, undo: Map<String, Boolean>? = null) {
        card.removeAllViews()
        val refresh = mutableListOf<() -> Unit>().also { counters[card] = it }
        val head = ui.row()
        head.addView(ui.label(topic.label, 17f, colors.ink, true).apply { typeface = ui.heading })
        val count = ui.label("", 13.5f, colors.muted)
        refresh += { count.text = "  " + activity.getString(R.string.pr_count_short, checks.count { ticked[it.field] == true }, checks.size) }
        head.addView(count)
        head.addView(View(activity), LinearLayout.LayoutParams(0, 1, 1f))
        if (undo != null) {
            head.addView(textButton(activity.getString(R.string.pr_undo), colors.accent, "progress_undo") {
                change(undo); fillCard(card, topic, checks)
            })
            card.postDelayed({ if (card.isAttachedToWindow) fillCard(card, topic, checks) }, UNDO_MS)
        } else if (checks.any { ticked[it.field] != true }) {
            head.addView(textButton(activity.getString(R.string.pr_mark_all), colors.accent, "progress_mark_all") {
                val before = checks.associate { it.field to (ticked[it.field] == true) }
                change(checks.associate { it.field to true })
                fillCard(card, topic, checks, before)
            })
        }
        card.addView(head)
        refresh.forEach { it() }
        val labelled = checks.any { it.label != null }
        when {
            labelled -> card.addView(namedBoxes(card, topic, checks))
            checks.size == 1 -> card.addView(ui.row().apply { setPadding(0, ui.dp(10), 0, 0); addView(box(card, topic, checks, checks[0])) })
            else -> {
                // a row for each page of the day, under its title ("עמוד 1"), the columns side by side as printed
                val days = checks.groupBy { it.dayId }
                for ((_, ofDay) in days) {
                    val rows = ofDay.groupBy { it.pageInDay }.toSortedMap()
                    for ((_, row) in rows) {
                        row.firstOrNull()?.group?.let { g ->
                            val label = ui.label("", 13.5f, colors.muted, true).apply { setPadding(0, ui.dp(12), 0, 0) }
                            refresh += { label.text = "$g  " + activity.getString(R.string.pr_count_short, row.count { ticked[it.field] == true }, row.size) }
                            card.addView(label)
                        }
                        card.addView(columns(card, topic, checks, row))
                    }
                }
            }
        }
        refresh.forEach { it() }
    }

    private fun columns(card: LinearLayout, topic: ProgressMap.Topic, all: List<ProgressMap.Check>, row: List<ProgressMap.Check>): View {
        val line = ui.row().apply { setPadding(0, ui.dp(10), 0, 0); gravity = Gravity.TOP }
        val byColumn = row.groupBy { it.column }.toSortedMap()
        val most = maxOf(3, byColumn.values.maxOf { it.size })
        // column 1 is the rightmost, as on the page: in a right-to-left row the first child is on the right
        for ((column, boxes) in byColumn) {
            val col = ui.column().apply { gravity = Gravity.CENTER_HORIZONTAL }
            col.addView(ui.label(activity.getString(R.string.pr_column, column), 12.5f, colors.muted).apply { gravity = Gravity.CENTER })
            boxes.sortedBy { it.order }.forEach { col.addView(box(card, topic, all, it), boxParams()) }
            repeat(most - boxes.size) { col.addView(Ghost(activity, ui), boxParams()) }
            line.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        }
        return line
    }

    private fun namedBoxes(card: LinearLayout, topic: ProgressMap.Topic, checks: List<ProgressMap.Check>): View {
        val line = ui.row().apply { setPadding(0, ui.dp(10), 0, 0) }
        for (c in checks.sortedBy { it.order }) {
            val item = ui.column().apply { gravity = Gravity.CENTER_HORIZONTAL }
            item.addView(box(card, topic, checks, c), boxParams())
            item.addView(ui.label(c.label ?: "", 13f, colors.ink).apply { gravity = Gravity.CENTER })
            line.addView(item, LinearLayout.LayoutParams(0, -2, 1f))
        }
        return line
    }

    private fun boxParams() = LinearLayout.LayoutParams(ui.dp(40), ui.dp(40)).apply { topMargin = ui.dp(6); gravity = Gravity.CENTER_HORIZONTAL }

    /** A real check box: an empty rounded square, or filled with a ✓ — so it reads as "tick here". */
    private fun box(card: LinearLayout, topic: ProgressMap.Topic, all: List<ProgressMap.Check>, c: ProgressMap.Check): View =
        Box(activity, ui, ticked[c.field] == true, c.label ?: topic.label).apply {
            setOnClickListener {
                val now = !checked
                change(mapOf(c.field to now))
                checked = now
                counters[card]?.forEach { it() }
            }
        }

    /** A check box drawn by hand (hundreds of them in a week): an empty rounded square, or filled with a ✓. */
    private class Box(context: Context, private val ui: Ui, on: Boolean, private val name: String) : View(context) {
        var checked = on
            set(v) { field = v; contentDescription = name + if (v) " ✓" else ""; invalidate() }
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
        private val tick = android.graphics.Path()
        init { tag = "progress_box"; isClickable = true; isFocusable = true; contentDescription = name + if (on) " ✓" else "" }
        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat(); val h = height.toFloat(); val r = ui.dp(9).toFloat(); val line = ui.dp(2).toFloat()
            if (checked) {
                paint.style = Paint.Style.FILL; paint.color = ui.colors.accent
                canvas.drawRoundRect(0f, 0f, w, h, r, r, paint)
                paint.style = Paint.Style.STROKE; paint.color = Color.WHITE; paint.strokeWidth = line * 1.4f
                tick.reset(); tick.moveTo(w * .27f, h * .52f); tick.lineTo(w * .44f, h * .68f); tick.lineTo(w * .74f, h * .35f)
                canvas.drawPath(tick, paint)
            } else {
                paint.style = Paint.Style.FILL; paint.color = ui.colors.card
                canvas.drawRoundRect(0f, 0f, w, h, r, r, paint)
                paint.style = Paint.Style.STROKE; paint.color = ui.colors.accent; paint.strokeWidth = line
                canvas.drawRoundRect(line / 2, line / 2, w - line / 2, h - line / 2, r, r, paint)
            }
        }
    }

    private fun change(changes: Map<String, Boolean>) {
        val real = changes.filter { (f, v) -> (ticked[f] == true) != v }
        if (real.isEmpty()) return
        val wasComplete = day?.let { progress().day(it).complete } ?: progress().week().complete
        real.forEach { (f, v) -> ticked[f] = v }
        write(real)
        val nowComplete = day?.let { progress().day(it).complete } ?: progress().week().complete
        // confetti once, when the day is done (owner: "קונפטי ב־100%")
        if (!wasComplete && nowComplete && day !in celebrated) { celebrated += day; confetti() }
    }

    // ---------- progress ----------

    private fun renderProgress() {
        val p = progress()
        val today = day?.let { p.day(it) } ?: p.week()
        val dayGauge = Gauge(activity, ui, big = true).apply {
            tag = "progress_gauge_day"
            title = day?.let { map.day(it)?.label } ?: activity.getString(R.string.pr_whole_week)
            fraction = today.fraction
            caption = activity.getString(R.string.pr_count, today.done, today.total)
        }
        body.addView(card { it.addView(dayGauge, LinearLayout.LayoutParams(-1, -2)) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(10) })
        if (day != null) {
            val week = p.week()
            val weekGauge = Gauge(activity, ui, big = false).apply {
                tag = "progress_gauge_week"
                title = activity.getString(R.string.pr_week)
                fraction = week.fraction
                caption = activity.getString(R.string.pr_count_short, week.done, week.total)
            }
            body.addView(card { it.addView(weekGauge, LinearLayout.LayoutParams(-1, -2)) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(10) })
        }
        // details: by day or by topic
        val details = card { c ->
            val toggle = ui.row().apply { background = ui.rounded(colors.selection, 12f); setPadding(ui.dp(3), ui.dp(3), ui.dp(3), ui.dp(3)) }
            for ((topics, text) in listOf(false to R.string.pr_by_days, true to R.string.pr_by_topics)) {
                val on = topics == byTopics
                toggle.addView(ui.label(activity.getString(text), 14f, if (on) colors.accent else colors.muted, true).apply {
                    gravity = Gravity.CENTER; minimumHeight = ui.dp(34)
                    background = if (on) ui.rounded(colors.card, 10f) else null
                    tag = if (topics) "progress_by_topics" else "progress_by_days"
                    setOnClickListener { byTopics = topics; render() }
                }, LinearLayout.LayoutParams(0, -2, 1f))
            }
            c.addView(toggle)
            if (byTopics) {
                p.topics.filter { it.id in chosen }.forEach { t ->
                    c.addView(barRow(t.label, day?.let { p.topicInDay(t.id, it) } ?: p.topicInWeek(t.id)))
                }
            } else {
                p.days.forEach { d -> c.addView(barRow(d.label, p.day(d.id))) }
            }
        }
        body.addView(details, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(10) })
    }

    private fun barRow(label: String, count: Progress.Count): View = ui.column().apply {
        setPadding(ui.dp(2), ui.dp(12), ui.dp(2), 0)
        val top = ui.row()
        top.addView(ui.label(label, 15f, colors.ink), LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(ui.label(activity.getString(R.string.pr_count_short, count.done, count.total), 13.5f, colors.muted))
        addView(top)
        val bar = FrameLayout(activity).apply { background = ui.rounded(colors.selection, 4f) }
        val fill = View(activity).apply { background = ui.rounded(if (count.fraction < Gauge.CALM_FROM) Gauge.PINK else Gauge.CALM, 4f) }
        bar.addView(fill, FrameLayout.LayoutParams(0, -1, Gravity.START))
        bar.post { fill.layoutParams = (fill.layoutParams as FrameLayout.LayoutParams).apply { width = (bar.width * count.fraction).toInt() }; fill.requestLayout() }
        addView(bar, LinearLayout.LayoutParams(-1, ui.dp(8)).apply { topMargin = ui.dp(6) })
    }

    private fun card(fill: (LinearLayout) -> Unit) = ui.column().apply {
        background = ui.rounded(colors.card, 18f)
        setPadding(ui.dp(14), ui.dp(14), ui.dp(14), ui.dp(16))
        fill(this)
    }

    // ---------- "מה אתה לומד?" ----------

    /**
     * Approved (2026-10-14): like the iPhone's settings — a big title, a white rounded group, a switch for each
     * topic, "select all" at the top and "save" at the bottom. Remembered from booklet to booklet; a topic the
     * reader has not met before starts off.
     */
    private fun chooseTopics(first: Boolean) {
        val topics = Progress(map, emptySet()) { false }.topics
        val selected = chosen.intersect(topics.map { it.id }.toSet()).toMutableSet()
        val d = Dialog(activity, if (ui.night) android.R.style.Theme_Material_NoActionBar else android.R.style.Theme_Material_Light_NoActionBar)
        val root = ui.column().apply { setBackgroundColor(colors.canvas); setPadding(ui.dp(18), ui.dp(26), ui.dp(18), ui.dp(18)); tag = "study_topics" }
        root.addView(ui.label(activity.getString(R.string.pr_study_title), 30f, colors.ink, true).apply { typeface = ui.heading })
        root.addView(ui.label(activity.getString(R.string.pr_study_note), 14f, colors.muted).apply { setPadding(0, ui.dp(4), 0, ui.dp(12)) })
        val group = ui.column().apply { background = ui.rounded(colors.card, 16f) }
        val switches = mutableListOf<Toggle>()
        val all = textButton(activity.getString(R.string.pr_select_all), colors.accent, "study_all") {
            switches.forEach { it.isChecked = true }
        }
        root.addView(ui.row().apply { gravity = Gravity.END; addView(all) })
        topics.forEachIndexed { i, t ->
            if (i > 0) group.addView(View(activity).apply { setBackgroundColor(colors.border) }, LinearLayout.LayoutParams(-1, 1).apply { marginStart = ui.dp(16) })
            val row = ui.row().apply { setPadding(ui.dp(16), ui.dp(4), ui.dp(12), ui.dp(4)); minimumHeight = ui.dp(54) }
            row.addView(ui.label(t.label, 16.5f, colors.ink), LinearLayout.LayoutParams(0, -2, 1f))
            val sw = Toggle(activity, ui, t.id in selected) { on -> if (on) selected += t.id else selected -= t.id }.apply {
                tag = "study_${t.id}"; contentDescription = t.label
            }
            switches += sw
            row.addView(sw, LinearLayout.LayoutParams(ui.dp(51), ui.dp(31)))
            row.setOnClickListener { sw.toggle() }
            group.addView(row)
        }
        val list = ScrollView(activity).apply { addView(group) }
        root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(ui.label(activity.getString(R.string.pr_save), 17f, colors.buttonInk, true).apply {
            gravity = Gravity.CENTER; minimumHeight = ui.dp(54); tag = "study_save"
            background = ui.rounded(colors.button, 16f)
            setOnClickListener {
                // topics of other booklets keep what the reader chose for them
                val others = settings.studyTopics.orEmpty() - topics.map { it.id }.toSet()
                settings.studyTopics = others + selected
                d.dismiss()
                render()
            }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(14) })
        d.setContentView(root)
        d.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        if (first) d.setCancelable(false)
        lastStudy = d
        d.show()
    }

    /** The "what do you learn?" window (for tests). */
    var lastStudy: Dialog? = null
        private set

    // ---------- small parts ----------

    private fun textButton(text: String, color: Int, tagName: String, action: () -> Unit) = ui.label(text, 15f, color, true).apply {
        tag = tagName
        gravity = Gravity.CENTER
        setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(8))
        background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x22000000), null, ui.rounded(Color.WHITE, 12f))
        setOnClickListener { action() }
    }

    /**
     * A switch like the iPhone's (the approved sketch), drawn by hand: the system's switch is built from dozens
     * of pictures and opening a list of them is slow on weak phones.
     */
    class Toggle(context: Context, private val ui: Ui, on: Boolean, private val changed: (Boolean) -> Unit) : View(context) {
        var isChecked = on
            set(v) { if (field != v) { field = v; invalidate(); changed(v); sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_VIEW_CLICKED) } }
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        init {
            isClickable = true; isFocusable = true
            setOnClickListener { toggle() }
        }
        fun toggle() { isChecked = !isChecked }
        override fun onInitializeAccessibilityNodeInfo(info: android.view.accessibility.AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(info)
            info.className = android.widget.Switch::class.java.name
            info.isCheckable = true; info.isChecked = isChecked
        }
        override fun onDraw(canvas: Canvas) {
            val h = height.toFloat(); val w = width.toFloat(); val r = h / 2
            paint.color = if (isChecked) Color.parseColor("#34C759") else ui.colors.border
            canvas.drawRoundRect(0f, 0f, w, h, r, r, paint)
            val pad = ui.dp(2).toFloat()
            val cx = if (isChecked) w - r else r
            paint.color = Color.WHITE
            paint.setShadowLayer(pad, 0f, pad / 2, 0x33000000)
            canvas.drawCircle(cx, r, r - pad, paint)
            paint.clearShadowLayer()
        }
    }

    /** A place a column does not have: greyed out, not tappable, not counted. */
    private class Ghost(context: Context, private val ui: Ui) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = ui.dp(2).toFloat(); color = ui.colors.border
            pathEffect = DashPathEffect(floatArrayOf(ui.dp(4).toFloat(), ui.dp(3).toFloat()), 0f)
        }
        private val fill = Paint().apply { color = ui.colors.canvas }
        init { tag = "progress_ghost"; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
        override fun onDraw(canvas: Canvas) {
            val r = ui.dp(9).toFloat(); val i = paint.strokeWidth / 2
            canvas.drawRoundRect(i, i, width - i, height - i, r, r, fill)
            canvas.drawRoundRect(i, i, width - i, height - i, r, r, paint)
        }
    }

    private fun confetti() {
        val view = Confetti(activity, ui)
        overlay.addView(view, FrameLayout.LayoutParams(-1, -1))
        view.play { overlay.removeView(view) }
    }

    /** Confetti once at 100% — the only animation on this screen. */
    private class Confetti(context: Context, private val ui: Ui) : View(context) {
        private val pieces = List(70) { floatArrayOf(Random.nextFloat(), -Random.nextFloat() * 0.3f, 0.6f + Random.nextFloat(), Random.nextFloat() * 360f) }
        private val palette = intArrayOf(Gauge.PINK, Gauge.ORANGE, Gauge.PURPLE, Gauge.CALM, Color.parseColor("#3CC8A0"))
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var t = 0f
        init { isClickable = false; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
        fun play(done: () -> Unit) {
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 1700
                addUpdateListener { t = it.animatedValue as Float; invalidate() }
                addListener(object : android.animation.AnimatorListenerAdapter() { override fun onAnimationEnd(animation: android.animation.Animator) = done() })
                start()
            }
        }
        override fun onDraw(canvas: Canvas) {
            pieces.forEachIndexed { i, p ->
                paint.color = palette[i % palette.size]
                paint.alpha = (255 * (1f - t)).toInt().coerceIn(0, 255)
                val x = p[0] * width
                val y = (p[1] + t * p[2]) * height
                canvas.save(); canvas.rotate(p[3] + t * 540f, x, y)
                canvas.drawRect(x - ui.dp(4), y - ui.dp(2), x + ui.dp(4), y + ui.dp(2), paint)
                canvas.restore()
            }
        }
    }

    companion object {
        const val UNDO_MS = 6000L
    }
}
