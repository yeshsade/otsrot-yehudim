// Lean Otzarot reader. License: GPLv3.
package com.yeshsade.otzarot.reader.ui

import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.animation.LinearInterpolator
import com.github.barteksc.pdfviewer.PDFView
import com.yeshsade.otzarot.reader.data.ProgressMap

/**
 * The day's name, faint, in the middle of the page (owner, 2026-10-17): "במרכז, בשקיפות כמעט מרבית, שם היום".
 * It shows for a few seconds after each touch of the screen, then fades. A page split between two days (Tehillim)
 * shows each day's name on its own part ("חצי חצי": the owner approved the sketch); that is how the booklet is,
 * not a setting. Drawn with the page, in the same frame — nothing extra is laid over the reader.
 */
class DayName(private val pdfView: PDFView, typeface: Typeface, private val night: Boolean) {
    var map: ProgressMap? = null
        set(value) { field = value; lastDay = null; lastCenter = null; pdfView.invalidate() }

    /** The day last seen in the middle of the screen: the name shows again only when this changes. */
    private var lastDay: String? = null

    /**
     * The pages moved. When the middle of the screen reaches another day the name shows for a few seconds
     * (owner, 2026-10-25: "כדי שלא יעצבן" — not at every touch). Returns the day now in the middle, if any.
     */
    fun moved(): String? {
        val day = currentDay(pdfView.currentPage) ?: return null
        if (day != lastDay) { lastDay = day; touched() }
        return day
    }

    /** Whether it may show now (the setting, and whether the frame is shown). */
    var allowed: () -> Boolean = { true }

    /**
     * Where on the screen (owner, 2026-10-27, all of them, to try): center · corner · top (a faint name, the same
     * size at any zoom) · bubble_top · bubble_bottom (a dark bubble, like the phone's own messages). Always fixed
     * to the screen, naming the day of what is in the middle of the screen.
     */
    var place: () -> String = { "center" }

    /** Room the phone's bar and the thin line take at the top, and the reading bar at the bottom (pixels). */
    var topInset: () -> Int = { 0 }
    var bottomInset: () -> Int = { 0 }

