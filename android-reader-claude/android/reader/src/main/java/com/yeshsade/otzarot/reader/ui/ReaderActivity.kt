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
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.yeshsade.otzarot.reader.data.FramePolicy
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
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

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
    private lateinit var bookmarks: Bookmarks
    private lateinit var header: View
    private lateinit var toolbar: View
    private lateinit var scrubber: Scrubber
    private lateinit var infoLine: InfoLine
    private lateinit var chrome: Chrome
    /** The phone's own bars (measured even while hidden), for the page handle's track. */
    private var statusInset = 0
    private var navInset = 0
    /** Frame and handle settings this screen was built with; a change rebuilds it. */
    private var builtWith = ""
    private val main = Handler(Looper.getMainLooper())
    private val saveRunnable = Runnable { saveNow() }

    private var doc: Doc? = null
    private var snapshot: File? = null
    private val saveState = SaveState()
    private val undo = UndoStack()
    private var pageCount = 0
    private var failureShown = false

    /**
     * Every change to the open document, and every capture of it for saving, runs here in order — never on
     * the main thread. A capture holds the engine for the whole file (seconds for a big booklet), so a tap
     * on a box is shown at once and applied here when the engine is free; the screen never freezes.
     */
    private val docThread = Executors.newSingleThreadExecutor { r -> Thread(r, "otzarot-doc") }

    /** Guards capturing the document so the screen can't be torn down in the middle of a capture. */
    private val captureLock = Any()
    private var destroyed = false
    @Volatile private var capturedGeneration = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        builtWith = layoutSettings()
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

    override fun onResume() {
        super.onResume()
        if (layoutSettings() != builtWith) { recreate(); return }
        infoLine.listen(true)
        chrome.show()
    }

    override fun onPause() {
        infoLine.listen(false)
        chrome.stop()
        super.onPause()
    }

    private fun layoutSettings() = app.settings.run { "$frameMode|$scrubberSide|$scrubberHoldMillis|$scrubberAlways|$scroll" }

    override fun onStop() {
        if (saveState.dirty) saveNow()
        super.onStop()
    }

    override fun onDestroy() {
        main.removeCallbacks(saveRunnable)
        // After the marks still waiting for the engine: a mark made after the last capture must not be lost
        // when the screen closes. (The engine is recycled only after this.)
        val generation = saveState.generation
        val pages = pageCount
        val last = docThread.submit {
            synchronized(captureLock) {
                if (generation != capturedGeneration && pages > 0) {
                    val target = doc
                    val file = captureLocked(generation)
                    if (target != null && file != null) app.saves.execute { write(target, file) }
                }
                destroyed = true
            }
        }
        runCatching { last.get(FINAL_CAPTURE_WAIT_S, TimeUnit.SECONDS) }
        docThread.shutdown()
        runCatching { docThread.awaitTermination(FINAL_CAPTURE_WAIT_S, TimeUnit.SECONDS) }
        pdfView.recycle()
        super.onDestroy()
    }

    // ---------- screen ----------

    private fun buildScreen() {
        val settings = app.settings
        val root = ui.column().apply { setBackgroundColor(ui.colors.canvas) }
        val (header, titles) = ui.header(getString(R.string.app_name), " ")
        this.header = header
        titleView = titles.getChildAt(0) as TextView
        subtitle = titles.findViewWithTag("subtitle")
        undoButton = ui.headerButton(R.drawable.ic_undo, getString(R.string.reader_undo)) { undoLast() }
        undoButton.alpha = .45f
        header.addView(undoButton)
        header.addView(ui.headerButton(if (ui.night) R.drawable.otzr_ic_sun else R.drawable.otzr_ic_moon,
            getString(if (ui.night) R.string.library_day else R.string.library_night)) { toggleNight() })
        header.addView(ui.headerButton(R.drawable.otzr_ic_back, getString(R.string.reader_back)) { leave() })
        header.elevation = ui.dp(4).toFloat()

        // The frame can hide: then the top bar floats over the page, so hiding it never resizes (and moves) the page.
        val hides = FramePolicy.canHide(settings.frameMode)
        if (!hides) root.addView(header)

        val body = FrameLayout(this)
        pdfView = PDFView(this, null).apply { setBackgroundColor(ui.colors.canvas) }
        body.addView(pdfView, FrameLayout.LayoutParams(-1, -1))
        bookmarks = Bookmarks(this, pdfView, ui, settings, app.quick) { step ->
            undo.push(step)
            marked()
        }
        pdfView.setOverlayDrawer { canvas -> bookmarks.draw(canvas) }
        body.addView(Bookmarks.TouchLayer(this, bookmarks), FrameLayout.LayoutParams(-1, -1))

        val toolbar = toolbar()
        this.toolbar = toolbar
        scrubber = Scrubber(this, ui, settings.scrubberSide, horizontal = settings.scrollHorizontal,
            holdMillis = settings.scrubberHoldMillis,
            // the handle runs the whole height the page has: under the top bar only while the bar is up, and
            // past the toolbar (which sits in the middle, not at the edge) — "it doesn't go down to the end"
            topInset = { if (hides && (!::chrome.isInitialized || chrome.shown)) header.height else if (hides) statusInset else 0 },
            bottomInset = {
                if (settings.scrollHorizontal) toolbar.height + (toolbar.layoutParams as FrameLayout.LayoutParams).bottomMargin
                else navInset
            },
            listener = object : Scrubber.Listener {
                override fun scrubTo(fraction: Float): String {
                    pdfView.setPositionOffset(fraction, false)
                    val page = if (pageCount > 0) pdfView.currentPage else 0
                    return getString(R.string.reader_page, page + 1, pageCount)
                }
                override fun scrubStarted() = chrome.hold()
                override fun scrubEnded() = chrome.used()
            })
        body.addView(scrubber, FrameLayout.LayoutParams(-1, -1))
        if (hides) body.addView(header, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        body.addView(toolbar, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = ui.dp(14)
        })
        infoLine = InfoLine(this, settings)
        body.addView(infoLine, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))

        if (hides) installFloating(root, header, toolbar) else ui.install(this, root, header)
        chrome = Chrome(window, root, top = header, bottom = toolbar,
            faders = if (settings.scrubberAlways) emptyList() else listOf(scrubber), info = infoLine, settings = settings)
        chrome.changed = { scrubber.invalidate() }
        scrubber.frameShown = { settings.scrubberAlways || chrome.shown }
        chrome.start()
    }

    /**
     * Window insets when the frame can hide. Uses the phone's bars' size even while they are hidden, so the
     * layout stays the same when they come and go (the page never jumps).
     */
    private fun installFloating(root: LinearLayout, header: View, toolbar: View) {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.systemBars())
            val cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            header.setPadding(header.paddingLeft, bars.top + ui.dp(8), header.paddingRight, header.paddingBottom)
            root.setPadding(bars.left, 0, bars.right, 0)
            (toolbar.layoutParams as FrameLayout.LayoutParams).bottomMargin = ui.dp(14) + bars.bottom
            statusInset = bars.top
            navInset = bars.bottom
            toolbar.requestLayout()
            (infoLine.layoutParams as FrameLayout.LayoutParams).topMargin = cutout.top
            infoLine.requestLayout()
            insets
        }
        setContentView(root)
        WindowCompat.getInsetsController(window, root).isAppearanceLightStatusBars = false
        WindowCompat.getInsetsController(window, root).isAppearanceLightNavigationBars = !ui.night
        ViewCompat.requestApplyInsets(root)
    }

    /** Touching the top bar, the toolbar or the handle keeps the frame up for its full time again. */
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN && ::chrome.isInitialized && chrome.shown) {
            if (inside(header, event) || inside(toolbar, event)) chrome.used()
        }
        return super.dispatchTouchEvent(event)
    }

    private fun inside(view: View, event: MotionEvent): Boolean {
        if (view.visibility != View.VISIBLE) return false
        val at = IntArray(2); view.getLocationOnScreen(at)
        return event.rawX >= at[0] && event.rawX < at[0] + view.width && event.rawY >= at[1] && event.rawY < at[1] + view.height
    }

    /** Floating reading bar. Reading and bookmarks work; the marker and pen come next. */
    private fun toolbar(): View {
        val bar = ui.row().apply {
            background = ui.rounded(ui.colors.card, 26f, border = ui.colors.border)
            elevation = ui.dp(6).toFloat()
            setPadding(ui.dp(8), ui.dp(6), ui.dp(8), ui.dp(6))
        }
        class Tool(val icon: Int, val label: Int, val selected: Boolean, val enabled: Boolean,
                   val tap: (() -> Unit)? = null, val hold: (() -> Unit)? = null)
        listOf(
            Tool(R.drawable.ic_book, R.string.tool_read, selected = true, enabled = true),
            Tool(R.drawable.ic_marker, R.string.tool_marker, selected = false, enabled = false),
            Tool(R.drawable.ic_pen, R.string.tool_pen, selected = false, enabled = false),
            Tool(R.drawable.ic_bookmark, R.string.tool_bookmark, selected = false, enabled = true,
                tap = { addBookmark() }, hold = { bookmarks.showList(); Unit }),
        ).forEach { tool ->
            val ink = when {
                tool.selected -> ui.colors.accent
                else -> ui.colors.ink
            }
            val item = ui.column().apply {
                gravity = Gravity.CENTER
                setPadding(ui.dp(8), ui.dp(6), ui.dp(8), ui.dp(6))
                minimumWidth = ui.dp(72)
                if (tool.selected) background = ui.rounded(ui.colors.selection, 18f)
                alpha = if (tool.enabled) 1f else .4f
                contentDescription = getString(tool.label)
                tool.tap?.let { action -> setOnClickListener { action() } }
                tool.hold?.let { action -> setOnLongClickListener { action(); true } }
            }
            item.addView(ImageView(this).apply {
                setImageResource(tool.icon)
                imageTintList = ColorStateList.valueOf(if (tool.enabled) ink else ui.colors.muted)
            }, LinearLayout.LayoutParams(ui.dp(24), ui.dp(24)))
            item.addView(ui.label(getString(tool.label), 12f, if (tool.enabled) ink else ui.colors.muted).apply {
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
                    // the notice is part of this screen: close the screen after it has been read
                    Messages.notice(this, getString(R.string.open_failed))
                    main.postDelayed({ if (!isDestroyed) finish() }, 3000)
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
        bookmarks.clear()
        titleView.text = target.name
        subtitle.text = " "
        // On the saves' thread: a save of this document still running from a moment ago finishes first (so the
        // copy has its marks), but opening never waits behind scans of the device.
        app.saves.execute {
            val file = snapshotFile(target)
            val ok = runCatching { prepareSnapshot(target, Uri.parse(target.uri), file); true }.getOrDefault(false)
            main.post {
                if (isFinishing || isDestroyed) return@post
                if (!ok) {
                    Messages.notice(this, getString(R.string.open_failed))
                    return@post
                }
                snapshot = file
                display(target, file)
            }
        }
    }

    /**
     * The private copy the screen shows. It is not copied again when it is known to match the document:
     * a file on the device whose size and time did not change, or the app's own working copy whose content
     * is what the app last saved. After a save the saved capture itself becomes this copy, so opening the
     * booklet again never copies it (copying a big booklet on every opening is what made opening slow).
     */
    private fun prepareSnapshot(target: Doc, source: Uri, file: File) {
        val stamp = File(file.path + ".stamp")
        val now = currentStamp(target, source)
        if (now != null && file.isFile && runCatching { stamp.readText() }.getOrNull() == now) return
        stamp.delete()
        val partial = File(file.path + ".part")
        contentResolver.openInputStream(source)!!.use { input -> partial.outputStream().use { input.copyTo(it) } }
        if (!partial.renameTo(file)) { file.delete(); check(partial.renameTo(file)) }
        if (now != null) stamp.writeText(now)
    }

    private fun snapshotFile(target: Doc) = File(File(cacheDir, "open").apply { mkdirs() }, target.id + ".pdf")

    /**
     * What the document is now, cheaply, to tell whether the private copy still matches it; null when it
     * can't be told without reading the whole file (then the copy is always refreshed).
     * - a file on the device: its size and time;
     * - the app's own working copy (only the app writes it): the content identity the app last saved,
     *   checked against the file's current size.
     */
    private fun currentStamp(target: Doc, source: Uri): String? {
        if (source.scheme == "file") {
            return File(source.path ?: return null).takeIf { it.isFile }?.let { "${it.length()}:${it.lastModified()}" }
        }
        if (target.kind != Doc.Kind.COPY) return null
        val identity = (app.library.get(target.id) ?: target).identity.ifEmpty { return null }
        val size = runCatching {
            contentResolver.query(source, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
            }
        }.getOrNull() ?: return null
        return if (identity.startsWith("s1-$size-")) "copy:$identity" else null
    }

    /**
     * After a verified save: the capture is exactly what the document now holds, so it becomes the private
     * copy for the next opening (instead of being deleted and the booklet copied again). The screen keeps
     * reading the file it opened — renaming over it doesn't change what the engine has open.
     */
    private fun adoptAsSnapshot(target: Doc, source: Uri, capture: File, identity: String): Boolean {
        val file = snapshotFile(target)
        val stamp = File(file.path + ".stamp")
        stamp.delete()
        if (!capture.renameTo(file)) return false
        val now = if (source.scheme == "file") currentStamp(target, source)
            else if (target.kind == Doc.Kind.COPY) "copy:$identity" else null
        if (now != null) runCatching { stamp.writeText(now) }
        return true
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
            .onPageScroll { _, offset -> scrubber.follow(offset); scrubber.pagesMoved() }
            .onLoad { pages ->
                pageCount = pages
                showPage(pdfView.currentPage)
                bookmarks.load(pages)
                app.io.execute { app.library.update(target.id) { it.copy(pages = pages, lastOpened = System.currentTimeMillis()) } }
            }
            .onPageChange { page, pages ->
                pageCount = pages
                showPage(page)
                app.io.execute { app.library.update(target.id) { it.copy(page = page) } }
            }
            .onError { Messages.notice(this, getString(R.string.open_failed)) }
            .load()
    }

    private fun showPage(page: Int) {
        val text = getString(R.string.reader_page, page + 1, pageCount)
        subtitle.text = text
        infoLine.setPage(text)
        scrubber.follow(pdfView.positionOffset)
    }

    // ---------- marking ----------

    private fun tap(event: MotionEvent): Boolean {
        // a tap on a booklet button (link) follows it; a tap on a check box only marks it;
        // any other tap on the page shows or hides the frame.
        // Boxes are found from what the screen already knows, so a tap never waits for a save in progress.
        val known = pdfView.canPeekFormFieldAt(event.x, event.y)
        val field = (if (known) pdfView.peekFormFieldAt(event.x, event.y) else pdfView.findFormFieldAt(event.x, event.y)) ?: run {
            if (pdfView.findLinkAt(event.x, event.y) != null) { chrome.used(); return false }
            chrome.toggle(); return true
        }
        if (field.readOnly) return false
        val checked = when (field.type) {
            PdfDocument.FormField.TYPE_CHECKBOX -> !field.checked
            PdfDocument.FormField.TYPE_RADIO_BUTTON -> if (field.checked) return true else true
            else -> return false
        }
        val step = UndoStack.Mark(field.pageIndex, field.annotationIndex, field.checked)
        undo.push(step)
        mark(field.pageIndex, field.annotationIndex, checked) { ok -> if (!ok) undo.remove(step) }
        return true
    }

    /** Shows the box's new state now, and changes the document on [docThread]. */
    private fun mark(page: Int, annotation: Int, checked: Boolean, done: (Boolean) -> Unit = {}) {
        pdfView.showPendingCheck(page, annotation, checked)
        marked()
        runCatching {
            docThread.execute {
                val ok = pdfView.setFormFieldCheckedInBackground(page, annotation, checked)
                main.post {
                    if (isDestroyed) return@post
                    pdfView.formFieldApplied(page, annotation, ok)
                    done(ok)
                    undoButton.alpha = if (undo.isEmpty) .45f else 1f
                }
            }
        }
    }

    private fun undoLast() {
        when (val step = undo.pop() ?: return) {
            is UndoStack.Mark -> mark(step.page, step.annotation, step.before)
            is UndoStack.BookmarkChange -> { bookmarks.revert(step); marked() }
        }
    }

    /** The last bookmark hint shown (for tests and screenshots). */
    var lastHint: Messages.Ask? = null
        private set

    private fun addBookmark() {
        if (pageCount == 0) return
        bookmarks.add { added ->
            if (added == null || isDestroyed) return@add
            val settings = app.settings
            if (!settings.bookmarkHintNever && !app.bookmarkHintShown) {
                app.bookmarkHintShown = true
                chrome.hold()
                lastHint = Messages.info(this, ui, getString(R.string.bm_hint_title), getString(R.string.bm_hint_heading),
                    listOf(getString(R.string.bm_hint_drag), getString(R.string.bm_hint_menu), getString(R.string.bm_hint_list)),
                    never = getString(R.string.bm_hint_never), ok = getString(R.string.action_ok)) { never ->
                    if (never) settings.bookmarkHintNever = true
                    chrome.used()
                }
            }
        }
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
        val covered = saveState.begin() ?: return
        app.saves.execute {
            // the capture waits, on the document's thread, for the marks tapped before it
            val file = runCatching {
                docThread.submit<File?> { synchronized(captureLock) { if (destroyed) null else captureLocked(covered) } }.get()
            }.getOrNull()
            val ok = file != null && write(target, file)
            main.post {
                val again = saveState.finish(ok)
                if (!ok && !failureShown && !isDestroyed) {
                    failureShown = true
                    Messages.notice(this, getString(R.string.save_failed), long = true)
                }
                if (ok && again && !isDestroyed) main.postDelayed(saveRunnable, RETRY_DELAY_MS)
            }
        }
    }

    /**
     * Writes the current document (with marks) to a private temp file. Runs on [docThread], after every mark
     * up to [generation] (they were queued before it). Caller holds [captureLock].
     */
    private fun captureLocked(generation: Long): File? {
        val file = File(cacheDir, "save-${System.nanoTime()}.pdf")
        val ok = runCatching { pdfView.saveAsCopy(file) }.getOrDefault(false)
        if (!ok) { file.delete(); return null }
        capturedGeneration = generation
        return file
    }

    /** Runs on the io thread. Writes the capture into the document's real file and verifies it. */
    private fun write(target: Doc, file: File): Boolean {
        var kept = false
        try {
            val uri = Uri.parse(target.uri)
            // A file found on the device: remember what the original was before the first marks change it,
            // so the same original arriving again (e.g. from WhatsApp) opens this entry.
            val current = app.library.get(target.id) ?: target
            if (current.originIdentity.isEmpty()) {
                Identity.of(this, uri)?.let { origin ->
                    app.library.update(target.id) { it.copy(originIdentity = origin, identity = it.identity.ifEmpty { origin }) }
                }
            }
            val size = file.length()
            val written = Files.writeVerified(this, uri, file) ?: return false
            app.library.update(target.id) { it.copy(identity = written, size = size) }
            kept = runCatching { adoptAsSnapshot(target, uri, file, written) }.getOrDefault(false)
            return true
        } finally {
            if (!kept) file.delete()
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
        private const val FINAL_CAPTURE_WAIT_S = 20L

        fun open(activity: android.app.Activity, doc: Doc) {
            activity.startActivity(Intent(activity, ReaderActivity::class.java).putExtra(EXTRA_DOC, doc.id))
        }
    }
}
