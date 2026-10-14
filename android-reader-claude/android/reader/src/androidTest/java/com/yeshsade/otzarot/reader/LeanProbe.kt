package com.yeshsade.otzarot.reader

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import com.github.barteksc.pdfviewer.PDFView
import com.shockwave.pdfium.PdfDocument
import com.shockwave.pdfium.PdfiumCore
import com.yeshsade.otzarot.reader.data.Bookmark
import com.yeshsade.otzarot.reader.ui.LibraryActivity
import com.yeshsade.otzarot.reader.ui.ReaderActivity
import com.yeshsade.otzarot.reader.ui.SettingsActivity
import java.io.File
import java.security.MessageDigest

/**
 * End-to-end check of the new app on a real Android runtime.
 * Run: adb shell am instrument -w com.yeshsade.otzarot.reader.test/com.yeshsade.otzarot.reader.LeanProbe
 */
class LeanProbe : Instrumentation() {
    private var passed = 0
    private val app get() = targetContext.applicationContext as ReaderApp

    private fun record(message: String) {
        passed++
        sendStatus(0, Bundle().apply { putString("stream", "PASS: $message\n") })
    }

    private var mode: String? = null

    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); mode = arguments?.getString("mode"); start() }

    override fun onStart() {
        if (mode == "shots") { shots(); return }
        if (mode == "files") { files(); return }
        if (mode == "bookmarks") { bookmarks(); return }
        if (mode == "bmshots") { bookmarkShots(); return }
        val result = Bundle()
        try {
            waitFor("app start", 60_000) { runCatching { app.settings; app.library; true }.getOrDefault(false) }
            app.settings.whatsAppPromptDismissed = true
            val booklet = ReadOnlyPdfProvider.uri("booklet-1.pdf")
            val original = digest(booklet)
            check(runCatching { targetContext.contentResolver.openOutputStream(booklet, "wt")?.close() }.isFailure)
            record("Shared file is read-only, like a WhatsApp share")

            var reader = openShared(booklet)
            waitLoaded(reader)
            val doc = app.library.all().single()
            check(doc.kind == com.yeshsade.otzarot.reader.data.Doc.Kind.COPY)
            check(Uri.parse(doc.uri) != booklet)
            record("Opening it adds one document to the list, backed by one working copy (${doc.uri})")
            runOnMainSync { snapshot(reader, "lean-reader.png") }

            val first = tapUnchecked(reader, null)
            waitFor("auto-save", 90_000) { checkedIn(doc.uri) == 1 }
            record("A tap is saved into the PDF by itself (no button, no question)")

            tapUnchecked(reader, first)
            val listMonitor = addMonitor(LibraryActivity::class.java.name, null, false)
            runOnMainSync { reader.onBackPressedDispatcher.onBackPressed() }
            waitFor("leaving", 60_000) { reader.isFinishing || reader.isDestroyed }
            waitFor("save on leaving", 90_000) { checkedIn(doc.uri) == 2 }
            val list = waitForMonitorWithTimeout(listMonitor, 60_000) as? LibraryActivity ?: error("list did not show after leaving")
            removeMonitor(listMonitor)
            waitForIdleSync()
            record("Back right after a mark leaves to the list without asking, and the mark is in the file")

            reader = openShared(booklet, from = list)
            waitLoaded(reader)
            check(app.library.all().size == 1) { "list has ${app.library.all().size} entries" }
            check(countChecked(reader) == 2) { "shows ${countChecked(reader)}" }
            record("Opening the same share again shows the 2 marks; still one entry, no new copy")

            tapUnchecked(reader, null)
            waitFor("third mark saved", 90_000) { checkedIn(doc.uri) == 3 }
            undoLast(reader)
            waitFor("undo saved", 90_000) { checkedIn(doc.uri) == 2 }
            record("Undo removes the last mark, and the file reflects the undo")

            tapUnchecked(reader, null)
            val monitor = addMonitor(ReaderActivity::class.java.name, null, false)
            runOnMainSync { reader.recreate() }
            reader = waitForMonitorWithTimeout(monitor, 60_000) as ReaderActivity
            removeMonitor(monitor)
            waitFor("save across rotation", 90_000) { checkedIn(doc.uri) == 3 }
            waitLoaded(reader)
            check(countChecked(reader) == 3)
            record("A mark made right before rotation is saved and shown after it")

            val edition2 = ReadOnlyPdfProvider.uri("booklet-2.pdf")
            reader = openShared(edition2, from = reader)
            waitLoaded(reader)
            check(app.library.all().size == 2)
            check(app.library.all().first().name == "booklet-2") { app.library.all().map { it.name }.toString() }
            reader = openShared(booklet, from = reader)
            waitLoaded(reader)
            check(app.library.all().map { it.name } == listOf("booklet-2", "booklet-1"))
            record("A corrected edition is a separate document; newest received first, and opening the old one does not move it")

            check(digest(booklet).contentEquals(original))
            record("The shared original was never changed")

            runOnMainSync { reader.finish() }
            waitForIdleSync()
            val library = startActivitySync(Intent(targetContext, LibraryActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                .putExtra(LibraryActivity.EXTRA_STAY, true)) as LibraryActivity
            waitFor("covers", 60_000) { File(targetContext.cacheDir, "covers").listFiles()?.size == 2 }
            SystemClock.sleep(1500)
            waitForIdleSync()
            runOnMainSync { snapshot(library, "lean-library.png") }
            record("The document list shows both booklets with small covers")

            val settings = startActivitySync(Intent(targetContext, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as SettingsActivity
            runOnMainSync { check(settings.window.decorView.findViewWithTag<View>("setting_scroll") != null); snapshot(settings, "lean-settings.png") }
            runOnMainSync { settings.finish(); library.finish() }
            waitForIdleSync()
            record("The approved settings screen opens in the new app")

            val monitorReader = addMonitor(ReaderActivity::class.java.name, null, false)
            startActivitySync(Intent(targetContext, LibraryActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            val auto = waitForMonitorWithTimeout(monitorReader, 60_000) as? ReaderActivity ?: error("last-read document did not open")
            removeMonitor(monitorReader)
            waitLoaded(auto)
            runOnMainSync { auto.finish() }
            record("Starting the app opens the document read last")

            result.putString("stream", "Passed $passed checks.\n")
            finish(Activity.RESULT_OK, result)
        } catch (error: Throwable) {
            result.putString("stream", "FAILED after $passed checks: ${error.stackTraceToString()}")
            finish(Activity.RESULT_CANCELED, result)
        }
    }

    /** Opens like a chat app does: from a visible screen when there is one (no new task), else as a new task. */
    /** Visual check: list, reader and settings in Hebrew, by day and by night. Needs documents already in the list. */
    private fun shots() {
        val result = Bundle()
        try {
            waitFor("app start", 60_000) { runCatching { app.settings; true }.getOrDefault(false) }
            for (night in listOf(false, true)) {
                runOnMainSync {
                    app.settings.language = "he"
                    app.settings.night = night
                    androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(androidx.core.os.LocaleListCompat.forLanguageTags("he"))
                    ReaderApp.applyTheme(app.settings)
                }
                val suffix = if (night) "night" else "day"
                val list = startActivitySync(Intent(targetContext, LibraryActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    .putExtra(LibraryActivity.EXTRA_STAY, true)) as LibraryActivity
                SystemClock.sleep(4000); waitForIdleSync()
                runOnMainSync { snapshot(list, "he-library-$suffix.png") }
                val doc = app.library.all().last()
                val monitor = addMonitor(ReaderActivity::class.java.name, null, false)
                runOnMainSync { ReaderActivity.open(list, doc) }
                val reader = waitForMonitorWithTimeout(monitor, 60_000) as ReaderActivity
                removeMonitor(monitor)
                waitLoaded(reader)
                runOnMainSync { snapshot(reader, "he-reader-$suffix.png"); reader.finish(); list.finish() }
                waitForIdleSync()
            }
            result.putString("stream", "Shots done.\n")
            finish(Activity.RESULT_OK, result)
        } catch (e: Throwable) {
            result.putString("stream", "FAILED: ${e.stackTraceToString()}")
            finish(Activity.RESULT_CANCELED, result)
        }
    }

    /**
     * Access to all files (granted beforehand with: appops set <pkg> MANAGE_EXTERNAL_STORAGE allow).
     * Expects test PDFs pushed to WhatsApp's documents folder and to Download.
     */
    private fun files() {
        val result = Bundle()
        try {
            waitFor("app start", 60_000) { runCatching { app.settings; app.library; true }.getOrDefault(false) }
            app.settings.allFilesPromptDismissed = true
            check(com.yeshsade.otzarot.reader.data.AllFiles.granted(targetContext)) { "all-files access not granted" }
            record("Access to all files is on")

            val root = android.os.Environment.getExternalStorageDirectory()
            val wa = File(root, "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Documents/wa-booklet.pdf")
            val dl = File(root, "Download/download-book.pdf")
            check(wa.isFile && dl.isFile) { "test files missing" }
            val waBefore = wa.readBytes()
            val copyDir = File(root, "Documents/" + com.yeshsade.otzarot.reader.data.Files.COPY_FOLDER)
            val copiesBefore = copyDir.listFiles()?.size ?: 0

            val list = startActivitySync(Intent(targetContext, LibraryActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                .putExtra(LibraryActivity.EXTRA_STAY, true)) as LibraryActivity
            waitFor("device scan", 120_000) { app.library.all().map { it.name }.containsAll(listOf("wa-booklet", "download-book")) }
            record("PDFs from the whole device appear by themselves (WhatsApp folder and Download)")

            // A new file arrives while the list is open; pull to refresh finds it.
            val late = File(root, "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Documents/arrived-later.pdf")
            dl.copyTo(late, overwrite = true)
            late.appendText("\n% different edition\n")
            runOnMainSync {
                val refresh = LibraryActivity::class.java.getDeclaredMethod("scanAndRefresh", Boolean::class.javaPrimitiveType).apply { isAccessible = true }
                refresh.invoke(list, true)
            }
            waitFor("refresh", 120_000) { app.library.all().any { it.name == "arrived-later" } }
            record("Pull to refresh finds a file that arrived after the list was opened")

            // WhatsApp hands over a read-only link to a file that is on the device: the same entry opens, no copy.
            val countBefore = app.library.all().size
            val opened = openShared(ReadOnlyPdfProvider.uri("wa-booklet-share.pdf"))
            waitLoaded(opened)
            check(app.library.all().size == countBefore) { "list grew to ${app.library.all().map { it.name }}" }
            check(app.library.byUri(Uri.fromFile(wa).toString()) != null)
            runOnMainSync { opened.finish() }
            waitForIdleSync()
            record("A read-only share of a file that is on the device opens its entry — no duplicate, no copy")

            // Marking the WhatsApp file writes into that file itself — no copy.
            val doc = app.library.all().first { it.name == "wa-booklet" }
            check(Uri.parse(doc.uri).scheme == "file")
            val monitor = addMonitor(ReaderActivity::class.java.name, null, false)
            runOnMainSync { ReaderActivity.open(list, doc) }
            val reader = waitForMonitorWithTimeout(monitor, 60_000) as ReaderActivity
            removeMonitor(monitor)
            waitLoaded(reader)
            tapUnchecked(reader, null)
            waitFor("save in place", 90_000) { checkedIn(doc.uri) == 1 }
            check(!wa.readBytes().contentEquals(waBefore))
            val copies = (copyDir.listFiles()?.size ?: 0) - copiesBefore
            check(copies == 0) { "$copies copies made" }
            record("A mark is saved into WhatsApp's own file — no copy, no double space")

            runOnMainSync { reader.finish(); list.finish() }

            // Sorting
            val sorted = LibraryActivity.Sort.NAME.apply(app.library.all()).map { it.name }
            check(sorted == sorted.sortedWith(java.text.Collator.getInstance())) { sorted.toString() }
            val bySize = LibraryActivity.Sort.SIZE.apply(app.library.all()).map { it.size }
            check(bySize == bySize.sortedDescending())
            record("Sorting by name and by size works")

            result.putString("stream", "Passed $passed checks.\n")
            finish(Activity.RESULT_OK, result)
        } catch (e: Throwable) {
            result.putString("stream", "FAILED after $passed checks: ${e.stackTraceToString()}")
            finish(Activity.RESULT_CANCELED, result)
        }
    }

    /** Ribbon bookmarks: add, save into the PDF, seen by another reader, drag, undo, change, reopen, remove. */
    private fun bookmarks() {
        val result = Bundle()
        try {
            waitFor("app start", 60_000) { runCatching { app.settings; app.library; true }.getOrDefault(false) }
            app.settings.whatsAppPromptDismissed = true
            app.settings.allFilesPromptDismissed = true
            var reader = openShared(ReadOnlyPdfProvider.uri("booklet-1.pdf"))
            waitLoaded(reader)
            val doc = app.library.all().first { it.name == "booklet-1" }
            val controller = bookmarkController(reader)
            waitFor("bookmarks read", 30_000) { var ok = false; runOnMainSync { ok = controller.loaded }; ok }
            val earlier = bookmarksIn(doc.uri).size
            check(controller.all().size == earlier) { "screen ${controller.all().size}, file $earlier" }
            // start clean (ribbons left by an earlier run)
            runOnMainSync { controller.all().forEach { check(controller.change(it, null)) } }
            waitFor("clean start", 90_000) { bookmarksIn(doc.uri).isEmpty() }
            val before = 0

            var added: Bookmark? = null
            runOnMainSync {
                added = ReaderActivity::class.java.getDeclaredMethod("addBookmark").apply { isAccessible = true }
                    .invoke(reader).let { controller.all().lastOrNull() }
            }
            val b = added ?: error("no bookmark added")
            check(controller.all().size == before + 1)
            check(b.color == app.settings.bookmarkColor)
            waitFor("bookmark saved", 90_000) { bookmarksIn(doc.uri).size == before + 1 }
            val saved = bookmarksIn(doc.uri).single { it.id == b.id }
            check(saved.name == b.name) { "${saved.name} vs ${b.name}" }
            record("Tapping Bookmark adds a purple ribbon in the middle of the screen and saves it into the PDF")
            SystemClock.sleep(800); waitForIdleSync()
            runOnMainSync { snapshot(reader, "bm-added.png") }

            // Kept for a check on the computer with another PDF engine (Android's own renderer skips all annotations).
            targetContext.contentResolver.openInputStream(Uri.parse(doc.uri))!!.use { input ->
                File(targetContext.filesDir, "bm-saved.pdf").outputStream().use { input.copyTo(it) }
            }

            // drag with the finger, through the real touch path
            val v = pdfView(reader)
            var from = FloatArray(2)
            runOnMainSync {
                val r = b.frameRect()
                val box = v.frameRectToView(b.page, android.graphics.RectF(r[0], r[1], r[2], r[3]))!!
                val loc = IntArray(2); v.getLocationInWindow(loc)
                from = floatArrayOf(box.centerX() + loc[0], box.centerY() + loc[1])
            }
            val shift = 160f
            touch(reader, MotionEvent.ACTION_DOWN, from[0], from[1])
            for (step in 1..8) { touch(reader, MotionEvent.ACTION_MOVE, from[0], from[1] + shift * step / 8); SystemClock.sleep(16) }
            touch(reader, MotionEvent.ACTION_UP, from[0], from[1] + shift)
            waitForIdleSync()
            val moved = controller.all().single { it.id == b.id }
            check(moved.y > b.y + 10 || moved.page != b.page) { "did not move: $b -> $moved" }
            waitFor("move saved", 90_000) { bookmarksIn(doc.uri).single { it.id == b.id }.let { kotlin.math.abs(it.y - moved.y) < 2 && it.page == moved.page } }
            check(bookmarksIn(doc.uri).size == before + 1)
            record("Dragging the ribbon moves it, and the new place is saved (still one ribbon)")

            undoLast(reader)
            waitFor("undo move saved", 90_000) { bookmarksIn(doc.uri).single { it.id == b.id }.let { kotlin.math.abs(it.y - b.y) < 2 && it.page == b.page } }
            record("Undo puts the ribbon back where it was, also in the file")

            var changed: Bookmark? = null
            runOnMainSync {
                val now = controller.all().single { it.id == b.id }
                val next = now.copy(horizontal = true, size = Bookmark.Size.LARGE, color = Bookmark.withOpacity(Bookmark.PALETTE[3], .5f))
                check(controller.change(now, next)); changed = next
            }
            waitFor("change saved", 90_000) { bookmarksIn(doc.uri).singleOrNull { it.id == b.id }?.name == changed!!.name }
            record("Colour, opacity, size and direction changes are saved into the PDF")

            runOnMainSync { reader.finish() }
            waitFor("closed", 60_000) { reader.isDestroyed }
            reader = openShared(ReadOnlyPdfProvider.uri("booklet-1.pdf"))
            waitLoaded(reader)
            val reopened = bookmarkController(reader)
            waitFor("bookmarks loaded", 30_000) { var n = -1; runOnMainSync { if (reopened.loaded) n = reopened.all().size }; n == before + 1 }
            SystemClock.sleep(800); waitForIdleSync()
            runOnMainSync { snapshot(reader, "bm-reopened.png") }
            record("Opening the document again shows the ribbon where it was saved")

            runOnMainSync { check(reopened.change(reopened.all().single { it.id == b.id }, null)) }
            waitFor("removal saved", 90_000) { bookmarksIn(doc.uri).size == before }
            record("Removing the ribbon removes it from the PDF")

            // A turned page (/Rotate 90): the ribbon must stand upright as the page is seen, in its centre.
            var turned: Bookmark? = null
            runOnMainSync {
                val size = pdfView(reader).getFrameSize(1)!!
                val t = Bookmark(Bookmark.newId(), 1, size[0] / 2, size[1] / 2, false, Bookmark.Size.LARGE, Bookmark.DEFAULT_COLOR)
                check(reopened.change(null, t)); turned = t
            }
            waitFor("turned page saved", 90_000) { bookmarksIn(doc.uri).any { it.id == turned!!.id } }
            targetContext.contentResolver.openInputStream(Uri.parse(doc.uri))!!.use { input ->
                File(targetContext.filesDir, "bm-turned.pdf").outputStream().use { input.copyTo(it) }
            }
            runOnMainSync { check(reopened.change(turned!!, null)) }
            waitFor("turned removal saved", 90_000) { bookmarksIn(doc.uri).isEmpty() }
            record("A ribbon on a turned page is saved (checked by eye on the computer)")
            runOnMainSync { reader.finish() }

            result.putString("stream", "Passed $passed checks.\n")
            finish(Activity.RESULT_OK, result)
        } catch (error: Throwable) {
            result.putString("stream", "FAILED after $passed checks: ${error.stackTraceToString()}")
            finish(Activity.RESULT_CANCELED, result)
        }
    }

    /** Pictures of the ribbon menu and the list, in Hebrew by day. Needs booklet-1 in the list. */
    private fun bookmarkShots() {
        val result = Bundle()
        try {
            waitFor("app start", 60_000) { runCatching { app.settings; true }.getOrDefault(false) }
            runOnMainSync {
                app.settings.language = "he"
                androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(androidx.core.os.LocaleListCompat.forLanguageTags("he"))
            }
            val reader = openShared(ReadOnlyPdfProvider.uri("booklet-1.pdf"))
            waitLoaded(reader)
            val c = bookmarkController(reader)
            waitFor("bookmarks read", 30_000) { var ok = false; runOnMainSync { ok = c.loaded }; ok }
            var b: Bookmark? = null
            runOnMainSync { b = c.add() }
            runOnMainSync { c.change(b!!, b!!.copy(id = b!!.id, x = b!!.x - 60).also { b = it }) }
            runOnMainSync { c.change(null, b!!.copy(id = Bookmark.newId(), page = 1, color = Bookmark.withOpacity(Bookmark.PALETTE[4], .8f))) }
            SystemClock.sleep(800); waitForIdleSync()
            var menu: android.app.Dialog? = null
            runOnMainSync { menu = c.showMenu(b!!) }
            SystemClock.sleep(1000); waitForIdleSync()
            runOnMainSync { snapshotView(menu!!.window!!.decorView, "bm-menu.png"); menu!!.dismiss() }
            var list: android.app.Dialog? = null
            runOnMainSync { list = c.showList() }
            SystemClock.sleep(1000); waitForIdleSync()
            runOnMainSync { snapshotView(list!!.window!!.decorView, "bm-list.png"); list!!.dismiss() }
            runOnMainSync { c.all().forEach { c.change(it, null) }; reader.finish() }
            result.putString("stream", "Shots done.\n")
            finish(Activity.RESULT_OK, result)
        } catch (e: Throwable) {
            result.putString("stream", "FAILED: ${e.stackTraceToString()}")
            finish(Activity.RESULT_CANCELED, result)
        }
    }

    private fun snapshotView(view: View, name: String) {
        val bitmap = Bitmap.createBitmap(view.width.coerceAtLeast(1), view.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.GRAY)
        view.draw(Canvas(bitmap))
        File(targetContext.filesDir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private fun bookmarkController(a: ReaderActivity): com.yeshsade.otzarot.reader.ui.Bookmarks =
        ReaderActivity::class.java.getDeclaredField("bookmarks").apply { isAccessible = true }.get(a) as com.yeshsade.otzarot.reader.ui.Bookmarks

    private fun touch(a: Activity, action: Int, x: Float, y: Float) {
        runOnMainSync {
            val now = SystemClock.uptimeMillis()
            val e = MotionEvent.obtain(now, now, action, x, y, 0)
            a.dispatchTouchEvent(e)
            e.recycle()
        }
    }

    /** The bookmarks stored in a file, read with the engine (frame = page as seen, top-left origin). */
    private fun bookmarksIn(uri: String): List<Bookmark> {
        val bytes = targetContext.contentResolver.openInputStream(Uri.parse(uri))!!.use { it.readBytes() }
        val core = PdfiumCore(targetContext)
        val doc = core.newDocument(bytes)
        val out = mutableListOf<Bookmark>()
        try {
            for (page in 0 until core.getPageCount(doc)) {
                core.openPage(doc, page)
                val height = core.getPageHeightPoint(doc, page)
                for (row in core.getAppBookmarks(doc, page)) {
                    val f = row.split('\t')
                    val l = f[1].toFloat(); val bottom = f[2].toFloat(); val r = f[3].toFloat(); val top = f[4].toFloat()
                    Bookmark.parse(f[0], page, floatArrayOf(l, height - top, r, height - bottom))?.let { out += it }
                }
            }
        } finally { core.closeDocument(doc) }
        return out
    }

    private fun openShared(uri: Uri, from: Activity? = null): ReaderActivity {
        val monitor = addMonitor(ReaderActivity::class.java.name, null, false)
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/pdf")
            .setClassName(targetContext, ReaderActivity::class.java.name)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (from != null) runOnMainSync { from.startActivity(intent) }
        else targetContext.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val activity = waitForMonitorWithTimeout(monitor, 60_000) as? ReaderActivity ?: error("reader did not open")
        removeMonitor(monitor)
        return activity
    }

    private fun pdfView(a: ReaderActivity): PDFView =
        ReaderActivity::class.java.getDeclaredField("pdfView").apply { isAccessible = true }.get(a) as PDFView

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else runOnMainSync(block)
    }

    private fun waitLoaded(a: ReaderActivity) {
        waitFor("document load", 120_000) {
            var ok = false
            onMain { ok = !a.isDestroyed && pdfView(a).getPageCount() > 0 }
            ok
        }
        SystemClock.sleep(1500)
        waitForIdleSync()
    }

    private fun fields(a: ReaderActivity): List<Pair<PDFView.FormField, FloatArray>> {
        val out = mutableListOf<Pair<PDFView.FormField, FloatArray>>()
        runOnMainSync {
            val v = pdfView(a)
            val seen = mutableSetOf<String>()
            var y = 4f
            while (y < v.height) {
                var x = 4f
                while (x < v.width) {
                    val f = v.findFormFieldAt(x, y)
                    if (f != null && f.type == PdfDocument.FormField.TYPE_CHECKBOX && seen.add("${f.pageIndex}:${f.annotationIndex}"))
                        out += f to floatArrayOf(x + 3, y + 3)
                    x += 6f
                }
                y += 6f
            }
        }
        return out
    }

    private fun tapUnchecked(a: ReaderActivity, exclude: String?): String {
        val (f, p) = fields(a).firstOrNull { (f, _) -> !f.checked && "${f.pageIndex}:${f.annotationIndex}" != exclude }
            ?: error("no unchecked checkbox visible")
        runOnMainSync {
            val m = ReaderActivity::class.java.getDeclaredMethod("tap", MotionEvent::class.java).apply { isAccessible = true }
            val now = SystemClock.uptimeMillis()
            val e = MotionEvent.obtain(now, now, MotionEvent.ACTION_UP, p[0], p[1], 0)
            check(m.invoke(a, e) as Boolean)
            e.recycle()
        }
        waitForIdleSync()
        return "${f.pageIndex}:${f.annotationIndex}"
    }

    private fun undoLast(a: ReaderActivity) = runOnMainSync {
        ReaderActivity::class.java.getDeclaredMethod("undoLast").apply { isAccessible = true }.invoke(a)
    }

    private fun countChecked(a: ReaderActivity) = fields(a).count { it.first.checked }

    private fun checkedIn(uri: String): Int {
        val bytes = targetContext.contentResolver.openInputStream(Uri.parse(uri))!!.use { it.readBytes() }
        val core = PdfiumCore(targetContext)
        val doc = core.newDocument(bytes)
        var n = 0
        try {
            for (page in 0 until core.getPageCount(doc)) {
                core.openPage(doc, page)
                val r = core.getFormFieldRects(doc, page)
                for (i in r.indices step 4) {
                    val f = core.getFormFieldAtPoint(doc, page, (r[i] + r[i + 2]) / 2, (r[i + 1] + r[i + 3]) / 2, 0f) ?: continue
                    if (f.isChecked) n++
                }
            }
        } finally { core.closeDocument(doc) }
        return n
    }

    private fun digest(uri: Uri): ByteArray = MessageDigest.getInstance("SHA-256").digest(
        targetContext.contentResolver.openInputStream(uri)!!.use { it.readBytes() })

    private fun snapshot(a: Activity, name: String) {
        val decor = a.window.decorView
        val bitmap = Bitmap.createBitmap(decor.width.coerceAtLeast(1), decor.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        decor.draw(Canvas(bitmap))
        File(targetContext.filesDir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private fun waitFor(what: String, timeout: Long, condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + timeout
        while (SystemClock.uptimeMillis() < end) {
            if (runCatching(condition).getOrDefault(false)) return
            SystemClock.sleep(250)
        }
        error("Timed out waiting for $what")
    }
}
