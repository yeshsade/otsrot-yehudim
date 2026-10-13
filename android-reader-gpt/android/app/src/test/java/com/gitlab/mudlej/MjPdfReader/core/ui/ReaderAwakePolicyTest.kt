package com.gitlab.mudlej.MjPdfReader.core.ui

import com.gitlab.mudlej.MjPdfReader.data.ScreenAwakeMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderAwakePolicyTest {
    @Test fun alwaysModeIncludesSettingsAndLibraryAndDoesNotExpire() {
        assertTrue(ReaderAwakePolicy.keepScreenOn(ScreenAwakeMode.ALWAYS, true, false, Long.MAX_VALUE))
        assertTrue(ReaderAwakePolicy.keepScreenOn(ScreenAwakeMode.ALWAYS, true, true, Long.MAX_VALUE))
    }

    @Test fun everyModeReleasesTheScreenWhenAppLeavesForeground() {
        ScreenAwakeMode.entries.forEach { mode ->
            assertFalse(ReaderAwakePolicy.keepScreenOn(mode, false, true, 0))
            assertFalse(ReaderAwakePolicy.keepScreenOn(mode, false, false, 0))
        }
    }

    @Test fun systemModeNeverOverridesThePhone() {
        assertFalse(ReaderAwakePolicy.keepScreenOn(ScreenAwakeMode.SYSTEM, true, true, 0))
        assertFalse(ReaderAwakePolicy.keepScreenOn(ScreenAwakeMode.SYSTEM, true, false, 0))
    }

    @Test fun extraTimeOnlyHoldsDuringReadingAndStopsAfterTenMinutes() {
        assertFalse(ReaderAwakePolicy.keepScreenOn(ScreenAwakeMode.MORE, true, false, 0))
        assertTrue(ReaderAwakePolicy.keepScreenOn(ScreenAwakeMode.MORE, true, true, 599_999))
        assertFalse(ReaderAwakePolicy.keepScreenOn(ScreenAwakeMode.MORE, true, true, 600_000))
    }

    @Test fun readerInteractionStartsAnotherHoldInterval() {
        assertFalse(ReaderAwakePolicy.keepScreenOn(ScreenAwakeMode.MORE, true, true, 700_000))
        assertTrue(ReaderAwakePolicy.keepScreenOn(ScreenAwakeMode.MORE, true, true, 0))
    }
}
