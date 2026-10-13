package com.gitlab.mudlej.MjPdfReader

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import android.view.MotionEvent
import androidx.lifecycle.ViewModelProvider
import com.github.barteksc.pdfviewer.PDFView
import com.gitlab.mudlej.MjPdfReader.data.AppDatabase
import com.gitlab.mudlej.MjPdfReader.ui.reader.MainActivity
import com.gitlab.mudlej.MjPdfReader.ui.reader.ReaderComposition
import com.gitlab.mudlej.MjPdfReader.ui.reader.ReaderViewModel
import com.shockwave.pdfium.PdfDocument
import com.shockwave.pdfium.PdfiumCore
import java.security.MessageDigest

/**
 * End-to-end auto-save check on a real Android runtime:
 * read-only share -> open -> tap checkbox -> quiet save -> tap again -> back (no prompt) ->
 * reopen the same share -> the marked working file opens, with no extra copy or library entry,
 * and the shared original is unchanged.
 */
class AutoSaveProbe(private val ins: Instrumentation) {
    private var passed = 0
    private fun record(message: String) {
        passed++
        ins.sendStatus(0, Bundle().apply { putString("stream", "PASS: $message\n") })
    }

    /** Runs all checks; returns RESULT_OK or RESULT_CANCELED with a report. */
    fun run(): Pair<Int, Bundle> {
        val result = Bundle()
        try {
            val source = ReadOnlyPdfProvider.URI
            val originalDigest = digest(source)
            check(runCatching { ins.targetContext.contentResolver.openOutputStream(source, "wt")?.close() }.isFailure)
            record("Shared source is readable and refuses writing (read-only like WhatsApp)")

            var activity = open(source)
            waitLoaded(activity)
            check(vm(activity).doc.uri == source)
            val first = tapUncheckedCheckbox(activity, exclude = null)
            check(vm(activity).hasUnsavedAnnotations)
            waitFor("auto-save after the first mark", 90_000) { !vm(activity).hasUnsavedAnnotations && !vm(activity).isSavingAnnotations }
            val working = vm(activity).doc.uri!!
            check(working != source) { "saved into the read-only source?" }
            check(checkedCount(working) == 1) { "working file has ${checkedCount(working)} checks" }
            record("A tap is saved into the PDF automatically, without a save button or prompt, to a working file ($working)")

            tapUncheckedCheckbox(activity, exclude = first)
            check(vm(activity).hasUnsavedAnnotations)
            ins.runOnMainSync { activity.onBackPressedDispatcher.onBackPressed() }
            waitFor("leaving after back", 90_000) { activity.isFinishing || activity.isDestroyed }
            ins.waitForIdleSync()
            check(checkedCount(working) == 2) { "after leaving: ${checkedCount(working)} checks" }
            record("Back with an unsaved mark leaves without asking and the mark is in the same working file")

            activity = open(source)
            waitLoaded(activity)
            waitFor("redirect to working file", 60_000) { vm(activity).doc.uri == working }
            waitLoaded(activity)
            check(countCheckedInView(activity) == 2) { "reader shows ${countCheckedInView(activity)} checks" }
            record("Opening the same shared file again opens the marked working file (2 checks visible)")

            val copies = workingCopies()
            check(copies <= 1) { "$copies working copies" }
            val records = AppDatabase.getInstance(ins.targetContext).pdfRecordDao().findAll().size
            check(records == 1) { "$records library entries" }
            check(digest(source).contentEquals(originalDigest))
            record("One working copy, one library entry, and the shared original is unchanged")

            // Rotation (activity recreated) right after a mark: the mark is still saved.
            tapUncheckedCheckbox(activity, exclude = null)
            val monitor = ins.addMonitor(MainActivity::class.java.name, null, false)
            ins.runOnMainSync { activity.recreate() }
            activity = (ins.waitForMonitorWithTimeout(monitor, 60_000) as? MainActivity) ?: error("no recreated activity")
            ins.removeMonitor(monitor)
            ins.waitForIdleSync()
            waitFor("save after recreation", 90_000) { checkedCount(working) == 3 }
            record("A mark made just before rotation is saved after the screen is recreated")

            // Another screen on top (reader goes to background): saves at once.
            waitLoaded(activity)
            tapUncheckedCheckbox(activity, exclude = null)
            val settings = ins.startActivitySync(Intent(ins.targetContext, com.gitlab.mudlej.MjPdfReader.ui.settings.ReaderSettingsActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            waitFor("save on going to background", 90_000) { checkedCount(working) == 4 }
            record("Going to another screen saves at once (4 checks in the file)")
            ins.runOnMainSync { settings.finish() }
            check(workingCopies() <= 1)
            record("Still one working copy after all rounds")

            ins.runOnMainSync { activity.finish() }
            ins.waitForIdleSync()
            result.putString("stream", "Passed $passed auto-save checks.\n")
            return Activity.RESULT_OK to result
        } catch (error: Throwable) {
            result.putString("stream", "FAILED after $passed checks: ${error.stackTraceToString()}")
            return Activity.RESULT_CANCELED to result
        }
    }

    private fun open(uri: Uri): MainActivity {
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/pdf")
            .setClassName(ins.targetContext, MainActivity::class.java.name)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return ins.startActivitySync(intent) as MainActivity
    }

    private fun vm(activity: MainActivity): ReaderViewModel {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            return ViewModelProvider(activity)[ReaderViewModel::class.java]
        }
        var model: ReaderViewModel? = null
        ins.runOnMainSync { model = ViewModelProvider(activity)[ReaderViewModel::class.java] }
        return model!!
    }

    private fun composition(activity: MainActivity): ReaderComposition {
        val field = MainActivity::class.java.getDeclaredField("reader").apply { isAccessible = true }
        return field.get(activity) as ReaderComposition
    }

    private fun pdfView(activity: MainActivity): PDFView = activity.findViewById(R.id.pdfView)

    private fun waitLoaded(activity: MainActivity) {
        waitFor("document load", 120_000) {
            var ok = false
            ins.runOnMainSync { ok = pdfView(activity).getPageCount() > 0 && vm(activity).doc.fileHash != null }
            ok
        }
        SystemClock.sleep(1500)
        ins.waitForIdleSync()
    }

    private fun scanFields(activity: MainActivity): List<Pair<PDFView.FormField, FloatArray>> {
        val found = mutableListOf<Pair<PDFView.FormField, FloatArray>>()
        ins.runOnMainSync {
            val view = pdfView(activity)
            val seen = mutableSetOf<String>()
            var y = 4f
            while (y < view.height) {
                var x = 4f
                while (x < view.width) {
                    val field = view.findFormFieldAt(x, y)
                    if (field != null && field.type == PdfDocument.FormField.TYPE_CHECKBOX) {
                        val key = "${field.pageIndex}:${field.annotationIndex}"
                        if (seen.add(key)) found.add(field to floatArrayOf(x + 3, y + 3))
                    }
                    x += 6f
                }
                y += 6f
            }
        }
        return found
    }

    private fun tapUncheckedCheckbox(activity: MainActivity, exclude: String?): String {
        val (field, point) = scanFields(activity).firstOrNull { (f, _) ->
            !f.checked && "${f.pageIndex}:${f.annotationIndex}" != exclude
        } ?: error("No unchecked checkbox visible")
        ins.runOnMainSync {
            val now = SystemClock.uptimeMillis()
            val event = MotionEvent.obtain(now, now, MotionEvent.ACTION_UP, point[0], point[1], 0)
            check(composition(activity).formFieldController.handlePdfTap(event))
            event.recycle()
        }
        ins.waitForIdleSync()
        return "${field.pageIndex}:${field.annotationIndex}"
    }

    private fun countCheckedInView(activity: MainActivity) = scanFields(activity).count { it.first.checked }

    private fun checkedCount(uri: Uri): Int {
        val bytes = ins.targetContext.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        val core = PdfiumCore(ins.targetContext)
        val document = core.newDocument(bytes)
        var count = 0
        try {
            for (page in 0 until core.getPageCount(document)) {
                core.openPage(document, page)
                val rects = core.getFormFieldRects(document, page)
                for (offset in rects.indices step 4) {
                    val field = core.getFormFieldAtPoint(document, page, (rects[offset] + rects[offset + 2]) / 2,
                        (rects[offset + 1] + rects[offset + 3]) / 2, 0f) ?: continue
                    if (field.isChecked) count++
                }
            }
        } finally {
            core.closeDocument(document)
        }
        return count
    }

    private fun workingCopies(): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return 0
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        ins.targetContext.contentResolver.query(
            collection, arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?", arrayOf("booklet-from-chat%"), null,
        ).use { return it?.count ?: 0 }
    }

    private fun digest(uri: Uri): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        ins.targetContext.contentResolver.openInputStream(uri)!!.use { md.update(it.readBytes()) }
        return md.digest()
    }

    private fun waitFor(what: String, timeoutMs: Long, condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < end) {
            if (condition()) return
            SystemClock.sleep(250)
        }
        error("Timed out waiting for $what")
    }
}
