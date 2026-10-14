// Lean Otzarot reader. License: GPLv3.
package com.yeshsade.otzarot.reader.ui

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.Window
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import com.github.barteksc.pdfviewer.PDFView
import com.shockwave.pdfium.PdfDocument
import com.yeshsade.otzarot.reader.R
import com.yeshsade.otzarot.reader.data.Bookmark
import com.yeshsade.otzarot.reader.data.BookmarkLabel
import com.yeshsade.otzarot.reader.data.Settings
import com.yeshsade.otzarot.reader.data.UndoStack
import java.util.concurrent.Executor
import kotlin.math.hypot

/**
 * Ribbon bookmarks on the reading screen. They are stored in the PDF (see [Bookmark]); the engine hides
 * them while it renders and this class draws them on top, so they can follow the finger while dragged.
 * Main thread only, except the initial scan which runs on [io].
 */
class Bookmarks(
    private val context: Context,
    private val pdfView: PDFView,
    private val ui: Ui,
    private val settings: Settings,
    private val io: Executor,
    /** Called after every change the user made, for undo and auto-save. */
    private val changed: (UndoStack.BookmarkChange) -> Unit,
) {
    private companion object {
        const val TAG = "Bookmarks"
        /** Owner's decision: adding a third ribbon to one page asks first. */
        const val MAX_WITHOUT_ASKING = 2
    }
    private val main = Handler(Looper.getMainLooper())
    private val byPage = HashMap<Int, MutableList<Bookmark>>()
    private var loadGeneration = 0
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = 0x33000000 }
    private val path = Path()

    /** While dragging: the ribbon, its on-screen box at the start, and how far the finger moved. */
    private var dragging: Bookmark? = null
    private var dragBox: RectF? = null
    private var dragDx = 0f
    private var dragDy = 0f

    fun all(): List<Bookmark> = byPage.values.flatten().sortedWith(compareBy({ it.page }, { it.y }, { it.x }))

    // ---------- loading ----------
    // Ribbons are read page by page, only for pages on screen: those pages are open in the engine anyway,
    // so reading them is cheap. Reading every page of a big booklet up front would hold the engine for a long
    // time and leave the screen blank.

    private val scanned = HashSet<Int>()
    private val scanning = HashSet<Int>()
    /** Ribbons removed on screen; a read that started before the removal must not bring them back. */
    private val removedIds = HashSet<String>()

    /** True once the ribbons on the first pages shown have been read. */
    var loaded = false
        private set

    fun clear() {
        loaded = false
        loadGeneration++
        byPage.clear(); scanned.clear(); scanning.clear(); removedIds.clear(); listFound.clear()
        pdfView.invalidate()
    }

    /** The document is open: read the ribbons on the pages now on screen. */
    fun load(pageCount: Int) {
        loaded = false
        loadGeneration++
        scanned.clear(); scanning.clear()
        readVisible()
    }

    private fun readVisible() {
        val range = pdfView.visiblePageRange ?: return
        val pages = (range[0]..range[1]).filter { it !in scanned && it !in scanning }
        if (pages.isEmpty()) return
        scanning += pages
        val generation = loadGeneration
        io.execute {
            val found = pages.associateWith { read(it) }
            main.post {
                if (generation != loadGeneration) return@post
                found.forEach { (page, list) -> merge(page, list) }
                loaded = true
                pdfView.invalidate()
            }
        }
    }

    private fun read(page: Int): List<Bookmark> = pdfView.getAppBookmarks(page).mapNotNull { (name, rect) ->
        Bookmark.parse(name, page, floatArrayOf(rect.left, rect.top, rect.right, rect.bottom))
    }

    /** What was read wins only for ribbons not touched on screen meanwhile. */
    private fun merge(page: Int, list: List<Bookmark>) {
        scanning -= page
        scanned += page
        val known = byPage.values.flatten().map { it.id }.toSet()
        val keep = list.filter { it.id !in known && it.id !in removedIds }
        if (keep.isNotEmpty()) byPage.getOrPut(page) { mutableListOf() }.addAll(0, keep)
    }

    /** Before counting the ribbons on a page: make sure that page has been read (it is on screen, so it is cheap). */
    private fun ensureRead(page: Int) {
        if (page !in scanned) merge(page, read(page))
    }

    // ---------- drawing ----------

    fun draw(canvas: Canvas) {
        readVisible()
        val range = pdfView.visiblePageRange ?: return
        for (page in range[0]..range[1]) {
            byPage[page]?.forEach { b ->
                if (b.id == dragging?.id) return@forEach
                viewBox(b)?.let { drawRibbon(canvas, b, it, lifted = false) }
            }
        }
        val b = dragging ?: return
        val box = RectF(dragBox ?: return).apply { offset(dragDx, dragDy) }
        drawRibbon(canvas, b, box, lifted = true)
    }

    private fun viewBox(b: Bookmark): RectF? =
        b.frameRect().let { pdfView.frameRectToView(b.page, RectF(it[0], it[1], it[2], it[3])) }

    private fun drawRibbon(canvas: Canvas, b: Bookmark, box: RectF, lifted: Boolean) {
        if (lifted) {
            val lift = ui.dp(3).toFloat()
            outline(b.horizontal, RectF(box).apply { offset(lift, lift) })
            canvas.drawPath(path, shadow)
        }
        outline(b.horizontal, box)
        paint.color = b.color
        canvas.drawPath(path, paint)
    }

    private fun outline(horizontal: Boolean, box: RectF) {
        val p = Bookmark.shape(horizontal, box.left, box.top, box.right, box.bottom)
        path.reset()
        path.moveTo(p[0], p[1])
        for (i in 2 until p.size step 2) path.lineTo(p[i], p[i + 1])
        path.close()
    }

    // ---------- touching ----------

    /** The bookmark under a finger, if any (the top one), with a generous touch margin. */
    fun hit(x: Float, y: Float): Bookmark? {
        val range = pdfView.visiblePageRange ?: return null
        val margin = ui.dp(10).toFloat()
        for (page in range[1] downTo range[0]) {
            byPage[page]?.asReversed()?.forEach { b ->
                val box = viewBox(b) ?: return@forEach
                box.inset(-margin, -margin)
                if (box.contains(x, y)) return b
            }
        }
        return null
    }

    fun startDrag(b: Bookmark) {
        dragging = b
        dragBox = viewBox(b)
        dragDx = 0f; dragDy = 0f
        pdfView.invalidate()
    }

    fun dragBy(dx: Float, dy: Float) {
        dragDx = dx; dragDy = dy
        pdfView.invalidate()
    }

    /** Drops the dragged ribbon where it is now, possibly on another page. */
    fun drop() {
        val b = dragging ?: return
        val box = dragBox
        dragging = null; dragBox = null
        if (box == null) { pdfView.invalidate(); return }
        val target = pdfView.viewToFrame(box.centerX() + dragDx, box.centerY() + dragDy)
        if (target == null) { pdfView.invalidate(); return }
        val moved = keptOn(b.copy(page = target.page, x = target.x, y = target.y))
        if (moved != b) change(b, moved) else pdfView.invalidate()
    }

    fun cancelDrag() {
        dragging = null; dragBox = null
        pdfView.invalidate()
    }

    // ---------- changing ----------

    /**
     * Toolbar "bookmark": a new ribbon in the middle of what is on screen. A third ribbon on the same page
     * asks first (owner's decision). [done] gets the new ribbon, or null if none was added.
     */
    fun add(done: (Bookmark?) -> Unit = {}) {
        val at = pdfView.viewToFrame(pdfView.width / 2f, pdfView.height / 2f)
        if (at == null) {
            android.util.Log.w(TAG, "add: no page under the centre")
            done(null); return
        }
        val b = keptOn(Bookmark(Bookmark.newId(), at.page, at.x, at.y,
            settings.bookmarkHorizontal, settings.bookmarkThickness, settings.bookmarkColor))
        val write = {
            if (change(null, b)) done(b)
            else { android.util.Log.w(TAG, "add: engine refused $b"); done(null) }
        }
        ensureRead(b.page)
        if ((byPage[b.page]?.size ?: 0) < MAX_WITHOUT_ASKING) { write(); return }
        lastQuestion = AlertDialog.Builder(context)
            .setMessage(R.string.bm_third_question)
            .setPositiveButton(R.string.bm_third_yes) { _, _ -> write() }
            .setNegativeButton(R.string.bm_third_no) { _, _ -> done(null) }
            .setOnCancelListener { done(null) }
            .show()
    }

    /** The last "more than 2 on this page?" question shown (for tests and screenshots). */
    var lastQuestion: AlertDialog? = null
        private set

    /** Writes a change into the document; [before] null = add, [after] null = remove. */
    fun change(before: Bookmark?, after: Bookmark?, record: Boolean = true): Boolean {
        if (!apply(before, after)) return false
        if (record) changed(UndoStack.BookmarkChange(before, after))
        return true
    }

    /** Undo: puts back what was there before the change. */
    fun revert(step: UndoStack.BookmarkChange) {
        apply(step.after, step.before)
    }

    private fun apply(before: Bookmark?, after: Bookmark?): Boolean {
        if (before != null) {
            pdfView.removeAppBookmark(before.page, before.name)
            byPage[before.page]?.removeAll { it.id == before.id }
            if (after == null) removedIds += before.id
        }
        if (after != null) removedIds -= after.id
        // the cache may hold a live preview of this ribbon on another page
        if (after != null) byPage.values.forEach { list -> list.removeAll { it.id == after.id } }
        var ok = true
        if (after != null) {
            ok = pdfView.addAppBookmark(after.page, after.name, after.polygon(), after.color)
            if (ok) byPage.getOrPut(after.page) { mutableListOf() }.add(after)
            else if (before != null) {
                // could not write the new one: keep the old one
                pdfView.addAppBookmark(before.page, before.name, before.polygon(), before.color)
                byPage.getOrPut(before.page) { mutableListOf() }.add(before)
            }
        }
        pdfView.invalidate()
        return ok
    }

    /** Shows a change on screen only (while the opacity slider moves); written by [change] later. */
    private fun preview(b: Bookmark) {
        byPage[b.page]?.let { list ->
            val i = list.indexOfFirst { it.id == b.id }
            if (i >= 0) list[i] = b
        }
        pdfView.invalidate()
    }

    private fun keptOn(b: Bookmark): Bookmark {
        val size = pdfView.getFrameSize(b.page) ?: return b
        return b.keptOn(size[0], size[1])
    }

    private fun remember(b: Bookmark) {
        settings.bookmarkColor = b.color
        settings.bookmarkThickness = b.thickness
        settings.bookmarkHorizontal = b.horizontal
    }

    // ---------- menu (long press on a ribbon) ----------

    fun showMenu(start: Bookmark): Dialog {
        var current = start
        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val box = ui.column().apply {
            background = ui.rounded(ui.colors.card, 22f)
            setPadding(ui.dp(20), ui.dp(18), ui.dp(20), ui.dp(14))
        }

        fun update(next: Bookmark) {
            val kept = keptOn(next)
            if (kept == current) return
            if (change(current, kept)) { current = kept; remember(kept) }
        }

        // colour
        box.addView(heading(R.string.bm_color))
        val swatches = ui.row()
        val swatchViews = Bookmark.PALETTE.map { rgb ->
            View(context).apply {
                contentDescription = String.format("#%06X", rgb and 0xFFFFFF)
                setOnClickListener {
                    update(current.copy(color = Bookmark.withOpacity(rgb, Bookmark.opacityOf(current.color))))
                    refreshSwatches(swatches, current)
                }
            }.also { swatches.addView(it, LinearLayout.LayoutParams(ui.dp(40), ui.dp(40)).apply { marginEnd = ui.dp(8) }) }
        }
        refreshSwatches(swatches, current)
        box.addView(swatches)

        // opacity
        box.addView(heading(R.string.bm_opacity))
        box.addView(SeekBar(context).apply {
            // the bar shows transparency, as its label says: 0% = solid, 80% = very see-through
            max = 80
            progress = 100 - (Bookmark.opacityOf(current.color) * 100 + .5f).toInt().coerceIn(20, 100)
            var before: Bookmark? = null
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    if (before == null) before = current
                    current = current.copy(color = Bookmark.withOpacity(current.color, (100 - value) / 100f))
                    preview(current)
                }
                override fun onStartTrackingTouch(bar: SeekBar) { before = current }
                override fun onStopTrackingTouch(bar: SeekBar) {
                    val old = before ?: return
                    before = null
                    if (old != current && change(old, current)) remember(current)
                }
            })
        }, LinearLayout.LayoutParams(-1, ui.dp(48)))

        // size: one bar, from half the old "small" up to a ribbon a third of the page long
        box.addView(heading(R.string.bm_size))
        box.addView(SeekBar(context).apply {
            val maxThickness = Bookmark.maxThickness(pdfView.getFrameSize(current.page)?.get(1) ?: 842f)
            max = 100
            progress = Bookmark.progressOf(current.thickness, maxThickness)
            var before: Bookmark? = null
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    if (before == null) before = current
                    current = keptOn(current.copy(thickness = Bookmark.thicknessAt(value, maxThickness)))
                    preview(current)
                }
                override fun onStartTrackingTouch(bar: SeekBar) { before = current }
                override fun onStopTrackingTouch(bar: SeekBar) {
                    val old = before ?: return
                    before = null
                    if (old != current && change(old, current)) remember(current)
                }
            })
        }, LinearLayout.LayoutParams(-1, ui.dp(48)))

        // direction
        box.addView(heading(R.string.bm_direction))
        val directions = ui.row()
        fun refreshDirections() {
            choice(directions.getChildAt(0) as TextView, !current.horizontal)
            choice(directions.getChildAt(1) as TextView, current.horizontal)
        }
        directions.addView(chip(R.string.bm_vertical) { update(current.copy(horizontal = false)); refreshDirections() }, chipParams())
        directions.addView(chip(R.string.bm_horizontal) { update(current.copy(horizontal = true)); refreshDirections() }, chipParams())
        refreshDirections()
        box.addView(directions)

        // remove / close
        val actions = ui.row().apply { setPadding(0, ui.dp(16), 0, 0) }
        actions.addView(ui.label(context.getString(R.string.bm_remove), 16f, Color.parseColor("#C62828"), bold = true).apply {
            gravity = Gravity.CENTER
            minHeight = ui.dp(48)
            setPadding(ui.dp(12), 0, ui.dp(12), 0)
            setOnClickListener { change(current, null); dialog.dismiss() }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        actions.addView(ui.label(context.getString(R.string.bm_close), 16f, ui.colors.accent, bold = true).apply {
            gravity = Gravity.CENTER
            minHeight = ui.dp(48)
            setPadding(ui.dp(12), 0, ui.dp(12), 0)
            setOnClickListener { dialog.dismiss() }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        box.addView(actions)

        dialog.setContentView(box)
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout((context.resources.displayMetrics.widthPixels * .9f).toInt().coerceAtMost(ui.dp(440)), ViewGroup.LayoutParams.WRAP_CONTENT)
            // keep the ribbon in sight so every change is seen at once: the menu goes to the other half
            val ribbon = viewBox(start)
            val ribbonLow = ribbon != null && ribbon.centerY() > pdfView.height / 2f
            setGravity(if (ribbonLow) Gravity.TOP else Gravity.BOTTOM)
            attributes = attributes.apply { y = ui.dp(24) }
            setDimAmount(.15f)
        }
        dialog.show()
        return dialog
    }

    private fun heading(text: Int) = ui.label(context.getString(text), 14f, ui.colors.muted).apply {
        setPadding(0, ui.dp(10), 0, ui.dp(6))
    }

    private fun refreshSwatches(row: LinearLayout, current: Bookmark) {
        for (i in 0 until row.childCount) {
            val rgb = Bookmark.PALETTE[i]
            val selected = rgb and 0xFFFFFF == current.color and 0xFFFFFF
            row.getChildAt(i).background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(rgb)
                if (selected) setStroke(ui.dp(4), ui.colors.ink)
            }
        }
    }

    private fun chip(label: Int, action: () -> Unit) = ui.label(context.getString(label), 15f, ui.colors.ink).apply {
        gravity = Gravity.CENTER
        minHeight = ui.dp(44)
        setPadding(ui.dp(10), 0, ui.dp(10), 0)
        setOnClickListener { action() }
    }

    private fun chipParams() = LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = ui.dp(8) }

    private fun choice(view: TextView, selected: Boolean) {
        view.background = ui.rounded(if (selected) ui.colors.selection else ui.colors.card, 14f,
            border = if (selected) ui.colors.accent else ui.colors.border)
        view.setTextColor(if (selected) ui.colors.accent else ui.colors.ink)
    }

    // ---------- list (long press on the toolbar button) ----------

    /** Ribbons on pages never shown yet, found by the list's search (page → ribbons). Kept for this document. */
    private val listFound = HashMap<Int, List<Bookmark>>()

    /** Every ribbon in the document: those read on screen, plus those the list's search found elsewhere. */
    private fun everything(): List<Bookmark> {
        val known = all()
        val knownIds = known.map { it.id }.toSet()
        val elsewhere = listFound.filterKeys { it !in scanned }.values.flatten()
            .filter { it.id !in knownIds && it.id !in removedIds }
        return (known + elsewhere).sortedWith(compareBy({ it.page }, { it.y }, { it.x }))
    }

    fun showList(): Dialog {
        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val box = ui.column().apply {
            background = ui.rounded(ui.colors.card, 22f)
            setPadding(ui.dp(8), ui.dp(16), ui.dp(8), ui.dp(8))
        }
        box.addView(ui.label(context.getString(R.string.bm_list_title), 18f, ui.colors.ink, bold = true).apply {
            setPadding(ui.dp(12), 0, ui.dp(12), ui.dp(8))
        })
        val status = ui.label("", 13f, ui.colors.muted).apply { setPadding(ui.dp(12), 0, ui.dp(12), ui.dp(8)) }
        box.addView(status)
        val rows = ui.column()
        box.addView(ScrollView(context).apply { addView(rows) }, LinearLayout.LayoutParams(-1, -2))
        val chapters = chapters()

        fun fill(searching: Boolean) {
            rows.removeAllViews()
            val items = everything()
            if (items.isEmpty() && !searching) {
                rows.addView(ui.label(context.getString(R.string.bm_list_empty), 15f, ui.colors.muted).apply {
                    setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(16))
                })
            }
            items.forEach { b ->
                val pageText = context.getString(R.string.bm_page, b.page + 1)
                val label = BookmarkLabel.of(b.page, chapters) { pageText }
                val row = ui.row().apply {
                    minimumHeight = ui.dp(56)
                    setPadding(ui.dp(12), ui.dp(6), ui.dp(12), ui.dp(6))
                    background = ui.rounded(ui.colors.card, 14f)
                    setOnClickListener { dialog.dismiss(); pdfView.jumpTo(b.page) }
                }
                row.addView(RibbonIcon(context, b), LinearLayout.LayoutParams(ui.dp(18), ui.dp(36)).apply { marginEnd = ui.dp(14) })
                val texts = ui.column()
                texts.addView(ui.label(label, 16f, ui.colors.ink).apply { maxLines = 1 })
                if (label != pageText) texts.addView(ui.label(pageText, 13f, ui.colors.muted))
                row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
                rows.addView(row, LinearLayout.LayoutParams(-1, -2))
            }
        }

        // Look through the pages not seen yet, one page at a time so the pages behind can still draw.
        val pages = pdfView.pageCount
        val toSearch = (0 until pages).filter { it !in scanned && it !in listFound }
        val generation = loadGeneration
        fill(searching = toSearch.isNotEmpty())
        fun searchFrom(i: Int) {
            if (!dialog.isShowing || generation != loadGeneration) return
            if (i >= toSearch.size) { status.visibility = View.GONE; fill(searching = false); return }
            status.text = context.getString(R.string.bm_list_searching, i + 1, toSearch.size)
            val page = toSearch[i]
            io.execute {
                val names = pdfView.getAppBookmarkNames(page)
                main.post {
                    if (generation != loadGeneration) return@post
                    listFound[page] = names.mapNotNull { Bookmark.parse(it, page, floatArrayOf(0f, 0f, 0f, 0f)) }
                    if (listFound[page]!!.isNotEmpty()) fill(searching = true)
                    searchFrom(i + 1)
                }
            }
        }
        if (toSearch.isEmpty()) status.visibility = View.GONE
        dialog.setOnShowListener { searchFrom(0) }

        dialog.setContentView(box)
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout((context.resources.displayMetrics.widthPixels * .9f).toInt().coerceAtMost(ui.dp(480)),
                ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        dialog.show()
        return dialog
    }

    /** The PDF's own table of contents, flattened: page → title. */
    private fun chapters(): List<Pair<Int, String>> {
        val out = ArrayList<Pair<Int, String>>()
        walkChildren(runCatching { pdfView.tableOfContents }.getOrNull() ?: emptyList(), out)
        return out
    }

    private fun walkChildren(list: List<PdfDocument.Bookmark>, out: ArrayList<Pair<Int, String>>) {
        list.forEach { item ->
            out.add(item.pageIdx.toInt() to (item.title ?: ""))
            if (item.hasChildren()) walkChildren(item.children, out)
        }
    }

    /** Small ribbon used in the list. */
    private class RibbonIcon(context: Context, private val b: Bookmark) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = b.color or (0xFF shl 24) }
        private val path = Path()
        override fun onDraw(canvas: Canvas) {
            val p = Bookmark.shape(false, 0f, 0f, width.toFloat(), height.toFloat())
            path.reset(); path.moveTo(p[0], p[1])
            for (i in 2 until p.size step 2) path.lineTo(p[i], p[i + 1])
            path.close()
            canvas.drawPath(path, paint)
        }
    }

    /**
     * Sits over the pages and takes a touch only when it starts on a ribbon: drag to move,
     * long press for the menu. Any other touch goes through to the pages.
     */
    @SuppressLint("ViewConstructor")
    class TouchLayer(context: Context, private val bookmarks: Bookmarks) : View(context) {
        private val slop = ViewConfiguration.get(context).scaledTouchSlop
        private val longPress = Runnable { pressed?.let { longPressed = true; bookmarks.showMenu(it) }; Unit }
        private var pressed: Bookmark? = null
        private var downX = 0f
        private var downY = 0f
        private var moving = false
        private var longPressed = false

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    pressed = bookmarks.hit(event.x, event.y)
                    if (pressed == null) return false
                    downX = event.x; downY = event.y
                    moving = false; longPressed = false
                    postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val b = pressed ?: return false
                    val dx = event.x - downX
                    val dy = event.y - downY
                    if (!moving && !longPressed && hypot(dx, dy) > slop) {
                        moving = true
                        removeCallbacks(longPress)
                        bookmarks.startDrag(b)
                    }
                    if (moving) bookmarks.dragBy(dx, dy)
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    removeCallbacks(longPress)
                    if (moving) bookmarks.drop()
                    pressed = null; moving = false
                    return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    removeCallbacks(longPress)
                    if (moving) bookmarks.cancelDrag()
                    pressed = null; moving = false
                    return true
                }
            }
            return pressed != null
        }
    }
}
