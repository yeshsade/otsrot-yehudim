package com.gitlab.mudlej.MjPdfReader.ui.reader.annotation

import com.gitlab.mudlej.MjPdfReader.ui.reader.annotation.AutoSavePolicy.Candidates
import com.gitlab.mudlej.MjPdfReader.ui.reader.annotation.AutoSavePolicy.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoSavePolicyTest {

    private val whatsapp = "content://com.whatsapp.provider.media/item/123"

    @Test fun readOnlyWhatsAppWithoutCopyCreatesOneWorkingCopy() {
        val choice = AutoSavePolicy.choose(Candidates(sourceUri = whatsapp))
        assertEquals(Kind.NEW_WORKING_COPY, choice.kind)
        assertNull(choice.uri)
    }

    @Test fun readOnlySourceUsesExistingLibraryCopyInsteadOfNewOne() {
        val copy = "content://media/external/file/77"
        val choice = AutoSavePolicy.choose(Candidates(whatsapp, libraryCopy = copy, libraryCopyWritable = true))
        assertEquals(Kind.LIBRARY_COPY, choice.kind)
        assertEquals(copy, choice.uri)
    }

    @Test fun unwritableLibraryCopyIsNotUsed() {
        val choice = AutoSavePolicy.choose(Candidates(whatsapp, libraryCopy = "content://x/1", libraryCopyWritable = false))
        assertEquals(Kind.NEW_WORKING_COPY, choice.kind)
    }

    @Test fun libraryCopyEqualToSourceIsIgnoredWhenSourceReadOnly() {
        val choice = AutoSavePolicy.choose(Candidates(whatsapp, libraryCopy = whatsapp, libraryCopyWritable = true))
        assertEquals(Kind.NEW_WORKING_COPY, choice.kind)
    }

    @Test fun writableSourceIsUpdatedInPlace() {
        val file = "file:///storage/emulated/0/Download/a.pdf"
        val choice = AutoSavePolicy.choose(Candidates(file, sourceWritable = true, libraryCopy = "file:///x.pdf", libraryCopyWritable = true))
        assertEquals(Kind.SOURCE, choice.kind)
        assertEquals(file, choice.uri)
    }

    @Test fun remoteSourceIsNeverWrittenDirectly() {
        val choice = AutoSavePolicy.choose(Candidates("https://e.com/a.pdf", sourceWritable = true, sourceIsRemote = true))
        assertEquals(Kind.NEW_WORKING_COPY, choice.kind)
    }

    @Test fun sessionDestinationWinsSoNoSecondCopyIsMade() {
        val dest = "content://media/external/file/9"
        val choice = AutoSavePolicy.choose(
            Candidates(whatsapp, currentDestination = dest, currentDestinationWritable = true,
                rememberedDestination = "content://other", rememberedDestinationWritable = true)
        )
        assertEquals(Kind.CURRENT_DESTINATION, choice.kind)
        assertEquals(dest, choice.uri)
    }

    @Test fun rememberedDestinationReusedAcrossSessions() {
        val dest = "content://media/external/file/9"
        val choice = AutoSavePolicy.choose(Candidates(whatsapp, rememberedDestination = dest, rememberedDestinationWritable = true))
        assertEquals(Kind.REMEMBERED_DESTINATION, choice.kind)
    }

    @Test fun originalKeyIsNamespaced() {
        assertEquals("orig:h2abc", AutoSavePolicy.originalIdentityKey("h2abc"))
    }

    @Test fun stateCleanAfterSave() {
        val s = AutoSaveState()
        s.onEdit()
        assertTrue(s.isDirty)
        assertEquals(1L, s.beginSave())
        assertEquals(AutoSaveState.After.CLEAN, s.finishSave(true))
        assertFalse(s.isDirty)
    }

    @Test fun editDuringSaveTriggersAnotherSave() {
        val s = AutoSaveState()
        s.onEdit()
        s.beginSave()
        s.onEdit()
        assertNull("no parallel save", s.beginSave())
        assertEquals(AutoSaveState.After.SAVE_AGAIN, s.finishSave(true))
        assertTrue(s.isDirty)
        assertEquals(2L, s.beginSave())
        assertEquals(AutoSaveState.After.CLEAN, s.finishSave(true))
    }

    @Test fun failedSaveStaysDirtyAndReportsOnce() {
        val s = AutoSaveState()
        s.onEdit()
        s.beginSave()
        assertEquals(AutoSaveState.After.KEEP_DIRTY, s.finishSave(false))
        assertTrue(s.isDirty)
        assertTrue(s.shouldReportFailure())
        assertFalse(s.shouldReportFailure())
    }

    @Test fun nothingToSaveWhenClean() {
        val s = AutoSaveState()
        assertNull(s.beginSave())
        assertEquals(0L, s.beginSave(force = true))
    }
}
