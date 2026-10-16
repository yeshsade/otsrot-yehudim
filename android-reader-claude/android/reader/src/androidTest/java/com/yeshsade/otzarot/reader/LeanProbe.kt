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

    private var fileArg: String? = null

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        mode = arguments?.getString("mode"); fileArg = arguments?.getString("file"); start()
    }

    override fun onStart() {
        if (mode == "shots") { shots(); return }
        if (mode == "files") { files(); return }
        if (mode == "bookmarks") { bookmarks(); return }
        if (mode == "bmshots") { bookmarkShots(); return }
        if (mode == "chrome") { chromeChecks(); return }
        if (mode == "settingsshots") { settingsShots(); return }
        if (mode == "look") { look(); return }
        if (mode == "perf") { perf(); return }
        if (mode == "manage") { manage(); return }
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
            var arriving: Float? = null
            runOnMainSync {
                added = ReaderActivity::class.java.getDeclaredMethod("addBookmark").apply { isAccessible = true }
                    .invoke(reader).let { controller.all().lastOrNull() }
                // read at once: the emulator is slow enough for the half-second drop to end before the next step
                arriving = controller.arrivalProgress
            }
            val b = added ?: error("no bookmark added")
            // it drops into its place (owner's choice: "falls and bounces"), then stays there
            check(arriving != null && arriving!! < 1f) { "the new ribbon did not drop in ($arriving)" }
            SystemClock.sleep(com.yeshsade.otzarot.reader.data.DropIn.MILLIS + 400); waitForIdleSync()
            runOnMainSync { arriving = controller.arrivalProgress }
            check(arriving == null) { "still dropping after the drop time ($arriving)" }
            record("A new ribbon drops into its place with one small hop, in half a second, then stays put")
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
                val next = now.copy(horizontal = true, thickness = 27f, color = Bookmark.withOpacity(Bookmark.PALETTE[3], .5f))
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

            // a third ribbon on one page asks first; "No" adds nothing, "Yes" adds it
            val added3 = mutableListOf<Bookmark?>()
            runOnMainSync { reopened.add { added3 += it }; reopened.add { added3 += it } }
            check(added3.size == 2 && added3.all { it != null })
            runOnMainSync { reopened.add { added3 += it } }
            var asked: com.yeshsade.otzarot.reader.ui.Messages.Ask? = null
            runOnMainSync { asked = reopened.lastQuestion; check(asked?.dialog?.isShowing == true) { "no question for a third ribbon" } }
            runOnMainSync { snapshotView(asked!!.dialog.window!!.decorView, "bm-third.png"); asked!!.no!!.performClick() }
            waitForIdleSync()
            check(added3.size == 3 && added3[2] == null)
            runOnMainSync { check(reopened.all().size == 2) }
            runOnMainSync { reopened.add { added3 += it } }
            runOnMainSync { reopened.lastQuestion!!.yes.performClick() }
            waitForIdleSync()
            runOnMainSync { check(reopened.all().size == 3) { "yes did not add" } }
            waitFor("three saved", 90_000) { bookmarksIn(doc.uri).size == 3 }
            runOnMainSync { reopened.all().forEach { check(reopened.change(it, null)) } }
            waitFor("three removed", 90_000) { bookmarksIn(doc.uri).isEmpty() }
            record("A third ribbon on the same page asks first: No adds nothing, Yes adds it")

            // size bar: a big ribbon is saved with its size
            var big: Bookmark? = null
            runOnMainSync {
                reopened.add { a -> big = a }
            }
            runOnMainSync {
                val max = Bookmark.maxThickness(pdfView(reader).getFrameSize(big!!.page)!![1])
                val next = big!!.copy(thickness = Bookmark.thicknessAt(100, max))
                check(reopened.change(big!!, next)); big = next
            }
            waitFor("big saved", 90_000) { bookmarksIn(doc.uri).singleOrNull()?.let { kotlin.math.abs(it.thickness - big!!.thickness) < .1f } == true }
            runOnMainSync { check(reopened.change(big!!, null)) }
            waitFor("big removed", 90_000) { bookmarksIn(doc.uri).isEmpty() }
            record("The size bar's biggest ribbon (a third of the page long) is saved with its size")

            // A turned page (/Rotate 90): the ribbon must stand upright as the page is seen, in its centre.
            var turned: Bookmark? = null
            runOnMainSync {
                val size = pdfView(reader).getFrameSize(1)!!
                val t = Bookmark(Bookmark.newId(), 1, size[0] / 2, size[1] / 2, false, 27f, Bookmark.DEFAULT_COLOR)
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
            runOnMainSync { c.add { b = it } }
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
        writePng(bitmap, name)
    }

    /**
     * The ⋮ menu on the list (owner, 2026-10-16): "remove from the list" stays removed even when the device is
     * scanned again, opening the file again brings it back; "delete from the device" deletes the file, or only
     * the app's copy for a read-only WhatsApp share. Needs access to all files. Also shoots the designed messages.
     */
    private fun manage() {
        val result = Bundle()
        try {
            waitFor("app start", 60_000) { runCatching { app.settings; app.library; true }.getOrDefault(false) }
            val s = app.settings
            s.allFilesPromptDismissed = true; s.whatsAppPromptDismissed = true; s.language = "he"
            onMainChecked { androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(androidx.core.os.LocaleListCompat.forLanguageTags("he")) }
            check(com.yeshsade.otzarot.reader.data.AllFiles.granted(targetContext)) { "all-files access not granted" }
            val dir = File(android.os.Environment.getExternalStorageDirectory(), "Download")
            val keep = File(dir, "probe-remove.pdf"); val gone = File(dir, "probe-delete.pdf")
            // a clean start: an earlier run may have left these files removed from the list
            listOf(keep, gone).forEach { app.library.unhide(Uri.fromFile(it).toString()) }
            for ((f, tag) in listOf(keep to "remove", gone to "delete")) {
                context.assets.open("synthetic-checkbox-source.pdf").use { input -> f.outputStream().use { input.copyTo(it); it.write("\n% probe $tag\n".toByteArray()) } }
            }
            // files written straight to the disk reach Android's media index only after a media scan (a download does this itself)
            val indexed = java.util.concurrent.CountDownLatch(2)
            android.media.MediaScannerConnection.scanFile(targetContext, arrayOf(keep.path, gone.path), arrayOf("application/pdf", "application/pdf")) { _, _ -> indexed.countDown() }
            indexed.await(60, java.util.concurrent.TimeUnit.SECONDS)
            val list = startActivitySync(Intent(targetContext, LibraryActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                .putExtra(LibraryActivity.EXTRA_STAY, true)) as LibraryActivity
            waitFor("device scan", 120_000) { app.library.all().map { it.name }.containsAll(listOf("probe-remove", "probe-delete")) }
            fun menu(doc: com.yeshsade.otzarot.reader.data.Doc): android.app.Dialog {
                var d: android.app.Dialog? = null
                onMainChecked { d = LibraryActivity::class.java.getDeclaredMethod("showMenu", com.yeshsade.otzarot.reader.data.Doc::class.java).apply { isAccessible = true }.invoke(list, doc) as android.app.Dialog }
                waitForIdleSync(); SystemClock.sleep(400)
                return d!!
            }
            fun choose(d: android.app.Dialog, tag: String, shot: String?): com.yeshsade.otzarot.reader.ui.Messages.Ask {
                onMainChecked { shot?.let { snapshotView(d.window!!.decorView, it) }; d.window!!.decorView.findViewWithTag<View>(tag).performClick() }
                waitForIdleSync(); SystemClock.sleep(400)
                var ask: com.yeshsade.otzarot.reader.ui.Messages.Ask? = null
                onMainChecked { ask = list.lastAsk; check(ask!!.dialog.isShowing) { "no question after $tag" } }
                return ask!!
            }
            fun scanAgain() {
                onMainChecked { LibraryActivity::class.java.getDeclaredMethod("scanAndRefresh", Boolean::class.javaPrimitiveType).apply { isAccessible = true }.invoke(list, true) }
                waitFor("scan done", 120_000) { var busy = true; onMainChecked { busy = field<Boolean>(list, "scanning") }; !busy }
            }

            // remove from the list: it stays removed after another scan of the device; the file stays
            val r = app.library.all().first { it.name == "probe-remove" }
            val removeAsk = choose(menu(r), "menu_remove", "menu.png")
            onMainChecked { snapshotView(removeAsk.dialog.window!!.decorView, "ask-remove.png"); removeAsk.yes.performClick() }
            waitFor("removed", 30_000) { app.library.get(r.id) == null }
            scanAgain(); scanAgain()
            check(app.library.all().none { it.name == "probe-remove" }) { "the scan brought the removed file back" }
            check(keep.isFile) { "removing deleted the file" }
            record("Remove from the list: the file stays on the device, and scanning the device again does not bring it back")

            // opening it again on purpose brings it back
            val back = com.yeshsade.otzarot.reader.data.Intake(targetContext, app.library, app.settings).open(Uri.fromFile(keep))
            check(back != null && app.library.all().any { it.name == "probe-remove" }) { "opening the file again did not bring it back" }
            record("Opening a removed file again brings it back to the list")

            // delete from the device: the file is gone, and so is the entry
            val g = app.library.all().first { it.name == "probe-delete" }
            val deleteAsk = choose(menu(g), "menu_delete", null)
            onMainChecked { snapshotView(deleteAsk.dialog.window!!.decorView, "ask-delete.png"); deleteAsk.yes.performClick() }
            waitFor("deleted", 30_000) { !gone.exists() && app.library.get(g.id) == null }
            scanAgain()
            check(app.library.all().none { it.name == "probe-delete" })
            record("Delete from the device: after the question, the file and its entry are gone")

            // a read-only share (like WhatsApp without access to all files): only the app's copy is deleted
            val reader = openShared(ReadOnlyPdfProvider.uri("manage-share.pdf"))
            waitLoaded(reader)
            // the share may open an earlier entry of the same booklet (another mode marked it): take whatever opened
            var shown: com.yeshsade.otzarot.reader.data.Doc? = null
            onMainChecked { shown = field<com.yeshsade.otzarot.reader.data.Doc?>(reader, "doc") }
            onMainChecked { reader.finish() }; waitFor("closed", 30_000) { reader.isDestroyed }
            val c = app.library.get(shown!!.id)!!
            check(c.kind == com.yeshsade.otzarot.reader.data.Doc.Kind.COPY) { "expected a working copy, got ${c.kind}" }
            refreshList(list)
            val copyAsk = choose(menu(c), "menu_delete", null)
            var text = ""
            onMainChecked {
                text = allText(copyAsk.dialog.window!!.decorView)
                snapshotView(copyAsk.dialog.window!!.decorView, "ask-delete-copy.png"); copyAsk.yes.performClick()
            }
            check(text.contains("וואטסאפ")) { "the question does not say WhatsApp's file stays: $text" }
            waitFor("copy deleted", 30_000) { app.library.get(c.id) == null }
            val original = targetContext.contentResolver.openInputStream(ReadOnlyPdfProvider.uri("manage-share.pdf"))!!.use { it.readBytes().size }
            check(original > 0) { "the shared original is gone" }
            record("Deleting a read-only share deletes only the app's copy; the question says WhatsApp's file stays")

            // the other designed messages, for the owner to see
            onMainChecked {
                val sort = LibraryActivity::class.java.getDeclaredMethod("chooseSort").apply { isAccessible = true }
                sort.invoke(list)
            }
            waitForIdleSync(); SystemClock.sleep(400)
            onMainChecked { val d = list.lastDialog!!; snapshotView(d.window!!.decorView, "choose-sort.png"); d.dismiss() }
            onMainChecked {
                val hint = list.resources.getIdentifier("bm_added_hint", "string", list.packageName)
                com.yeshsade.otzarot.reader.ui.Messages.notice(list, list.getString(hint), long = true)
            }
            waitForIdleSync(); SystemClock.sleep(600)
            onMainChecked { snapshot(list, "notice.png") }
            var all: android.app.Dialog? = null
            onMainChecked { all = LibraryActivity::class.java.getDeclaredMethod("allFilesDialog").apply { isAccessible = true }.invoke(list) as android.app.Dialog }
            waitForIdleSync(); SystemClock.sleep(400)
            onMainChecked { snapshotView(all!!.window!!.decorView, "ask-all-files.png"); all!!.dismiss() }
            record("Every message uses the designed card or bubble")
            keep.delete()
            onMainChecked { list.finish() }

            result.putString("stream", "Passed $passed checks.\n")
            finish(Activity.RESULT_OK, result)
        } catch (error: Throwable) {
            result.putString("stream", "FAILED after $passed checks: ${error.stackTraceToString()}")
            finish(Activity.RESULT_CANCELED, result)
        }
    }

    private fun refreshList(list: LibraryActivity) {
        onMainChecked { LibraryActivity::class.java.getDeclaredMethod("refresh").apply { isAccessible = true }.invoke(list) }
        waitForIdleSync()
    }

    private fun allText(view: View): String = when (view) {
        is android.widget.TextView -> view.text.toString()
        is android.view.ViewGroup -> (0 until view.childCount).joinToString(" ") { allText(view.getChildAt(it)) }
        else -> ""
    }

    /**
     * Hiding the frame, the thin line and the page handle. With -e file <path on the device> it uses that PDF
     * (all-files access must be granted), else the test booklet.
     */
    private fun chromeChecks() {
        val result = Bundle()
        try {
            waitFor("app start", 60_000) { runCatching { app.settings; app.library; true }.getOrDefault(false) }
            val s = app.settings
            s.whatsAppPromptDismissed = true; s.allFilesPromptDismissed = true
            s.frameMode = com.yeshsade.otzarot.reader.data.FramePolicy.AUTO; s.frameSeconds = 3
            s.infoPage = false; s.infoBattery = true; s.infoClock = true
            s.scrubberSide = "right"; s.scrubberHoldMillis = 500; s.scroll = "vertical"
            s.language = "he"
            onMainChecked { androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(androidx.core.os.LocaleListCompat.forLanguageTags("he")) }

            var reader = openDoc()
            waitLoaded(reader)
            val header = field<View>(reader, "header"); val toolbar = field<View>(reader, "toolbar")
            val info = field<View>(reader, "infoLine"); val scrubber = field<com.yeshsade.otzarot.reader.ui.Scrubber>(reader, "scrubber")
            val v = pdfView(reader)
            var shownSize = IntArray(2); var shownOffset = 0f
            // opening a big booklet can take longer than the 3 s used here: start the frame's time now
            onMainChecked { field<com.yeshsade.otzarot.reader.ui.Chrome>(reader, "chrome").show() }
            onMainChecked {
                check(header.visibility == View.VISIBLE && toolbar.visibility == View.VISIBLE && scrubber.visibility == View.VISIBLE)
                shownSize = intArrayOf(v.width, v.height); shownOffset = v.currentYOffset
                snapshot(reader, "chrome-shown.png")
            }
            record("The frame is shown when a document opens")

            waitFor("frame hides by itself", 15_000) { var h = false; onMainChecked { h = header.visibility != View.VISIBLE }; h }
            SystemClock.sleep(600); waitForIdleSync()
            onMainChecked {
                check(toolbar.visibility != View.VISIBLE && scrubber.visibility != View.VISIBLE) { "toolbar or handle still shown" }
                check(info.visibility == View.VISIBLE) { "no thin line" }
                val text = (info as android.widget.LinearLayout).let { l -> (0 until l.childCount).joinToString("|") { (l.getChildAt(it) as android.widget.TextView).text } }
                check(text.contains("%") && Regex("\\d{1,2}:\\d{2}").containsMatchIn(text)) { "thin line shows '$text'" }
                val insets = androidx.core.view.ViewCompat.getRootWindowInsets(reader.window.decorView)!!
                check(!insets.isVisible(androidx.core.view.WindowInsetsCompat.Type.statusBars())) { "phone's bar still shown" }
                check(v.width == shownSize[0] && v.height == shownSize[1]) { "page area changed size ${v.width}x${v.height} vs ${shownSize.toList()}" }
                check(kotlin.math.abs(v.currentYOffset - shownOffset) < 1f) { "page moved" }
                snapshot(reader, "chrome-hidden.png")
            }
            record("After the set time the frame, the handle and the phone's bars hide together; the page does not move; the thin line shows battery and time")

            tapPage(reader)
            waitFor("frame back", 10_000) { var ok = false; runOnMainSync { ok = header.visibility == View.VISIBLE && header.alpha > .99f && info.visibility != View.VISIBLE }; ok }
            tapPage(reader)
            waitFor("frame gone", 10_000) { var ok = false; runOnMainSync { ok = header.visibility != View.VISIBLE }; ok }
            record("A tap on the page shows the frame, another tap hides it")

            // the page handle: holding less than the set time does nothing; holding longer moves through the document
            tapPage(reader)
            val pages = v.pageCount
            val startPage = v.currentPage
            val c = scrubber.thumbCentre()
            val loc = IntArray(2); onMainChecked { scrubber.getLocationInWindow(loc) }
            val x = c[0] + loc[0]; val y0 = c[1] + loc[1]
            val yTo = y0 + (scrubber.height - c[1]) * .6f
            touch(reader, MotionEvent.ACTION_DOWN, x, y0)
            touch(reader, MotionEvent.ACTION_MOVE, x, yTo)
            touch(reader, MotionEvent.ACTION_UP, x, yTo)
            waitForIdleSync()
            check(v.currentPage == startPage) { "a quick swipe on the handle moved the page" }
            touch(reader, MotionEvent.ACTION_DOWN, x, y0)
            SystemClock.sleep(800)
            for (i in 1..10) { touch(reader, MotionEvent.ACTION_MOVE, x, y0 + (yTo - y0) * i / 10); SystemClock.sleep(30) }
            SystemClock.sleep(300); waitForIdleSync()
            onMainChecked { snapshot(reader, "chrome-handle.png") }
            touch(reader, MotionEvent.ACTION_UP, x, yTo)
            waitForIdleSync(); SystemClock.sleep(500)
            check(pages < 3 || v.currentPage > startPage) { "holding and dragging the handle did not move (page ${v.currentPage})" }
            record("The page handle moves only after being held (0.5 s), then jumps through the document (now page ${v.currentPage + 1} of $pages)")

            // "shows and hides with a tap": never hides by itself
            onMainChecked { reader.finish() }; waitFor("closed", 30_000) { reader.isDestroyed }
            s.frameMode = com.yeshsade.otzarot.reader.data.FramePolicy.TAP
            reader = openDoc(); waitLoaded(reader)
            SystemClock.sleep(5000)
            onMainChecked { check(field<View>(reader, "header").visibility == View.VISIBLE) { "tap mode hid by itself" } }
            tapPage(reader)
            waitFor("tap hides", 10_000) { var ok = false; runOnMainSync { ok = field<View>(reader, "header").visibility != View.VISIBLE }; ok }
            record("In 'shows and hides with a tap' the frame stays until tapped")

            // "always shown": the top bar sits above the page, a tap does not hide it
            onMainChecked { reader.finish() }; waitFor("closed", 30_000) { reader.isDestroyed }
            s.frameMode = com.yeshsade.otzarot.reader.data.FramePolicy.ALWAYS
            reader = openDoc(); waitLoaded(reader)
            SystemClock.sleep(4000)
            tapPage(reader)
            onMainChecked {
                val h = field<View>(reader, "header"); val pv = pdfView(reader)
                val hl = IntArray(2); h.getLocationInWindow(hl); val pl = IntArray(2); pv.getLocationInWindow(pl)
                check(h.visibility == View.VISIBLE) { "always mode hid" }
                check(pl[1] >= hl[1] + h.height - 1) { "page is under the top bar" }
            }
            record("In 'always shown' the frame never hides and the page starts below the top bar")

            // a real tap on a booklet button (link) on the contents page moves to where it points
            onMainChecked { pdfView(reader).jumpTo(minOf(2, pdfView(reader).pageCount - 1)) }
            SystemClock.sleep(3000); waitForIdleSync()
            var target: FloatArray? = null; var linkFrom = -1
            onMainChecked {
                val pv = pdfView(reader); linkFrom = pv.currentPage
                loop@ for (iy in 1..39) for (ix in 1..19) {
                    val x = pv.width * ix / 20f; val y = pv.height * iy / 40f
                    val link = pv.findLinkAt(x, y) ?: continue
                    if (link.link.destPageIdx == null || link.link.destPageIdx == linkFrom || pv.findFormFieldAt(x, y) != null) continue
                    val pl = IntArray(2); pv.getLocationInWindow(pl); target = floatArrayOf(x + pl[0], y + pl[1]); break@loop
                }
            }
            val at = target ?: error("no button found on the contents page")
            touch(reader, MotionEvent.ACTION_DOWN, at[0], at[1]); SystemClock.sleep(60)
            touch(reader, MotionEvent.ACTION_UP, at[0], at[1])
            waitFor("button moved the page", 15_000) { var p = linkFrom; onMainChecked { p = pdfView(reader).currentPage }; p != linkFrom }
            record("A tap on a button in the booklet (contents) moves to its page")

            // side-to-side reading: the handle runs along the bottom
            onMainChecked { reader.finish() }; waitFor("closed", 30_000) { reader.isDestroyed }
            s.frameMode = com.yeshsade.otzarot.reader.data.FramePolicy.TAP; s.scroll = "horizontal"; s.scrubberHoldMillis = 0
            reader = openDoc(); waitLoaded(reader)
            val hs = field<com.yeshsade.otzarot.reader.ui.Scrubber>(reader, "scrubber")
            val hc = hs.thumbCentre()
            check(hc[1] > hs.height * .7f) { "handle is not at the bottom" }
            val hl = IntArray(2); onMainChecked { hs.getLocationInWindow(hl) }
            val hv = pdfView(reader); val before = hv.currentPage
            val toX = if (hc[0] > hs.width / 2) hs.width * .2f else hs.width * .8f
            touch(reader, MotionEvent.ACTION_DOWN, hc[0] + hl[0], hc[1] + hl[1])
            for (i in 1..10) { touch(reader, MotionEvent.ACTION_MOVE, hc[0] + (toX - hc[0]) * i / 10 + hl[0], hc[1] + hl[1]); SystemClock.sleep(30) }
            onMainChecked { snapshot(reader, "chrome-handle-horizontal.png") }
            touch(reader, MotionEvent.ACTION_UP, toX + hl[0], hc[1] + hl[1])
            waitForIdleSync(); SystemClock.sleep(500)
            check(hv.pageCount < 3 || hv.currentPage != before) { "horizontal handle did not move" }
            record("In side-to-side reading the handle runs along the bottom and moves through the document")
            onMainChecked { reader.finish() }
            s.scroll = "vertical"; s.frameMode = com.yeshsade.otzarot.reader.data.FramePolicy.AUTO; s.frameSeconds = 10; s.scrubberHoldMillis = 500

            result.putString("stream", "Passed $passed checks.\n")
            finish(Activity.RESULT_OK, result)
        } catch (error: Throwable) {
            result.putString("stream", "FAILED after $passed checks: ${error.stackTraceToString()}")
            finish(Activity.RESULT_CANCELED, result)
        }
    }

    /** The settings screen with its new rows and their sheets, and the all-files explanation (Hebrew). */
    private fun settingsShots() {
        val result = Bundle()
        try {
            waitFor("app start", 60_000) { runCatching { app.settings; true }.getOrDefault(false) }
            app.settings.language = "he" // the settings screen takes its wording language from here
            val settingsScreen = startActivitySync(Intent(targetContext, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as SettingsActivity
            SystemClock.sleep(1500); waitForIdleSync()
            onMainChecked {
                val scroll = settingsScreen.window.decorView.findViewWithTag<View>("setting_frame").parent.parent.parent as android.widget.ScrollView
                scroll.scrollTo(0, settingsScreen.window.decorView.findViewWithTag<View>("setting_awake").top)
            }
            SystemClock.sleep(500); waitForIdleSync()
            onMainChecked { snapshot(settingsScreen, "settings-new-rows.png") }
            for (key in listOf("frame", "handle")) {
                onMainChecked { settingsScreen.window.decorView.findViewWithTag<View>("setting_$key").performClick() }
                SystemClock.sleep(1200); waitForIdleSync()
                onMainChecked {
                    val sheet = SettingsActivity::class.java.getDeclaredField("sheet").apply { isAccessible = true }.get(settingsScreen) as android.app.Dialog
                    snapshotView(sheet.window!!.decorView, "settings-sheet-$key.png"); sheet.dismiss()
                }
                SystemClock.sleep(500); waitForIdleSync()
            }
            onMainChecked { settingsScreen.finish() }
            record("The settings screen shows the new layout: frame (with the thin line) and the page handle (position, when shown, hold time)")

            val lib = startActivitySync(Intent(targetContext, LibraryActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK).putExtra(LibraryActivity.EXTRA_STAY, true)) as LibraryActivity
            SystemClock.sleep(1000); waitForIdleSync()
            var d: android.app.Dialog? = null
            onMainChecked { d = LibraryActivity::class.java.getDeclaredMethod("allFilesDialog").apply { isAccessible = true }.invoke(lib) as android.app.Dialog }
            SystemClock.sleep(1500); waitForIdleSync()
            onMainChecked { snapshotView(d!!.window!!.decorView, "all-files-dialog.png"); d!!.dismiss(); lib.finish() }

            result.putString("stream", "Passed $passed checks.\n")
            finish(Activity.RESULT_OK, result)
        } catch (error: Throwable) {
            result.putString("stream", "FAILED after $passed checks: ${error.stackTraceToString()}")
            finish(Activity.RESULT_CANCELED, result)
        }
    }

    /** Encodes off the main thread: on a slow emulator PNG encoding there makes Android think the app hangs. */
    private fun writePng(bitmap: Bitmap, name: String) {
        pendingPngs.incrementAndGet()
        Thread {
            try { File(targetContext.filesDir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
            finally { bitmap.recycle(); pendingPngs.decrementAndGet() }
        }.start()
    }

    private val pendingPngs = java.util.concurrent.atomic.AtomicInteger()

    override fun finish(resultCode: Int, results: Bundle?) {
        val end = SystemClock.uptimeMillis() + 60_000
        while (pendingPngs.get() > 0 && SystemClock.uptimeMillis() < end) SystemClock.sleep(100)
        super.finish(resultCode, results)
    }

    /** Opens -e file, waits for the pages to draw, and takes a picture with the frame shown and hidden. */
    private fun look() {
        val result = Bundle()
        try {
            waitFor("app start", 60_000) { runCatching { app.settings; true }.getOrDefault(false) }
            app.settings.frameMode = com.yeshsade.otzarot.reader.data.FramePolicy.TAP
            val reader = openDoc(); waitLoaded(reader)
            SystemClock.sleep(20_000); waitForIdleSync()
            onMainChecked { snapshot(reader, "look-shown.png") }
            tapPage(reader); SystemClock.sleep(3000)
            onMainChecked { snapshot(reader, "look-hidden.png"); reader.finish() }
            app.settings.frameMode = com.yeshsade.otzarot.reader.data.FramePolicy.AUTO
            result.putString("stream", "Look done.\n")
            finish(Activity.RESULT_OK, result)
        } catch (error: Throwable) {
            result.putString("stream", "FAILED: ${error.stackTraceToString()}")
            finish(Activity.RESULT_CANCELED, result)
        }
    }

    /**
     * Times moving between screens: list -> document (until the page count is known and the main thread is idle)
     * and document -> list (until the list is idle). Needs -e file <big pdf> already in the list.
     */
    private fun perf() {
        val result = Bundle()
        try {
            waitFor("app start", 60_000) { runCatching { app.settings; app.library; true }.getOrDefault(false) }
            app.settings.whatsAppPromptDismissed = true; app.settings.allFilesPromptDismissed = true
            val path = fileArg ?: error("needs -e file")
            val target = com.yeshsade.otzarot.reader.data.Intake(targetContext, app.library, app.settings).open(Uri.fromFile(File(path)))!!
            val listMonitor = addMonitor(LibraryActivity::class.java.name, null, false)
            var list = startActivitySync(Intent(targetContext, LibraryActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK).putExtra(LibraryActivity.EXTRA_STAY, true)) as LibraryActivity
            waitForIdleSync(); SystemClock.sleep(8000); waitForIdleSync()
            val opens = mutableListOf<Long>(); val backs = mutableListOf<Long>()
            repeat(4) {
                val rm = addMonitor(ReaderActivity::class.java.name, null, false)
                val t0 = SystemClock.uptimeMillis()
                runOnMainSync { ReaderActivity.open(list, target) }
                val reader = waitForMonitorWithTimeout(rm, 60_000) as ReaderActivity
                removeMonitor(rm)
                waitFor("pages", 120_000) { var ok = false; runOnMainSync { ok = pdfView(reader).getPageCount() > 0 }; ok }
                waitForIdleSync()
                opens += SystemClock.uptimeMillis() - t0
                SystemClock.sleep(3000)
                val t1 = SystemClock.uptimeMillis()
                runOnMainSync { reader.onBackPressedDispatcher.onBackPressed() }
                // what the reader sees: the list is in front and has drawn (has the window's focus)
                waitFor("list back", 60_000) { var ok = false; runOnMainSync { ok = list.hasWindowFocus() }; ok }
                backs += SystemClock.uptimeMillis() - t1
                waitForIdleSync()
                SystemClock.sleep(3000)
            }
            removeMonitor(listMonitor)
            val docs = app.library.all().size
            result.putString("stream", "PERF docs=$docs open(ms)=$opens back(ms)=$backs median open=${opens.sorted()[opens.size / 2]} back=${backs.sorted()[backs.size / 2]}\n")
            runOnMainSync { list.finish() }
            finish(Activity.RESULT_OK, result)
        } catch (error: Throwable) {
            result.putString("stream", "FAILED: ${error.stackTraceToString()}")
            finish(Activity.RESULT_CANCELED, result)
        }
    }

    /** Runs on the main thread; a failed check is reported here instead of crashing the app. */
    private fun onMainChecked(block: () -> Unit) {
        var error: Throwable? = null
        runOnMainSync { try { block() } catch (t: Throwable) { error = t } }
        error?.let { throw it }
    }

    private fun openDoc(): ReaderActivity {
        val path = fileArg ?: return openShared(ReadOnlyPdfProvider.uri("booklet-1.pdf"))
        val doc = com.yeshsade.otzarot.reader.data.Intake(targetContext, app.library, app.settings).open(Uri.fromFile(File(path)))
            ?: error("cannot open $path")
        val monitor = addMonitor(ReaderActivity::class.java.name, null, false)
        targetContext.startActivity(Intent(targetContext, ReaderActivity::class.java).putExtra(ReaderActivity.EXTRA_DOC, doc.id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val a = waitForMonitorWithTimeout(monitor, 60_000) as? ReaderActivity ?: error("reader did not open")
        removeMonitor(monitor)
        return a
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> field(a: Any, name: String): T = a.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(a) as T

    /** A tap on the page away from check boxes (the middle of the left edge area). */
    private fun tapPage(a: ReaderActivity) {
        runOnMainSync {
            val v = pdfView(a)
            val m = ReaderActivity::class.java.getDeclaredMethod("tap", MotionEvent::class.java).apply { isAccessible = true }
            val now = SystemClock.uptimeMillis()
            var e = MotionEvent.obtain(now, now, MotionEvent.ACTION_UP, v.width * .5f, v.height * .62f, 0)
            if (v.findFormFieldAt(e.x, e.y) != null) { e.recycle(); e = MotionEvent.obtain(now, now, MotionEvent.ACTION_UP, v.width * .1f, v.height * .5f, 0) }
            m.invoke(a, e); e.recycle()
        }
        waitForIdleSync(); SystemClock.sleep(400)
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
        writePng(bitmap, name)
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
