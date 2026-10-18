// Lean Otzarot reader. License: GPLv3.
package com.yeshsade.otzarot.reader.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PorterDuff
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import com.github.barteksc.pdfviewer.PDFView

/**
 * The page-turn animation in one page mode (owner, 2026-10-18: "להוסיף הכל חוץ מסלסול פינה").
 *
 * It costs nothing while reading: only during a turn (a third of a second) does it hold a picture of the page
 * that is leaving (one small picture, reused, let go when the screen is left). The page that arrives is the live reader itself, so it is never re-rendered for the animation.
 * Everything moves with view properties, which the phone's graphics chip draws.
 *
 * Styles: none, slide (the engine's own, not handled here), cover, fade, depth, book.
 */
class PageTurn(
    private val body: FrameLayout,
    private val pdfView: PDFView,
    private val style: () -> String,
    private val horizontal: Boolean,
    private val rtl: Boolean,
) {
    private val context = body.context
    private val density = context.resources.displayMetrics.density
    /** The leaving page, under the reader (the new page comes over it) or over it (the old page goes away). */
    private val under = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_XY; visibility = View.GONE }
    private val over = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_XY; visibility = View.GONE }
    /** Darkens the reader while a page lies over it. */
    private val shade = View(context).apply { setBackgroundColor(Color.BLACK); alpha = 0f; visibility = View.GONE }
    private var shot: Bitmap? = null
    private var running: ValueAnimator? = null

    val busy get() = running != null
    /** How many turns were animated (for the checks), and a slow-motion factor so a check can photograph one. */
    var played = 0; private set
    var slowMotion = 1f

    init {
        val i = body.indexOfChild(pdfView)
        body.addView(under, i, FrameLayout.LayoutParams(-1, -1))
        body.addView(shade, i + 2, FrameLayout.LayoutParams(-1, -1))
        body.addView(over, i + 3, FrameLayout.LayoutParams(-1, -1))
        pdfView.setPageTurnHandler { from, to, direction -> turn(from, to, direction) }
    }

    private fun turn(from: Int, to: Int, direction: Int): Boolean {
        val style = style()
        if (style == "slide") return false
        finish()
        if (style == "none" || pdfView.width == 0 || pdfView.height == 0) { pdfView.jumpTo(to); return true }
        pdfView.jumpTo(from) // the whole page at rest, not where the finger left it
        val picture = picture() ?: return false // the engine slides instead
        pdfView.jumpTo(to)
        play(style, picture, direction > 0)
        return true
    }

    /**
     * The page on screen now, drawn once at half size into a reused picture. It is only seen while it moves
     * (a third of a second), and a quarter of the pixels draws four times faster: drawing it at full size froze
     * a slow phone. (Letting the graphics chip draw it is faster still, but not every phone can do that.)
     */
    private fun picture(): Bitmap? = runCatching {
        val w = (pdfView.width * SCALE).toInt().coerceAtLeast(1); val h = (pdfView.height * SCALE).toInt().coerceAtLeast(1)
        val b = shot?.takeIf { it.width == w && it.height == h && !it.isRecycled }
            ?: Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565).also { shot?.recycle(); shot = it }
        val canvas = android.graphics.Canvas(b)
        canvas.scale(SCALE, SCALE)
        pdfView.drawSnapshot(canvas)
        b
    }.onFailure { android.util.Log.w("PageTurn", "no picture of the page", it) }.getOrNull()

    private fun play(style: String, picture: Bitmap, forward: Boolean) {
        val w = pdfView.width.toFloat(); val h = pdfView.height.toFloat()
        // the side the next page comes from: below when flipping up and down; the reading side otherwise
        val (ex, ey) = when {
            !horizontal -> 0f to h
            rtl -> -w to 0f
            else -> w to 0f
        }
        pdfView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        val step: (Float) -> Unit
        when (style) {
            "fade" -> {
                show(under, picture)
                step = { t -> pdfView.alpha = t }
            }
            "cover", "depth" -> {
                val depth = style == "depth"
                if (forward) {
                    // the new page slides in over the old one, which waits (cover) or steps back (depth)
                    show(under, picture)
                    pdfView.elevation = 10 * density
                    step = { t ->
                        pdfView.translationX = ex * (1 - t); pdfView.translationY = ey * (1 - t)
                        dim(under, .35f * t)
                        if (depth) { val s = 1 - .12f * t; under.scaleX = s; under.scaleY = s }
                    }
                } else {
                    // the old page slides back out the way it came, uncovering the one before it
                    show(over, picture)
                    over.elevation = 10 * density
                    shade.visibility = View.VISIBLE
                    step = { t ->
                        over.translationX = ex * t; over.translationY = ey * t
                        shade.alpha = .35f * (1 - t)
                        if (depth) { val s = .88f + .12f * t; pdfView.scaleX = s; pdfView.scaleY = s }
                    }
                }
            }
            else -> { // "book": the page turns over around the spine (on the right in Hebrew, as in a book)
                val spineRight = rtl
                val sign = if (spineRight) 1f else -1f
                val camera = 9000 * density
                if (forward) {
                    show(over, picture)
                    over.pivotX = if (spineRight) w else 0f; over.pivotY = h / 2; over.cameraDistance = camera
                    shade.visibility = View.VISIBLE
                    step = { t ->
                        over.rotationY = sign * 90f * t
                        dim(over, .45f * t)
                        shade.alpha = .3f * (1 - t)
                    }
                } else {
                    show(under, picture)
                    pdfView.pivotX = if (spineRight) w else 0f; pdfView.pivotY = h / 2; pdfView.cameraDistance = camera
                    step = { t ->
                        pdfView.rotationY = sign * 90f * (1 - t)
                        dim(under, .3f * t)
                    }
                }
            }
        }
        step(0f)
        played++
        running = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ((if (style == "book") 380 else 280) * slowMotion).toLong()
            interpolator = DecelerateInterpolator(1.4f)
            addUpdateListener { step(it.animatedValue as Float) }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) = reset()
            })
            start()
        }
    }

    /** Ends a turn at once (a new touch, a new turn, leaving the screen). */
    fun finish() { running?.end(); running = null }

    /** Lets the picture go when the screen is no longer seen. */
    fun release() { finish(); shot?.recycle(); shot = null }

    private companion object { const val SCALE = .5f }

    private fun show(view: ImageView, picture: Bitmap) {
        view.setImageBitmap(picture); view.clearColorFilter(); view.visibility = View.VISIBLE
    }

    private fun dim(view: ImageView, amount: Float) =
        view.setColorFilter(Color.argb((amount * 255).toInt().coerceIn(0, 255), 0, 0, 0), PorterDuff.Mode.SRC_ATOP)

    private fun reset() {
        running = null
        for (v in listOf(under, over)) {
            v.visibility = View.GONE; v.setImageDrawable(null); v.clearColorFilter()
            v.translationX = 0f; v.translationY = 0f; v.scaleX = 1f; v.scaleY = 1f; v.rotationY = 0f; v.elevation = 0f
        }
        shade.visibility = View.GONE; shade.alpha = 0f
        pdfView.alpha = 1f; pdfView.translationX = 0f; pdfView.translationY = 0f
        pdfView.scaleX = 1f; pdfView.scaleY = 1f; pdfView.rotationY = 0f; pdfView.elevation = 0f
        pdfView.setLayerType(View.LAYER_TYPE_NONE, null)
    }
}
