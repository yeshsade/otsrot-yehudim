// Written by Mudlej. License is GPLv3.
// Otzarot Yehudim fork: no remote crash reporter is initialized.

package com.gitlab.mudlej.MjPdfReader

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.preference.PreferenceManager
import com.gitlab.mudlej.MjPdfReader.data.AutoBackupScheduler
import com.gitlab.mudlej.MjPdfReader.data.OnlineDocumentStore
import com.gitlab.mudlej.MjPdfReader.data.Preferences
import com.gitlab.mudlej.MjPdfReader.data.ReaderSettings
import com.gitlab.mudlej.MjPdfReader.core.ui.ReaderWindowPolicy
import com.gitlab.mudlej.MjPdfReader.ui.settings.ReaderSettingsActivity
import com.gitlab.mudlej.MjPdfReader.ui.reader.actions.PrintController
import com.gitlab.mudlej.MjPdfReader.ui.reader.controls.PdfThemeController
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.DynamicColorsOptions

class App : Application() {
    lateinit var readerWindowPolicy: ReaderWindowPolicy
        private set

    override fun onCreate() {
        DynamicColors.applyToActivitiesIfAvailable(this, DynamicColorsOptions.Builder()
            .setPrecondition { activity, _ -> activity !is ReaderSettingsActivity }
            .build())
        super.onCreate()
        val storage = PreferenceManager.getDefaultSharedPreferences(this)
        val readerSettings = ReaderSettings(storage)
        val preferences = readerSettings.upstream
        readerSettings.language?.let { AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(it)) }
        readerWindowPolicy = ReaderWindowPolicy(readerSettings, storage)
        registerActivityLifecycleCallbacks(readerWindowPolicy)
        AppCompatDelegate.setDefaultNightMode(PdfThemeController.interfaceNightMode(preferences))
        if (preferences.getAutoBackupEnabled()) {
            AutoBackupScheduler.ensureScheduled(this, preferences.getAutoBackupHour(), preferences.getAutoBackupMinute())
        }
        OnlineDocumentStore.sweepIncognito(this)
        PrintController.sweepStaging(this)
    }
}