    private val density = pdfView.resources.displayMetrics.density
    private val bubble = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2E2A3D.toInt() }
    private val bubbleText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.typeface = typeface; textAlign = Paint.Align.CENTER; color = 0xFFFFFFFF.toInt()
    }
    /** The day chosen for the middle of the screen last time: kept while the middle is between two days' parts. */
    private var lastCenter: String? = null

    private var strength = 0f
    private var animator: ValueAnimator? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.typeface = typeface
        textAlign = Paint.Align.CENTER
        color = if (night) 0xFFFFFFFF.toInt() else 0xFF2A2340.toInt()
    }

    /** A touch: show the names, then fade them out. */
    fun touched() {
        if (map == null || !allowed()) return
        animator?.cancel()
        strength = 1f
        pdfView.invalidate()
        animator = ValueAnimator.ofFloat(1f, 0f).apply {
            duration = SHOW_MS + FADE_MS
            interpolator = LinearInterpolator()
            addUpdateListener {
                val elapsed = it.currentPlayTime
                strength = if (elapsed < SHOW_MS) 1f else (1f - (elapsed - SHOW_MS).toFloat() / FADE_MS).coerceIn(0f, 1f)
                pdfView.invalidate()
            }
            start()
        }
    }

    fun hide() { animator?.cancel(); strength = 0f; pdfView.invalidate() }

    fun draw(canvas: Canvas) {
        val map = map ?: return
        if (strength <= 0f) return
        val range = pdfView.visiblePageRange ?: return
        drawCentered(canvas, map, range)
    }

    /** Each day on this page and the part of the page (in points of the page as seen) that is that day's. */
    private fun parts(info: ProgressMap.PageInfo, page: Int): List<Pair<String, RectF>> {
        val byDay = info.regions.filter { it.dayId != null }.groupBy { it.dayId!! }
        if (byDay.size > 1) {
            return byDay.map { (day, regions) ->
                day to RectF(regions.minOf { it.left }, regions.minOf { it.top }, regions.maxOf { it.right }, regions.maxOf { it.bottom })
            }
        }
        val day = info.dayId ?: byDay.keys.firstOrNull() ?: return emptyList()
        // the whole page; its size is read only when the engine already has the page (never waits on the engine here)
        if (!pdfView.isPageOpen(page)) return emptyList()
        val size = pdfView.getFrameSize(page) ?: return emptyList()
        return listOf(day to RectF(0f, 0f, size[0], size[1]))
    }

    /** The day of what is in the middle of the screen, written where [place] says. */
    private fun drawCentered(canvas: Canvas, map: ProgressMap, range: IntArray) {
        val label = dayAtCenter(map, range)?.let { map.day(it)?.label } ?: return
        val w = pdfView.width.toFloat(); val h = pdfView.height.toFloat()
        val margin = 14 * density
        when (val where = place()) {
            "bubble_top", "bubble_bottom" -> {
                bubbleText.textSize = 15 * density
                bubbleText.alpha = (255 * strength).toInt()
                val tw = bubbleText.measureText(label)
                val bw = tw + 36 * density; val bh = 40 * density
                val cy = if (where == "bubble_top") topInset() + margin + bh / 2 else h - bottomInset() - margin - bh / 2
                bubble.alpha = (235 * strength).toInt()
                canvas.drawRoundRect(w / 2 - bw / 2, cy - bh / 2, w / 2 + bw / 2, cy + bh / 2, bh / 2, bh / 2, bubble)
                canvas.drawText(label, w / 2, cy - (bubbleText.descent() + bubbleText.ascent()) / 2, bubbleText)
            }
            else -> {
                paint.textSize = 1f
                val one = paint.measureText(label).coerceAtLeast(1f)
                paint.alpha = (255 * OPACITY * strength).toInt()
                when (where) {
                    "corner" -> {
                        paint.textSize = minOf(w * 0.34f / one, h * 0.05f, MAX_TEXT_PX)
                        val tw = paint.measureText(label)
                        // the corner where a line starts: the right one in Hebrew
                        val rtl = pdfView.layoutDirection == android.view.View.LAYOUT_DIRECTION_RTL ||
                            android.text.TextUtils.getLayoutDirectionFromLocale(java.util.Locale.getDefault()) == android.view.View.LAYOUT_DIRECTION_RTL
                        val x = if (rtl) w - margin - tw / 2 else margin + tw / 2
                        canvas.drawText(label, x, topInset() + margin - paint.ascent(), paint)
                    }
                    "top" -> {
                        paint.textSize = minOf(w * 0.5f / one, h * 0.07f, MAX_TEXT_PX)
                        canvas.drawText(label, w / 2, topInset() + margin - paint.ascent(), paint)
                    }
                    else -> {
                        paint.textSize = minOf(w * 0.62f / one, h * 0.12f, MAX_TEXT_PX)
                        canvas.drawText(label, w / 2, h / 2 - (paint.descent() + paint.ascent()) / 2, paint)
                    }
                }
            }
        }
    }

    /**
     * The day of what is in the middle of the screen; while the engine has not opened the page yet, the day the
     * map gives the page (a split page: its first day).
     */
    fun currentDay(page: Int): String? {
        dayAtCenter()?.let { return it }
        val info = map?.pageInfo(page)?.takeIf { it.weekOffset == 0 } ?: return null
        return info.dayId ?: info.regions.firstNotNullOfOrNull { it.dayId }
    }

    /** The day of what is in the middle of the screen (a split page: the half there), or null. */
    fun dayAtCenter(): String? {
        val map = map ?: return null
        val range = pdfView.visiblePageRange ?: return null
        return dayAtCenter(map, range)
    }

    private fun dayAtCenter(map: ProgressMap, range: IntArray): String? {
        val cx = pdfView.width / 2f
        val cy = pdfView.height / 2f
        // each part of each page in view: a split Tehillim page gives each of its pieces, not a box around all
        // of one day's pieces (those boxes overlap, and the middle of the screen sat in both — the name went
        // "שניה ככה שניה ככה", owner 2026-10-27)
        val inside = mutableSetOf<String>()
        var best: String? = null
        var bestDistance = Float.MAX_VALUE
        for (page in range[0]..range[1]) {
            val info = map.pageInfo(page) ?: continue
            if (info.weekOffset != 0) continue
            for ((dayId, frame) in pieces(info, page)) {
                val box = pdfView.frameRectToView(page, frame) ?: continue
                val dx = maxOf(box.left - cx, 0f, cx - box.right)
                val dy = maxOf(box.top - cy, 0f, cy - box.bottom)
                val distance = dx * dx + dy * dy
                if (distance == 0f) inside += dayId
                if (distance < bestDistance) { bestDistance = distance; best = dayId }
            }
        }
        // the middle is in one day's piece: that day. Between pieces (or in two): stay with the day before, if it
        // is still in view, so the name does not flicker at the border
        val chosen = when {
            inside.size == 1 -> inside.first()
            inside.size > 1 && lastCenter in inside -> lastCenter
            inside.isEmpty() && lastCenter != null && bestDistance < Float.MAX_VALUE && lastCenter in visibleDays(map, range) -> lastCenter
            else -> best
        }
        lastCenter = chosen
        return chosen
    }

    private fun visibleDays(map: ProgressMap, range: IntArray): Set<String> {
        val days = mutableSetOf<String>()
        for (page in range[0]..range[1]) {
            val info = map.pageInfo(page)?.takeIf { it.weekOffset == 0 } ?: continue
            info.dayId?.let { days += it }
            info.regions.mapNotNullTo(days) { it.dayId }
        }
        return days
    }

    /** Each piece of a split page with its day; a page of one day is one piece, the whole page. */
    private fun pieces(info: ProgressMap.PageInfo, page: Int): List<Pair<String, RectF>> {
        val withDay = info.regions.filter { it.dayId != null }
        if (withDay.map { it.dayId }.distinct().size > 1) {
            return withDay.map { it.dayId!! to RectF(it.left, it.top, it.right, it.bottom) }
        }
        return parts(info, page)
    }

    private fun drawName(canvas: Canvas, label: String, box: RectF) {
        if (box.width() < 40f || box.height() < 40f) return
        // as big as fits the part, but not huge
        paint.textSize = 1f
        val w = paint.measureText(label).coerceAtLeast(1f)
        paint.textSize = minOf(box.width() * 0.62f / w, box.height() * 0.22f, MAX_TEXT_PX)
        paint.alpha = (255 * OPACITY * strength).toInt()
        val y = box.centerY() - (paint.descent() + paint.ascent()) / 2
        canvas.drawText(label, box.centerX(), y, paint)
    }

    companion object {
        /** "בשקיפות כמעט מרבית": barely there. */
        const val OPACITY = 0.13f
        const val SHOW_MS = 2500L
        const val FADE_MS = 700L
        const val MAX_TEXT_PX = 260f
    }
}
