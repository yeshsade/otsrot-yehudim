package com.gitlab.mudlej.MjPdfReader

import android.app.Activity
import android.app.Dialog
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.preference.PreferenceManager
import com.gitlab.mudlej.MjPdfReader.data.Preferences
import com.gitlab.mudlej.MjPdfReader.data.ReaderSettings
import com.gitlab.mudlej.MjPdfReader.data.ScreenAwakeMode
import com.gitlab.mudlej.MjPdfReader.ui.settings.ReaderSettingsActivity
import com.shockwave.pdfium.PdfiumCore
import java.io.File

/** Real Android view/callback and JNI checks, independent of emulator System UI rendering. */
class NativeSettingsProbe : Instrumentation() {
    private val passed = mutableListOf<String>()
    private fun record(message: String) {
        passed.add(message)
        sendStatus(0, Bundle().apply { putString("stream", "PASS: $message\n") })
    }

    private var probe: String? = null

    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); probe = arguments?.getString("probe"); start() }

    override fun onStart() {
        if (probe == "autosave") {
            val (code, bundle) = AutoSaveProbe(this).run()
            finish(code, bundle)
            return
        }
        val result = Bundle()
        try {
            val storage = PreferenceManager.getDefaultSharedPreferences(targetContext)
            val settings = ReaderSettings(storage)
            runOnMainSync {
                settings.setLanguage("he")
                settings.setAwakeMode(ScreenAwakeMode.ALWAYS)
                settings.upstream.setHorizontalScroll(false)
                settings.setResumeAtLastPosition(true)
                settings.setLockedOrientation(ReaderSettings.UNLOCKED)
                settings.upstream.setInterfaceTheme(Preferences.themeLight)
                settings.upstream.setPdfPagesTheme(Preferences.themeLight)
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("he"))
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            }
            var activity = launch()
            runOnMainSync {
                check(activity.window.decorView.findViewWithTag<View>("setting_scroll") != null)
                check(!activity.window.decorView.findViewWithTag<View>("setting_marks").isEnabled)
                check(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0)
                snapshot(activity, "settings-native-he-day.png")
            }
            record("Cold settings activity opens; Hebrew native views render; unfinished repeat-marking control is disabled")

            choose(activity, "scroll", "horizontal")
            choose(activity, "resume", "start")
            check(settings.upstream.getHorizontalScroll() && settings.upstream.getAlwaysOpenAtFirstPage())
            runOnMainSync { activity.finish() }
            waitForIdleSync()
            activity = launch()
            check(settings.upstream.getHorizontalScroll() && settings.upstream.getAlwaysOpenAtFirstPage())
            record("Native option callbacks persist navigation and resume preferences across activity reopening")

            choose(activity, "awake", "system")
            runOnMainSync { check(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON == 0) }
            choose(activity, "awake", "always")
            runOnMainSync { check(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0) }
            val finishedWindow = activity.window
            runOnMainSync { activity.finish() }
            waitForIdleSync()
            check(finishedWindow.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON == 0)
            record("Always-awake applies to the settings screen, System releases it, and real pause/destruction clears the window flag")

            activity = launch()
            choose(activity, "rotation", "lock")
            check(settings.lockedOrientation != ReaderSettings.UNLOCKED)
            choose(activity, "rotation", "phone")
            check(settings.lockedOrientation == ReaderSettings.UNLOCKED)
            record("Native rotation choices persist and release the app-only orientation preference")
            runOnMainSync { activity.finish() }
            waitForIdleSync()
            runOnMainSync {
                settings.setLanguage("en")
                settings.upstream.setInterfaceTheme(Preferences.themeDark)
                settings.upstream.setPdfPagesTheme(Preferences.themeDark)
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("en"))
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            }
            activity = launch()
            runOnMainSync {
                check(activity.window.decorView.findViewWithTag<View>("setting_language").layoutDirection == View.LAYOUT_DIRECTION_LTR)
                snapshot(activity, "settings-native-en-night.png")
                activity.finish()
            }
            waitForIdleSync()
            record("English/LTR and night-mode native views render with persisted app/PDF theme preferences")
            probePdfium()
            result.putString("stream", "Passed ${passed.size} native checks.\n")
            result.putInt("passed", passed.size)
            finish(Activity.RESULT_OK, result)
        } catch (error: Throwable) {
            result.putString("stream", "FAILED after ${passed.size} checks: ${error.stackTraceToString()}")
            result.putInt("passed", passed.size)
            finish(Activity.RESULT_CANCELED, result)
        }
    }

    private fun launch() = startActivitySync(Intent(targetContext, ReaderSettingsActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ReaderSettingsActivity

    private fun choose(activity: ReaderSettingsActivity, key: String, value: String) {
        runOnMainSync {
            check(activity.window.decorView.findViewWithTag<View>("setting_$key").performClick())
            val field = ReaderSettingsActivity::class.java.getDeclaredField("sheet").apply { isAccessible = true }
            val dialog = field.get(activity) as Dialog
            check(dialog.window!!.decorView.findViewWithTag<View>("choice_$value").performClick())
        }
        waitForIdleSync()
    }

    private fun snapshot(activity: ReaderSettingsActivity, name: String) {
        val decor = activity.window.decorView
        val metrics = activity.resources.displayMetrics
        decor.measure(View.MeasureSpec.makeMeasureSpec(metrics.widthPixels, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(metrics.heightPixels, View.MeasureSpec.EXACTLY))
        decor.layout(0, 0, metrics.widthPixels, metrics.heightPixels)
        val bitmap = Bitmap.createBitmap(metrics.widthPixels, metrics.heightPixels, Bitmap.Config.ARGB_8888)
        decor.draw(Canvas(bitmap))
        File(targetContext.filesDir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private fun probePdfium() {
        val core = PdfiumCore(targetContext)
        val bytes = context.assets.open("synthetic-checkbox-source.pdf").use { it.readBytes() }
        var document = core.newDocument(bytes)
        var fields = 0
        for (page in 0 until core.getPageCount(document)) {
            core.openPage(document, page)
            val rects = core.getFormFieldRects(document, page)
            for (offset in rects.indices step 4) {
                val field = core.getFormFieldAtPoint(document, page, (rects[offset] + rects[offset + 2]) / 2,
                    (rects[offset + 1] + rects[offset + 3]) / 2, 0f) ?: error("Missing checkbox")
                check(core.setFormFieldChecked(document, page, field.annotationIndex, true))
                fields++
            }
        }
        check(fields == 6)
        val checked = File(targetContext.filesDir, "native-checkbox-checked.pdf")
        ParcelFileDescriptor.open(checked, ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE or ParcelFileDescriptor.MODE_READ_WRITE).use {
            check(core.saveAsCopy(document, it))
        }
        core.closeDocument(document)
        document = core.newDocument(checked.readBytes())
        fields = 0
        for (page in 0 until core.getPageCount(document)) {
            core.openPage(document, page)
            val rects = core.getFormFieldRects(document, page)
            for (offset in rects.indices step 4) {
                val field = core.getFormFieldAtPoint(document, page, (rects[offset] + rects[offset + 2]) / 2,
                    (rects[offset + 1] + rects[offset + 3]) / 2, 0f) ?: error("Missing saved checkbox")
                check(field.isChecked)
                check(core.setFormFieldChecked(document, page, field.annotationIndex, false))
                fields++
            }
        }
        check(fields == 6)
        val cleared = File(targetContext.filesDir, "native-checkbox-cleared.pdf")
        ParcelFileDescriptor.open(cleared, ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE or ParcelFileDescriptor.MODE_READ_WRITE).use {
            check(core.saveAsCopy(document, it))
        }
        core.closeDocument(document)
        document = core.newDocument(cleared.readBytes())
        for (page in 0 until core.getPageCount(document)) {
            core.openPage(document, page)
            val rects = core.getFormFieldRects(document, page)
            for (offset in rects.indices step 4) {
                val field = core.getFormFieldAtPoint(document, page, (rects[offset] + rects[offset + 2]) / 2,
                    (rects[offset + 1] + rects[offset + 3]) / 2, 0f) ?: error("Missing cleared checkbox")
                check(!field.isChecked)
            }
        }
        core.closeDocument(document)
        record("Actual Android JNI loads and saves/reopens/checks/unchecks all six synthetic PDF form fields")
    }
}
