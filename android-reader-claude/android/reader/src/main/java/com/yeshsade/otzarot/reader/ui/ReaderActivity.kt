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
    private lateinit var pageTurn: PageTurn
    private lateinit var dayName: DayName
    /** The booklet's hidden map (stage 2), once read; null for a file without one. */
    private var map: com.yeshsade.otzarot.reader.data.ProgressMap? = null
    /** The map's boxes in the file: field name → its widgets (page, annotation), and whether each field is ticked. */
    private var widgets: Map<String, List<Pair<Int, Int>>> = emptyMap()
    private var fieldOf: Map<Long, String> = emptyMap()
    private val ticked = HashMap<String, Boolean>()
    private lateinit var undoButton: ImageButton
    private lateinit var redoButton: ImageButton
    private lateinit var bookmarks: Bookmarks
    private lateinit var header: View
    private lateinit var toolbar: View
    private lateinit var scrubber: Scrubber
    private lateinit var infoLine: InfoLine
    private lateinit var chrome: Chrome
    /** The phone's own bars (measured even while hidden), for the page handle's track. */
    private var statusInset = 0
    private var navInset = 0
    /**
     * While the frame is hidden the phone's bars are hidden too, so a swipe from the top (to pull down the
     * notifications) or from the bottom starts on the page. Such a swipe belongs to the phone, not to the
     * pages: without this the booklet flew back pages every time the notifications were pulled down.
     */
    private var edgeStart: android.graphics.PointF? = null
    private var edgeTop = 0
    private var edgeBottom = 0
    private var edgeSwipe = false
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

    private fun layoutSettings() = app.settings.run { "$frameMode|$scrubberSide|$scrubberHoldMillis|$scrubberAlways|$scroll|$pages" }

    override fun onStop() {
        if (::pageTurn.isInitialized) pageTurn.release()
        if (saveState.dirty) saveNow()
        super.onStop()
    }

    override fun onDestroy() {
        main.removeCallbacks(saveRunnable)
        // After the marks still waiting for the engine: a mark made after the last capture must not be lost
        // when the screen closes. The capture runs on the document's thread, in the background (review finding 6:
        // waiting for it here froze the screen for a moment on "back" right after a mark in a big booklet), and
        // only after it is the engine let go. (The view does not let it go by itself when the window closes.)
        val generation = saveState.generation
        val pages = pageCount
        val target = doc
        val view = pdfView
        // the screen stays in the list of open documents until its last marks are queued for saving: a screen
        // opening the same booklet in the meantime waits for that (closing and opening again quickly lost marks)
        fun release() {
            view.release()
            released = true
            if (target != null && app.readers[target.id] === this) app.readers.remove(target.id)
            afterRelease.toList().forEach { it() }
            afterRelease.clear()
        }
        val finished = runCatching {
            docThread.execute {
                synchronized(captureLock) {
                    if (generation != capturedGeneration && pages > 0) {
                        val file = captureLocked(generation)
                        if (target != null && file != null) app.saves.execute { write(target, file) }
                    }
                    destroyed = true
                }
                // the last capture's write is queued on the saves' thread now; the screen taking over loads after it
                main.post { release() }
            }
            docThread.shutdown()
        }.isSuccess
        if (!finished) release()
        super.onDestroy()
    }

    // ---------- screen ----------

    private fun buildScreen() {
        val settings = app.settings
        val root = ui.column().apply { setBackgroundColor(ui.colors.canvas) }
        // On the left ☰ and day/night; on the right undo and redo, outside the menu (owner, 2026-10-17/18:
        // "not in such a crude design" — "but delicate"): quiet arrows, dimmed while there is nothing to do.
        val (header, left, extras) = ui.menuBar(this) { openMenu() }
        left.addView(ui.nightButton(this) { saveNow() })
        this.header = header
        undoButton = ui.quietButton(R.drawable.ic_undo, getString(R.string.reader_undo)) { undoLast() }.apply { tag = "undo" }
        redoButton = ui.quietButton(R.drawable.ic_redo, getString(R.string.reader_redo)) { redoLast() }.apply { tag = "redo" }
        extras.addView(undoButton)
        extras.addView(redoButton)
        updateUndoButtons()
        header.elevation = ui.dp(4).toFloat()

        // The frame can hide: then the top bar floats over the page, so hiding it never resizes (and moves) the page.
        val hides = FramePolicy.canHide(settings.frameMode)
        if (!hides) root.addView(header)

        val body = FrameLayout(this)
        pdfView = PDFView(this, null).apply {
            setBackgroundColor(ui.colors.canvas)
            // two-finger zoom reaches 20 times the whole page, like Xodo (measured from the owner's screenshots,
            // 2026-10-19); a double tap still has one step and then back to the whole page
            maxZoom = MAX_ZOOM
        }
        body.addView(pdfView, FrameLayout.LayoutParams(-1, -1))
        pageTurn = PageTurn(body, pdfView, { if (settings.singlePage) settings.pageTurn else "slide" },
            horizontal = settings.scrollHorizontal, rtl = ui.rtl(settings.language))
        bookmarks = Bookmarks(this, pdfView, ui, settings, app.quick) { step ->
            undo.push(step)
            marked()
        }
        dayName = DayName(pdfView, Fonts.heading(this), ui.night)
        pdfView.setOverlayDrawer { canvas -> bookmarks.draw(canvas); dayName.draw(canvas) }
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
            val gestures = insets.getInsets(WindowInsetsCompat.Type.systemGestures())
            edgeTop = maxOf(bars.top, gestures.top, ui.dp(24))
            edgeBottom = maxOf(bars.bottom, gestures.bottom, ui.dp(20))
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
        if (event.actionMasked == MotionEvent.ACTION_DOWN && ::pageTurn.isInitialized) pageTurn.finish()
        if (event.actionMasked == MotionEvent.ACTION_DOWN && ::dayName.isInitialized) dayName.touched()
        if (event.actionMasked == MotionEvent.ACTION_DOWN && ::chrome.isInitialized) {
            edgeSwipe = false
            edgeStart = if (!chrome.shown && FramePolicy.canHide(app.settings.frameMode) && fromPhoneEdge(event))
                android.graphics.PointF(event.rawX, event.rawY) else null
            if (chrome.shown && (inside(header, event) || inside(toolbar, event))) chrome.used()
        }
        // A drag that starts at the top or bottom edge belongs to the phone (its notifications, its home gesture):
        // the pages don't move. But a tap there is the reader's — a booklet's "העמוד הבא" often sits right at the
        // top of the page (owner, 2026-10-20: the buttons "felt strange").
        val start = edgeStart
        if (!edgeSwipe && start != null && event.actionMasked == MotionEvent.ACTION_MOVE &&
            kotlin.math.hypot(event.rawX - start.x, event.rawY - start.y) > android.view.ViewConfiguration.get(this).scaledTouchSlop) {
            edgeSwipe = true
            val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
            super.dispatchTouchEvent(cancel)
            cancel.recycle()
        }
        if (edgeSwipe) {
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) { edgeSwipe = false; edgeStart = null }
            return true
        }
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) edgeStart = null
        return super.dispatchTouchEvent(event)
    }

    private fun fromPhoneEdge(event: MotionEvent): Boolean {
        val height = window.decorView.height
        return height > 0 && (event.rawY < edgeTop || event.rawY > height - edgeBottom)
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
            // owner, 2026-10-21: "התקדמות", a small live gauge with today's percentage ("שעון קטן חי")
            Tool(R.drawable.ic_gauge, R.string.tool_progress, selected = false, enabled = true,
                tap = { openProgress(ProgressScreen.Tab.MARK) }),
        ).forEach { tool ->
            val ink = when {
                tool.selected -> ui.colors.accent
                else -> ui.colors.ink
            }
            val item = ui.column().apply {
                gravity = Gravity.CENTER
                setPadding(ui.dp(8), ui.dp(6), ui.dp(8), ui.dp(6))
                minimumWidth = ui.dp(62)
                if (tool.label == R.string.tool_progress) tag = "tool_progress"
                if (tool.selected) background = ui.rounded(ui.colors.selection, 18f)
                alpha = if (tool.enabled) 1f else .4f
                contentDescription = getString(tool.label)
                tool.tap?.let { action -> setOnClickListener { action() } }
                tool.hold?.let { action -> setOnLongClickListener { action(); true } }
            }
            val icon = ImageView(this).apply {
                setImageResource(tool.icon)
                imageTintList = ColorStateList.valueOf(if (tool.enabled) ink else ui.colors.muted)
            }
            if (tool.label == R.string.tool_progress) {
                // the plain icon until the booklet's boxes are known, then the gauge in its place
                val box = FrameLayout(this)
                box.addView(icon, FrameLayout.LayoutParams(ui.dp(24), ui.dp(24), Gravity.CENTER))
                val gauge = Gauge(this, ui, big = false).apply { mini = true; miniNumber = true; visibility = View.GONE; tag = "tool_progress_gauge" }
                box.addView(gauge, FrameLayout.LayoutParams(ui.dp(30), ui.dp(26), Gravity.CENTER))
                toolbarGauge = gauge; toolbarGaugeIcon = icon
                item.addView(box, LinearLayout.LayoutParams(ui.dp(30), ui.dp(26)))
            } else item.addView(icon, LinearLayout.LayoutParams(ui.dp(24), ui.dp(24)))
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
        // undo and redo belong to the document they were made in
        undo.clear()
        updateUndoButtons()
        // The document is held by one screen at a time: an older screen showing it saves, closes, and only
        // then is it loaded here (owner's review finding, 2026-10-19).
        claim(target) { load(target) }
    }

    private fun load(target: Doc) {
        if (isFinishing || isDestroyed || doc?.id != target.id) return
        // On the saves' thread: a save of this document still running from a moment ago finishes first (so the
        // copy has its marks), but opening never waits behind scans of the device.
        app.saves.execute {
            // a save that was cut off last time (the phone died in the middle) is finished first
            runCatching {
                val last = (app.library.get(target.id) ?: target).identity
                Files.finishPending(this, Uri.parse(target.uri), target.id, last)?.let { done ->
                    app.library.update(target.id) { it.copy(identity = done) }
                }
            }
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

    /** Takes [target] for this screen. When another screen holds it, that screen hands it over first. */
    private fun claim(target: Doc, then: () -> Unit) {
        val readers = app.readers
        readers.entries.removeAll { it.value === this && it.key != target.id }
        val previous = readers[target.id]
        readers[target.id] = this
        if (previous == null || previous === this || previous.released) { then(); return }
        var started = false
        val go = { if (!started) { started = true; then() } }
        previous.handOff(go)
        // never wait for ever: a screen that does not close within this time loses the document anyway
        main.postDelayed(go, HANDOFF_WAIT_MS)
    }

    /** Another screen opens this document: close, and say when its last marks are queued for saving. */
    private var released = false
    private val afterRelease = mutableListOf<() -> Unit>()

    fun handOff(done: () -> Unit) {
        if (released) { done(); return }
        afterRelease += done
        if (!isFinishing && !isDestroyed) finish()
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
            // "עמוד אחד": every page fills one screen and a swipe brings exactly the next one, like a book.
            // "גלילה רציפה": the free feed the reader always had.
            .autoSpacing(settings.singlePage)
            .pageSnap(settings.singlePage)
            .pageFling(settings.singlePage)
            .onTap { event -> tap(event) }
            .onPageScroll { _, offset -> scrubber.follow(offset); scrubber.pagesMoved() }
            .onLoad { pages ->
                pageCount = pages
                readMap(target)
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
            // let go of the engine only after the last capture (see onDestroy), not when the window closes
            .autoReleasingWhenDetachedFromWindow(false)
            .load()
    }

    /**
     * Reads the booklet's hidden map, on the document's thread (the engine is not shared between threads), and
     * hands it to what uses it. A file without a map, or with one that can't be trusted, simply has none.
     */
    private fun readMap(target: Doc) {
        map = null
        parsedMap = null
        mapState = MapState.LOADING
        dayName.map = null
        widgets = emptyMap(); fieldOf = emptyMap(); ticked.clear()
        refreshProgressViews()
        val generation = ++mapGeneration
        val cacheKey = com.yeshsade.otzarot.reader.data.ProgressCache.keyOf(snapshot)
        runCatching {
            docThread.execute {
                docIndex = null; docWidgets = emptyMap(); docTicked.clear(); docMarks.clear(); docMapHash = null
                val bytes = runCatching { pdfView.getAttachment(com.yeshsade.otzarot.reader.data.ProgressMap.FILE_NAME) }.getOrNull()
                val parsed = bytes?.let { com.yeshsade.otzarot.reader.data.ProgressMap.parse(String(it, Charsets.UTF_8)) }
                val read = (parsed as? com.yeshsade.otzarot.reader.data.ProgressMap.Result.Ok)?.map
                if (read == null || bytes == null) { noMap(target, generation); return@execute }
                val mapHash = com.yeshsade.otzarot.reader.data.ProgressCache.hash(bytes)
                docMapHash = mapHash
                // the day's name needs only which page is which day, and the screen can open (with "קורא…")
                main.post {
                    if (isDestroyed || doc?.id != target.id || generation != mapGeneration) return@post
                    dayName.map = read
                    parsedMap = read
                    refreshProgressViews()
                }
                // the same file as last time: what is known about its boxes is used as is
                val cached = cacheKey?.let { com.yeshsade.otzarot.reader.data.ProgressCache.load(this, target.id) }
                    ?.takeIf { it.key == cacheKey && it.mapHash == mapHash }
                mapFromCache = cached != null
                if (cached != null) {
                    adoptMap(target, generation, read, cached.widgets, cached.ticked.associateWith { true }, cacheKey, false)
                    return@execute
                }
                // the boxes really in the file, a page per turn of the document's thread, so a tap on a box
                // never waits for the whole booklet; a box missing → no marking screen at all
                val pages = read.checks.flatMap { it.pages }.toSortedSet().toList()
                val found = HashMap<Int, List<com.github.barteksc.pdfviewer.model.CheckField>>()
                fun next(i: Int) {
                    if (generation != mapGeneration || destroyed) return
                    if (i < pages.size) {
                        found[pages[i]] = pdfView.getCheckFields(pages[i])
                        runCatching { docThread.execute { next(i + 1) } }
                        return
                    }
                    finishMap(target, generation, read, found, cacheKey)
                }
                next(0)
            }
        }
    }

    enum class MapState { LOADING, READY, NONE }

    /** Whether this booklet's boxes are known yet (main thread). */
    var mapState = MapState.LOADING
        private set
    private var parsedMap: com.yeshsade.otzarot.reader.data.ProgressMap? = null

    @Volatile private var mapGeneration = 0
    /** Whether the boxes came from what was remembered (for tests). */
    @Volatile var mapFromCache = false
        private set

    // The document thread's own view of the boxes: what the file in the engine holds. A save copies it, so the
    // next opening knows the boxes without reading every page (ProgressCache).
    private var docIndex: Map<Long, String>? = null
    private var docWidgets: Map<String, List<Pair<Int, Int>>> = emptyMap()
    private val docTicked = HashMap<String, Boolean>()
    private val docMarks = ArrayList<Triple<Int, Int, Boolean>>()
    private var docMapHash: String? = null
    private val captureTicked = java.util.concurrent.ConcurrentHashMap<String, com.yeshsade.otzarot.reader.data.ProgressCache.Entry>()

    /** Document thread: a box changed in the engine. */
    private fun docMarked(page: Int, annotation: Int, checked: Boolean) {
        val index = docIndex
        if (index == null) docMarks += Triple(page, annotation, checked)
        else index[key(page, annotation)]?.let { docTicked[it] = checked }
    }

    private fun noMap(target: Doc, generation: Int) {
        main.post {
            if (isDestroyed || doc?.id != target.id || generation != mapGeneration) return@post
            mapState = MapState.NONE
            refreshProgressViews()
            lastProgress?.takeIf { it.waiting }?.let { it.shown.dismiss(); Messages.notice(this, getString(R.string.pr_no_map)) }
        }
    }

    /** Document thread: every page read; checks the map against the real boxes. */
    private fun finishMap(target: Doc, generation: Int, read: com.yeshsade.otzarot.reader.data.ProgressMap,
                          found: Map<Int, List<com.github.barteksc.pdfviewer.model.CheckField>>, cacheKey: String?) {
        val verified = com.yeshsade.otzarot.reader.data.ProgressMap.verify(read) { p -> found[p].orEmpty().mapNotNull { it.name }.toSet() }
        if (verified !is com.yeshsade.otzarot.reader.data.ProgressMap.Result.Ok) {
            android.util.Log.w("OtzarotReader", "progress map refused: $verified")
            noMap(target, generation)
            return
        }
        val names = read.checks.map { it.field }.toSet()
        val byName = HashMap<String, MutableList<Pair<Int, Int>>>()
        val state = HashMap<String, Boolean>()
        for (f in found.values.flatten()) {
            val name = f.name ?: continue
            if (name !in names) continue
            byName.getOrPut(name) { mutableListOf() } += f.page to f.annotationIndex
            state[name] = (state[name] ?: false) || f.checked
        }
        // remembered only when nothing was ticked while reading: then it is exactly what the file holds
        adoptMap(target, generation, read, byName, state, cacheKey, docMarks.isEmpty())
    }

    /** Document thread: the boxes are known; hands them to the screen. */
    private fun adoptMap(target: Doc, generation: Int, read: com.yeshsade.otzarot.reader.data.ProgressMap,
                         byName: Map<String, List<Pair<Int, Int>>>, state: Map<String, Boolean>, cacheKey: String?, remember: Boolean) {
        val index = byName.flatMap { (n, w) -> w.map { key(it.first, it.second) to n } }.toMap()
        docWidgets = byName
        docTicked.clear(); docTicked.putAll(state)
        // boxes ticked while the map was being read, in order (each only sets a value, so order is enough)
        for ((page, annotation, on) in docMarks) index[key(page, annotation)]?.let { docTicked[it] = on }
        docMarks.clear()
        docIndex = index
        val now = HashMap(docTicked)
        if (remember && cacheKey != null) {
            val hash = docMapHash
            if (hash != null) app.io.execute {
                com.yeshsade.otzarot.reader.data.ProgressCache.save(this, target.id,
                    com.yeshsade.otzarot.reader.data.ProgressCache.Entry(cacheKey, hash, byName, now.filterValues { it }.keys))
            }
        }
        main.post {
            if (isDestroyed || doc?.id != target.id || generation != mapGeneration) return@post
            map = read
            parsedMap = read
            widgets = byName
            fieldOf = index
            ticked.clear(); ticked.putAll(now)
            mapState = MapState.READY
            refreshProgressViews()
            lastProgress?.takeIf { it.waiting }?.becameReady()
        }
    }

    private fun key(page: Int, annotation: Int) = (page.toLong() shl 32) or (annotation.toLong() and 0xffffffffL)

    /** The progress screen ("מד התקדמות" / "סימון מהיר"). */
    var lastProgress: ProgressScreen? = null
        private set

    /**
     * Opens the progress screen. The button is there from the first moment (owner, 2026-10-20): while the boxes
     * are still being read the screen opens with "קורא…" and fills in; a file without a map says so.
     */
    private fun openProgress(tab: ProgressScreen.Tab, tries: Int = 0) {
        when (mapState) {
            MapState.NONE -> { Messages.notice(this, getString(R.string.pr_no_map)); return }
            MapState.LOADING -> if (parsedMap == null) {
                // the map itself is read in a moment; try again shortly
                if (tries < 40 && !isDestroyed) main.postDelayed({ openProgress(tab, tries + 1) }, 150)
                return
            }
            MapState.READY -> {}
        }
        val m = map ?: parsedMap ?: return
        chrome.hold()
        lastProgress = ProgressScreen(this, ui, app.settings, m, ticked, waiting = mapState != MapState.READY) { changes ->
            for ((name, on) in changes) widgets[name]?.forEach { (page, annotation) -> mark(page, annotation, on) }
        }.show(tab)
        lastProgress?.shown?.setOnDismissListener { chrome.used(); pdfView.invalidate(); refreshProgressViews() }
    }

    /** Today's part of the booklet that is done, for the small gauge in the toolbar; null while not known. */
    private fun todayFraction(): Float? {
        val m = map ?: return null
        if (mapState != MapState.READY) return null
        val chosen = app.settings.studyTopics.orEmpty()
        val p = com.yeshsade.otzarot.reader.data.Progress(m, chosen) { ticked[it] == true }
        val (y, mo, d) = com.yeshsade.otzarot.reader.data.OpenDay.today()
        val day = com.yeshsade.otzarot.reader.data.OpenDay.choose(p.days.map { it.id }, m.booklet.weekStart, y, mo, d, ticked.values.any { it })
        return (day?.let { p.day(it) } ?: p.week()).fraction
    }

    private fun weekFraction(): Float? {
        val m = map ?: return null
        if (mapState != MapState.READY) return null
        return com.yeshsade.otzarot.reader.data.Progress(m, app.settings.studyTopics.orEmpty()) { ticked[it] == true }.week().fraction
    }

    /** Redraws what shows the progress outside its screen (the toolbar's gauge). Cheap: one small drawing. */
    private fun refreshProgressViews() {
        val gauge = toolbarGauge ?: return
        val f = todayFraction()
        gauge.visibility = if (f != null) View.VISIBLE else View.GONE
        toolbarGaugeIcon?.visibility = if (f != null) View.GONE else View.VISIBLE
        if (f != null) gauge.fraction = f
    }

    private var toolbarGauge: Gauge? = null
    private var toolbarGaugeIcon: View? = null

    private fun showPage(page: Int) {
        val text = getString(R.string.reader_page, page + 1, pageCount)
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
            if (pdfView.findLinkAt(event.x, event.y) != null) {
                // a booklet button: the page it opens starts at the top of the screen, not under the floating
                // top bar (owner, 2026-10-20: the buttons felt like they landed "at the bottom of the page")
                if (FramePolicy.canHide(app.settings.frameMode)) chrome.hide() else chrome.used()
                return false
            }
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
                if (ok) docMarked(page, annotation, checked)
                main.post {
                    if (isDestroyed) return@post
                    pdfView.formFieldApplied(page, annotation, ok)
                    val now = if (ok) checked else !checked
                    fieldOf[key(page, annotation)]?.let { name ->
                        ticked[name] = now
                        // one box shown on two pages (a Gemara amud that runs over two pages): the engine changed
                        // the field, so the other page shows it too
                        if (ok) widgets[name]?.forEach { (p, a) -> if (p != page || a != annotation) pdfView.formFieldApplied(p, a, true) }
                        refreshProgressViews()
                    }
                    done(ok)
                    updateUndoButtons()
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

    /** Applies again the last step that was undone. */
    private fun redoLast() {
        when (val step = undo.redo() ?: return) {
            is UndoStack.Mark -> mark(step.page, step.annotation, !step.before)
            is UndoStack.BookmarkChange -> { bookmarks.revert(UndoStack.BookmarkChange(step.after, step.before)); marked() }
        }
    }

    private fun updateUndoButtons() {
        if (!::undoButton.isInitialized) return
        undoButton.alpha = if (undo.isEmpty) .38f else 1f
        undoButton.isEnabled = !undo.isEmpty
        redoButton.alpha = if (undo.canRedo) 1f else .38f
        redoButton.isEnabled = undo.canRedo
    }

    // ---------- the menu (☰), in the owner's order (drawer board, 2026-10-17) ----------

    /** The open menu and the last go-to-page / file-details windows (for tests). */
    var lastMenu: Drawer.Shown? = null
        private set
    var lastWindow: android.app.Dialog? = null
        private set

    private fun openMenu() {
        chrome.hold()
        val target = doc
        val page = if (pageCount > 0) getString(R.string.reader_page, pdfView.currentPage + 1, pageCount) else null
        val files = Drawer.Item(R.drawable.ic_list, getString(R.string.menu_files), "menu_files") { leave() }
        val goTo = Drawer.Item(R.drawable.ic_goto, getString(R.string.menu_goto), "menu_goto") { goToPage() }
        val marks = Drawer.Item(R.drawable.ic_bookmark, getString(R.string.menu_bookmarks), "menu_bookmarks") { bookmarks.showList() }
        val info = Drawer.Item(R.drawable.ic_info, getString(R.string.menu_info), "menu_info") { fileDetails() }
        val quick = Drawer.Item(R.drawable.ic_check_box, getString(R.string.menu_quick_mark), "menu_quick_mark") { openProgress(ProgressScreen.Tab.MARK) }
        // always there, from the first moment (owner, 2026-10-20); while the boxes are read, without a percentage
        // still to come, in the owner's place: "share the file" (in More)
        lastMenu = Drawer.show(this, ui, target?.name, page,
            top = listOfNotNull(quick.takeIf { target != null }, AppMenu.rotationLock(this), files, goTo.takeIf { pageCount > 0 }),
            more = listOfNotNull(marks.takeIf { pageCount > 0 }, info.takeIf { target != null }),
            pinned = listOf(AppMenu.settings(this)),
            progress = if (target != null) Drawer.ProgressButton(weekFraction()) { openProgress(ProgressScreen.Tab.PROGRESS) } else null)
        lastMenu?.dialog?.setOnDismissListener { chrome.used() }
    }

    private fun goToPage() {
        if (pageCount == 0) return
        val dialog = android.app.Dialog(this)
        val sheet = Messages.sheet(ui)
        val group = Messages.group(ui)
        group.addView(Messages.header(ui, getString(R.string.goto_title), null, null))
        val input = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            hint = getString(R.string.goto_hint, pageCount)
            textSize = 20f
            gravity = Gravity.CENTER
            setTextColor(ui.colors.ink)
            setHintTextColor(ui.colors.muted)
            typeface = ui.font
            background = ui.rounded(ui.colors.canvas, 14f)
            setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(12))
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_GO
            tag = "goto_input"
        }
        group.addView(input, LinearLayout.LayoutParams(-1, -2).apply { setMargins(ui.dp(20), 0, ui.dp(20), ui.dp(16)) })
        fun go() {
            val n = input.text.toString().trim().toIntOrNull() ?: return
            dialog.dismiss()
            pdfView.jumpTo((n - 1).coerceIn(0, pageCount - 1))
        }
        input.setOnEditorActionListener { _, _, _ -> go(); true }
        group.addView(Messages.divider(ui))
        group.addView(Messages.action(ui, getString(R.string.goto_go), ui.colors.accent, bold = true) { go() }.apply { tag = "goto_go" })
        sheet.addView(group)
        sheet.addView(Messages.cancel(ui, getString(R.string.action_cancel)) { dialog.dismiss() }, Messages.gap(ui))
        Messages.show(dialog, sheet, ui, cancelable = true)
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        input.requestFocus()
        lastWindow = dialog
    }

    private fun fileDetails() {
        val target = app.library.get(doc?.id ?: return) ?: doc ?: return
        val dialog = android.app.Dialog(this)
        val sheet = Messages.sheet(ui)
        val group = Messages.group(ui)
        val place = if (target.kind == Doc.Kind.COPY) getString(R.string.info_place_copy)
            else Uri.parse(target.uri).let { it.path?.substringBeforeLast('/') ?: it.toString() }
        val lines = listOfNotNull(
            getString(R.string.info_name, android.text.BidiFormatter.getInstance().unicodeWrap(target.name)),
            if (pageCount > 0) getString(R.string.info_pages, pageCount) else null,
            target.size.takeIf { it > 0 }?.let { getString(R.string.info_size, android.text.format.Formatter.formatShortFileSize(this, it)) },
            getString(R.string.info_place, place),
            target.receivedAt.takeIf { it > 0 }?.let { getString(R.string.info_received,
                android.text.format.DateFormat.getMediumDateFormat(this).format(java.util.Date(it))) },
        )
        group.addView(Messages.header(ui, getString(R.string.info_title), null, lines.joinToString("\n")))
        sheet.addView(group)
        sheet.addView(Messages.cancel(ui, getString(R.string.action_close)) { dialog.dismiss() }.apply { tag = "info_close" }, Messages.gap(ui))
        Messages.show(dialog, sheet, ui, cancelable = true)
        lastWindow = dialog
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
        updateUndoButtons()
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
        val hash = docMapHash
        if (docIndex != null && hash != null)
            captureTicked[file.path] = com.yeshsade.otzarot.reader.data.ProgressCache.Entry("", hash, docWidgets, docTicked.filterValues { it }.keys.toSet())
        return file
    }

    /** Runs on the io thread. Writes the capture into the document's real file and verifies it. */
    private fun write(target: Doc, file: File): Boolean {
        val uri = Uri.parse(target.uri)
        // A document Android can only overwrite by emptying it first: the new version waits, complete, in the
        // app's own storage until it is safely in the document (a cut-off write is finished at the next opening).
        var data = file
        if (uri.scheme != "file") {
            val pending = Files.pendingFile(this, target.id)
            if (!Files.keep(file, pending)) { file.delete(); return false }
            data = pending
        }
        var kept = false
        var written: String? = null
        try {
            // A file found on the device: remember what the original was before the first marks change it,
            // so the same original arriving again (e.g. from WhatsApp) opens this entry.
            val current = app.library.get(target.id) ?: target
            if (current.originIdentity.isEmpty()) {
                Identity.of(this, uri)?.let { origin ->
                    app.library.update(target.id) { it.copy(originIdentity = origin, identity = it.identity.ifEmpty { origin }) }
                }
            }
            val size = data.length()
            written = Files.writeVerified(this, uri, data) ?: return false
            app.library.update(target.id) { it.copy(identity = written, size = size) }
            kept = runCatching { adoptAsSnapshot(target, uri, data, written) }.getOrDefault(false)
            // the saved file is the private copy now: remember its boxes for the next opening
            val boxes = captureTicked.remove(file.path)
            val key = if (kept) com.yeshsade.otzarot.reader.data.ProgressCache.keyOf(snapshotFile(target)) else null
            if (boxes != null && key != null) com.yeshsade.otzarot.reader.data.ProgressCache.save(this, target.id, boxes.copy(key = key))
            else com.yeshsade.otzarot.reader.data.ProgressCache.forget(this, target.id)
            return true
        } finally {
            // a pending copy stays only while its write has not been verified
            if (!kept && (written != null || data === file)) data.delete()
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


    companion object {
        /** Two-finger zoom limit, relative to the whole page (Xodo allows about the same). */
        const val MAX_ZOOM = 20f
        const val EXTRA_DOC = "doc"
        private const val STATE_DOC = "doc"
        private const val SAVE_DELAY_MS = 1500L
        private const val RETRY_DELAY_MS = 300L
        private const val HANDOFF_WAIT_MS = 25_000L

        fun open(activity: android.app.Activity, doc: Doc) {
            activity.startActivity(Intent(activity, ReaderActivity::class.java).putExtra(EXTRA_DOC, doc.id))
        }
    }
}
