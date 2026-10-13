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
