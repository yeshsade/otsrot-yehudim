// Lean Otzarot reader. License: GPLv3.
package com.yeshsade.otzarot.reader.data

import java.util.Locale
import java.util.UUID

/**
 * A draggable ribbon bookmark (owner's decisions: docs/BOOKMARK-decisions.md). It lives inside the PDF as
 * a filled STAMP annotation whose name carries everything needed to rebuild it:
 * `otzr-bm:v1;o=v;s=m;c=CC57479F;id=…`. Positions are "frame" points: the page as it is seen, origin top-left.
 * Pure logic; unit-tested.
 */
data class Bookmark(
    val id: String,
    val page: Int,
    /** Centre of the ribbon, in frame points. */
    val x: Float,
    val y: Float,
    val horizontal: Boolean,
    /** Width of the ribbon across, in points; its length is [LENGTH_RATIO] times that. */
    val thickness: Float,
    /** ARGB; the alpha is the chosen opacity. */
    val color: Int,
) {
    val width get() = if (horizontal) thickness * LENGTH_RATIO else thickness
    val height get() = if (horizontal) thickness else thickness * LENGTH_RATIO

    /** {left, top, right, bottom} in frame points. */
    fun frameRect() = floatArrayOf(x - width / 2, y - height / 2, x + width / 2, y + height / 2)

    /** The ribbon outline as x,y pairs in frame points. */
    fun polygon(): FloatArray = frameRect().let { shape(horizontal, it[0], it[1], it[2], it[3]) }

    val name: String
        get() = String.format(Locale.US, "%sv1;o=%s;s=%.1f;c=%08X;id=%s",
            PREFIX, if (horizontal) "h" else "v", thickness, color, id)

    /** Same ribbon, moved so it stays fully on the page. */
    fun keptOn(frameWidth: Float, frameHeight: Float): Bookmark {
        val nx = x.coerceIn(width / 2, (frameWidth - width / 2).coerceAtLeast(width / 2))
        val ny = y.coerceIn(height / 2, (frameHeight - height / 2).coerceAtLeast(height / 2))
        return copy(x = nx, y = ny)
    }

    companion object {
        const val PREFIX = "otzr-bm:"
        const val LENGTH_RATIO = 3f
        /** Owner's decision: purple (the app colour) at about 80% opacity. */
        const val DEFAULT_COLOR = 0xCC57479F.toInt()

        /** Owner's decisions: the bar goes from 6.5 up to a ribbon as long as half the page's height; a new bookmark
         *  starts at twice the first size, 13 (2026-10-26: "הסימנייה הראשונית היא פיצית"). */
        const val DEFAULT_THICKNESS = 26f
        /** The first size (2026-10-14), still the saved one for whoever never moved the bar. */
        const val OLD_DEFAULT_THICKNESS = 13f
        const val MIN_THICKNESS = 6.5f
        /** Sizes written by 0.2.2 (s/m/l). */
        private val OLD_SIZES = mapOf("s" to 13f, "m" to 19f, "l" to 27f)

        fun maxThickness(pageHeight: Float): Float = (pageHeight / 2f / LENGTH_RATIO).coerceAtLeast(MIN_THICKNESS)

        /**
         * The size bar (0..100). The scale grows by the same factor at every step, so the small sizes that
         * are used most get as much of the bar as the big ones.
         */
        fun thicknessAt(progress: Int, max: Float): Float {
            val p = progress.coerceIn(0, 100) / 100.0
            return (MIN_THICKNESS * Math.pow((max / MIN_THICKNESS).toDouble(), p)).toFloat()
        }

        fun progressOf(thickness: Float, max: Float): Int {
            if (max <= MIN_THICKNESS) return 0
            val t = thickness.coerceIn(MIN_THICKNESS, max)
            return (Math.log((t / MIN_THICKNESS).toDouble()) / Math.log((max / MIN_THICKNESS).toDouble()) * 100 + .5).toInt()
        }

        /** Slant of the pointed end and depth of the V notch, as parts of the ribbon's length. */
        private const val SLANT = .12f
        private const val NOTCH = .15f

        fun newId(): String = UUID.randomUUID().toString().replace("-", "").take(12)

        /**
         * Outline inside a box (any coordinates with y pointing down). Vertical: the pointed slanted end at
         * the top, the V notch at the bottom. Horizontal: the pointed end on the right, the notch on the left.
         */
        fun shape(horizontal: Boolean, left: Float, top: Float, right: Float, bottom: Float): FloatArray {
            // u: across the ribbon (0..1), v: along it from the pointed end (0..1)
            val unit = floatArrayOf(0f, SLANT, 1f, 0f, 1f, 1f, .5f, 1f - NOTCH, 0f, 1f)
            val out = FloatArray(unit.size)
            for (i in unit.indices step 2) {
                val u = unit[i]
                val v = unit[i + 1]
                if (horizontal) {
                    out[i] = right - v * (right - left)
                    out[i + 1] = top + u * (bottom - top)
                } else {
                    out[i] = left + u * (right - left)
                    out[i + 1] = top + v * (bottom - top)
                }
            }
            return out
        }

        /** Rebuilds a bookmark from its name and its frame rect {left, top, right, bottom}; null if not ours. */
        fun parse(name: String, page: Int, rect: FloatArray): Bookmark? {
            if (!name.startsWith(PREFIX)) return null
            val fields = name.removePrefix(PREFIX).split(';').mapNotNull {
                val eq = it.indexOf('=')
                if (eq > 0) it.substring(0, eq) to it.substring(eq + 1) else null
            }.toMap()
            val id = fields["id"]?.takeIf { it.isNotEmpty() } ?: return null
            val color = fields["c"]?.toLongOrNull(16)?.toInt() ?: DEFAULT_COLOR
            return Bookmark(
                id = id, page = page,
                x = (rect[0] + rect[2]) / 2, y = (rect[1] + rect[3]) / 2,
                horizontal = fields["o"] == "h", color = color,
                thickness = fields["s"]?.let { OLD_SIZES[it] ?: it.toFloatOrNull() }?.takeIf { it > 0 } ?: DEFAULT_THICKNESS,
            )
        }

        /** The colours offered in the menu (opacity is chosen separately). */
        val PALETTE = intArrayOf(
            0xFF57479F.toInt(), // purple — the app colour
            0xFFD93A3A.toInt(), // red
            0xFFF08A24.toInt(), // orange
            0xFFF2C230.toInt(), // yellow
            0xFF2E9E5B.toInt(), // green
            0xFF2F7FD8.toInt(), // blue
        )

        fun withOpacity(rgb: Int, opacity: Float): Int =
            ((opacity.coerceIn(.2f, 1f) * 255 + .5f).toInt() shl 24) or (rgb and 0xFFFFFF)

        fun opacityOf(color: Int): Float = (color ushr 24) / 255f
    }
}

