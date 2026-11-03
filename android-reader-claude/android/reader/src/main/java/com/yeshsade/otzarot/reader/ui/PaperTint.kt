// Lean Otzarot reader. License: GPLv3.
package com.yeshsade.otzarot.reader.ui

import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import com.github.barteksc.pdfviewer.PDFView

/**
 * A hint of warm paper over the pages, so the screen is easier on the eyes (owner, 2026-11-03: "ממש לא נח לי.
 * ובקובץ הפיזי כל כך נח"). The tint was measured from a photo of the printed booklet beside a white sheet: the
 * booklet's paper came out at sketch level 1. Multiplied over the page, so black text stays black. Only by day.
 */
class PaperTint(private val pdfView: PDFView, private val night: Boolean) {
    var on: () -> Boolean = { true }

    private val multiply = Paint().apply {
        color = TINT
        if (Build.VERSION.SDK_INT >= 29) blendMode = BlendMode.MULTIPLY
    }
    /** Before Android 10 there is no multiply on the screen's drawing: a thin warm veil instead. */
    private val veil = Paint().apply { color = Color.argb(65, 251, 235, 200) }
    private val box = RectF()

    fun draw(canvas: Canvas) {
        if (night || !on()) return
        val range = pdfView.visiblePageRange ?: return
        val paint = if (Build.VERSION.SDK_INT >= 29) multiply else veil
        for (page in range[0]..range[1]) {
            if (!pdfView.isPageOpen(page)) continue
            val size = pdfView.getFrameSize(page) ?: continue
            box.set(0f, 0f, size[0], size[1])
            val inView = pdfView.frameRectToView(page, box) ?: continue
            canvas.drawRect(inView, paint)
        }
    }

    companion object {
        /** Level 1 of the sketch of 2026-11-03. */
        val TINT = Color.parseColor("#FEFAF1")
    }
}
