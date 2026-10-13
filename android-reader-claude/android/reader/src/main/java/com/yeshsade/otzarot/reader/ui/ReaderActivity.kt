// Lean Otzarot reader. License: GPLv3.
package com.yeshsade.otzarot.reader.ui

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.github.barteksc.pdfviewer.PDFView
import com.github.barteksc.pdfviewer.util.FitPolicy
import com.shockwave.pdfium.PdfDocument
import com.yeshsade.otzarot.reader.R
import com.yeshsade.otzarot.reader.ReaderApp
import com.yeshsade.otzarot.reader.app
import com.yeshsade.otzarot.reader.data.Doc
import com.yeshsade.otzarot.reader.data.Files
import com.yeshsade.otzarot.reader.data.Identity
import com.yeshsade.otzarot.reader.data.Intake
import com.yeshsade.otzarot.reader.data.SaveState
import com.yeshsade.otzarot.reader.data.UndoStack
import java.io.File

/**
 * Reading screen. Marks are saved by themselves into the PDF: ~1.5 s after the last tap, and at once
 * when leaving or going to the background. No save button, no "save?" question.
 *
 * Safety: the engine reads its file lazily, so the screen shows a private snapshot of the document and
 * saves write the real file. The file being displayed is never overwritten under the engine.
 */
class ReaderActivity : AppCompatActivity() {
    private lateinit var ui: Ui
    private lateinit var pdfView: PDFView
    private lateinit var titleView: TextView
    private lateinit var subtitle: TextView
    private lateinit var undoButton: ImageButton
    private val main = Handler(Looper.getMainLooper())
    private val saveRunnable = Runnable { saveNow() }

    private var doc: Doc? = null
    private var snapshot: File? = null
    private val saveState = SaveState()
    private val undo = UndoStack()
    private var pageCount = 0
    private var failureShown = false