/**
 * How a new ribbon arrives (owner's choice 2026-10-16: "falls and bounces"): it drops from above into its
 * place, speeding up, then makes one small hop. Half a second. Pure logic; unit-tested.
 */
object DropIn {
    const val MILLIS = 500L
    /** Part of the time spent falling; the rest is the hop. */
    private const val FALL = .7f
    /** Height of the hop, as a part of the fall. */
    const val HOP = .12f

    /** How far above its place the ribbon is at time [t] (0..1), as a part of the fall height: 1 = top, 0 = in place. */
    fun lift(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return if (x < FALL) {
            val p = x / FALL
            1f - p * p
        } else {
            val p = (x - FALL) / (1f - FALL)
            HOP * 4f * p * (1f - p)
        }
    }

    /** Fades in during the first quarter of the fall. */
    fun opacity(t: Float): Float = (t / .25f).coerceIn(0f, 1f)
}

/**
 * Label for a bookmark row in the list: the booklet's own "topic, day" when the booklet carries that map
 * (later), else the nearest chapter of the PDF's built-in table of contents at or before the page, else
 * "page N". [chapters] is page → title, any order.
 */
object BookmarkLabel {
    fun of(page: Int, chapters: List<Pair<Int, String>>, pageWord: (Int) -> String): String =
        chapters.filter { it.first <= page && it.second.isNotBlank() }.maxByOrNull { it.first }?.second?.trim()
            ?: pageWord(page + 1)
}