    /** Guards capturing the document so the screen can't be torn down in the middle of a capture. */
    private val captureLock = Any()
    private var destroyed = false
    private var capturedGeneration = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        buildScreen()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = leave()
        })
        handle(intent, savedInstanceState?.getString(STATE_DOC))
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        saveNow()
        handle(intent, null)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        doc?.let { outState.putString(STATE_DOC, it.id) }
        super.onSaveInstanceState(outState)
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        (application as ReaderApp).windowPolicy.touched(this)
    }

    override fun onStop() {
        if (saveState.dirty) saveNow()
        super.onStop()
    }

    override fun onDestroy() {
        main.removeCallbacks(saveRunnable)
        synchronized(captureLock) {
            // A mark made after the last capture must not be lost when the screen closes.
            if (saveState.generation != capturedGeneration && pageCount > 0) {
                val target = doc
                val file = captureLocked()
                if (target != null && file != null) app.io.execute { write(target, file) }
            }
            destroyed = true
        }
        pdfView.recycle()
        super.onDestroy()
    }

    // ---------- screen ----------

    private fun buildScreen() {
        val root = ui.column().apply { setBackgroundColor(ui.colors.canvas) }
        val (header, titles) = ui.header(getString(R.string.app_name), " ")
        titleView = titles.getChildAt(0) as TextView
        subtitle = titles.findViewWithTag("subtitle")
        undoButton = ui.headerButton(R.drawable.ic_undo, getString(R.string.reader_undo)) { undoLast() }
        undoButton.alpha = .45f
        header.addView(undoButton)
        header.addView(ui.headerButton(if (ui.night) R.drawable.otzr_ic_sun else R.drawable.otzr_ic_moon,
            getString(if (ui.night) R.string.library_day else R.string.library_night)) { toggleNight() })
        header.addView(ui.headerButton(R.drawable.otzr_ic_back, getString(R.string.reader_back)) { leave() })
        root.addView(header)

        val body = FrameLayout(this)
        pdfView = PDFView(this, null).apply { setBackgroundColor(ui.colors.canvas) }
        body.addView(pdfView, FrameLayout.LayoutParams(-1, -1))
        body.addView(toolbar(), FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = ui.dp(14)
        })
        root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        ui.install(this, root, header)
    }

    /** Floating reading bar. Only "reading" works in this build; the marking tools come next. */
    private fun toolbar(): View {
        val bar = ui.row().apply {
            background = ui.rounded(ui.colors.card, 26f, border = ui.colors.border)
            elevation = ui.dp(6).toFloat()
            setPadding(ui.dp(8), ui.dp(6), ui.dp(8), ui.dp(6))
        }
        listOf(
            Triple(R.drawable.ic_book, R.string.tool_read, true),
            Triple(R.drawable.ic_marker, R.string.tool_marker, false),
            Triple(R.drawable.ic_pen, R.string.tool_pen, false),
            Triple(R.drawable.ic_bookmark, R.string.tool_bookmark, false),
        ).forEach { (icon, label, active) ->
            val item = ui.column().apply {
                gravity = Gravity.CENTER
                setPadding(ui.dp(8), ui.dp(6), ui.dp(8), ui.dp(6))
                minimumWidth = ui.dp(72)
                if (active) background = ui.rounded(ui.colors.selection, 18f)
                alpha = if (active) 1f else .4f
                contentDescription = getString(label)
            }
            item.addView(ImageView(this).apply {
                setImageResource(icon)
                imageTintList = ColorStateList.valueOf(if (active) ui.colors.accent else ui.colors.muted)
            }, LinearLayout.LayoutParams(ui.dp(24), ui.dp(24)))
            item.addView(ui.label(getString(label), 12f, if (active) ui.colors.accent else ui.colors.muted).apply {
                gravity = Gravity.CENTER
                maxLines = 1
            }, LinearLayout.LayoutParams(-2, -2))
            bar.addView(item, LinearLayout.LayoutParams(-2, -2))
        }
        return bar
    }

    // ---------- opening ----------

    private fun handle(intent: Intent, restoredId: String?) {
        val library = app.library
        val id = restoredId ?: intent.getStringExtra(EXTRA_DOC)
        if (id != null) {
            library.get(id)?.let { show(it); return }
        }
        val uri = incomingUri(intent) ?: run { finish(); return }
        app.io.execute {
            val opened = runCatching { Intake(this, library, app.settings).open(uri) }.getOrNull()
            main.post {
                if (isFinishing || isDestroyed) return@post
                if (opened == null) {
                    Toast.makeText(this, R.string.open_failed, Toast.LENGTH_LONG).show()
                    finish()
                } else show(opened)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun incomingUri(intent: Intent): Uri? = when (intent.action) {
        Intent.ACTION_VIEW -> intent.data
        Intent.ACTION_SEND -> if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else intent.getParcelableExtra(Intent.EXTRA_STREAM)
        else -> intent.data
    }

    private fun show(target: Doc) {
        if (doc?.id == target.id && pageCount > 0) return
        doc = target
        titleView.text = target.name
        subtitle.text = " "
        app.io.execute {
            val file = File(cacheDir, "open").apply { mkdirs() }.let { File(it, target.id + ".pdf") }
            val ok = runCatching {
                contentResolver.openInputStream(Uri.parse(target.uri))!!.use { input ->
                    file.outputStream().use { input.copyTo(it) }
                }
                true
            }.getOrDefault(false)
            main.post {
                if (isFinishing || isDestroyed) return@post
                if (!ok) {
                    Toast.makeText(this, R.string.open_failed, Toast.LENGTH_LONG).show()
                    return@post
                }
                snapshot = file
                display(target, file)
            }
        }
    }

    private fun display(target: Doc, file: File) {
        val settings = app.settings
        val startPage = if (settings.resume == "last") target.page else 0
        pdfView.fromFile(file)
            .defaultPage(startPage)
            .swipeHorizontal(settings.scroll == "horizontal")
            .horizontalReadingDirectionRtl(ui.rtl(settings.language))
            .pageFitPolicy(FitPolicy.BOTH) // the whole page is visible on opening (owner's decision)
            .fitEachPage(true)
            .nightMode(settings.night)
            .enableAnnotationRendering(true)
            .enableDoubleTap(true)
            .spacing(8)
            .onTap { event -> tap(event) }
            .onLoad { pages ->
                pageCount = pages
                showPage(pdfView.currentPage)
                app.io.execute { app.library.update(target.id) { it.copy(pages = pages, lastOpened = System.currentTimeMillis()) } }
            }
            .onPageChange { page, pages ->
                pageCount = pages
                showPage(page)
                app.io.execute { app.library.update(target.id) { it.copy(page = page) } }
            }
            .onError { Toast.makeText(this, R.string.open_failed, Toast.LENGTH_LONG).show() }
            .load()
    }

    private fun showPage(page: Int) {
        subtitle.text = getString(R.string.reader_page, page + 1, pageCount)
    }

    // ---------- marking ----------

    private fun tap(event: MotionEvent): Boolean {
        val field = pdfView.findFormFieldAt(event.x, event.y) ?: return false
        if (field.readOnly) return false
        val checked = when (field.type) {
            PdfDocument.FormField.TYPE_CHECKBOX -> !field.checked
            PdfDocument.FormField.TYPE_RADIO_BUTTON -> if (field.checked) return true else true
            else -> return false
        }
        if (!pdfView.setFormFieldChecked(field.pageIndex, field.annotationIndex, checked)) return true
        undo.push(UndoStack.Mark(field.pageIndex, field.annotationIndex, field.checked))
        marked()
        return true
    }

    private fun undoLast() {
        val mark = undo.pop() ?: return
        pdfView.setFormFieldChecked(mark.page, mark.annotation, mark.before)
        marked()
    }

    private fun marked() {
        saveState.edit()
        undoButton.alpha = if (undo.isEmpty) .45f else 1f
        main.removeCallbacks(saveRunnable)
        main.postDelayed(saveRunnable, SAVE_DELAY_MS)
    }

    // ---------- saving ----------

    private fun saveNow() {
        main.removeCallbacks(saveRunnable)
        val target = doc ?: return
        saveState.begin() ?: return
        app.io.execute {
            val file = synchronized(captureLock) { if (destroyed) null else captureLocked() }
            val ok = file != null && write(target, file)
            main.post {
                val again = saveState.finish(ok)
                if (!ok && !failureShown && !isDestroyed) {
                    failureShown = true
                    Toast.makeText(this, R.string.save_failed, Toast.LENGTH_LONG).show()
                }
                if (ok && again && !isDestroyed) main.postDelayed(saveRunnable, RETRY_DELAY_MS)
            }
        }
    }

    /** Writes the current document (with marks) to a private temp file. Caller holds [captureLock]. */
    private fun captureLocked(): File? {
        val generation = saveState.generation
        val file = File(cacheDir, "save-${System.nanoTime()}.pdf")
        val ok = runCatching { pdfView.saveAsCopy(file) }.getOrDefault(false)
        if (!ok) { file.delete(); return null }
        capturedGeneration = generation
        return file
    }

    /** Runs on the io thread. Writes the capture into the document's real file and verifies it. */
    private fun write(target: Doc, file: File): Boolean {
        try {
            val ok = Files.writeVerified(this, Uri.parse(target.uri), file)
            if (ok) Identity.of(file)?.let { id -> app.library.update(target.id) { it.copy(identity = id) } }
            return ok
        } finally {
            file.delete()
        }
    }

    // ---------- navigation ----------

    private fun leave() {
        saveNow()
        if (isTaskRoot) {
            // The list becomes the base of the app's task. Otherwise Android would treat opening the same
            // file again from WhatsApp as "return to this task" and show the list instead of the document.
            startActivity(Intent(this, LibraryActivity::class.java)
                .putExtra(LibraryActivity.EXTRA_STAY, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        }
        finish()
    }

    private fun toggleNight() {
        saveNow()
        app.settings.night = !app.settings.night
        ReaderApp.applyTheme(app.settings)
    }

    companion object {
        const val EXTRA_DOC = "doc"
        private const val STATE_DOC = "doc"
        private const val SAVE_DELAY_MS = 1500L
        private const val RETRY_DELAY_MS = 300L

        fun open(activity: android.app.Activity, doc: Doc) {
            activity.startActivity(Intent(activity, ReaderActivity::class.java).putExtra(EXTRA_DOC, doc.id))
        }
    }
}
