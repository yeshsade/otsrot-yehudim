package com.yeshsade.otzarot.reader.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files as NioFiles

class LogicTest {

    // ----- intake decisions -----
    @Test fun knownDocumentNeverCreatesAnotherCopy() =
        assertEquals(IntakePlan.Action.OPEN_KNOWN, IntakePlan.decide(knownReadable = true, folderMatch = true, sourceWritable = true))

    @Test fun fileInGrantedWhatsAppFolderIsUsedInPlace() =
        assertEquals(IntakePlan.Action.USE_FOLDER_FILE, IntakePlan.decide(false, folderMatch = true, sourceWritable = false))

    @Test fun writableOriginalIsUsedInPlace() =
        assertEquals(IntakePlan.Action.USE_SOURCE, IntakePlan.decide(false, false, sourceWritable = true))

    @Test fun readOnlyOriginalGetsExactlyOneCopy() =
        assertEquals(IntakePlan.Action.MAKE_COPY, IntakePlan.decide(false, false, false))

    // ----- save state -----
    @Test fun saveCleansState() {
        val s = SaveState()
        s.edit()
        assertEquals(1L, s.begin())
        assertFalse(s.finish(true))
        assertFalse(s.dirty)
    }

    @Test fun editDuringSaveNeedsAnotherSave() {
        val s = SaveState()
        s.edit(); s.begin(); s.edit()
        assertNull("no overlapping saves", s.begin())
        assertTrue(s.finish(true))
        assertTrue(s.dirty)
    }

    @Test fun failedSaveStaysDirty() {
        val s = SaveState()
        s.edit(); s.begin()
        assertTrue(s.finish(false))
        assertTrue(s.dirty)
    }

    @Test fun nothingToSaveWhenClean() = assertNull(SaveState().begin())

    // ----- undo -----
    @Test fun undoReturnsMarksInReverse() {
        val u = UndoStack()
        u.push(UndoStack.Mark(0, 1, false)); u.push(UndoStack.Mark(2, 3, true))
        assertEquals(3, u.pop()!!.annotation)
        assertEquals(1, u.pop()!!.annotation)
        assertNull(u.pop())
        assertTrue(u.isEmpty)
    }

    @Test fun undoIsBounded() {
        val u = UndoStack(limit = 2)
        repeat(5) { u.push(UndoStack.Mark(it, it, false)) }
        assertEquals(4, u.pop()!!.page)
        assertEquals(3, u.pop()!!.page)
        assertNull(u.pop())
    }

    // ----- screen timeout -----
    @Test fun extraTenMinutesOnlyWhileReading() {
        assertTrue(AwakePolicy.keepScreenOn(AwakeMode.MORE, true, true, 0))
        assertFalse(AwakePolicy.keepScreenOn(AwakeMode.MORE, true, true, AwakePolicy.EXTRA_MILLIS))
        assertFalse(AwakePolicy.keepScreenOn(AwakeMode.MORE, true, false, 0))
    }

    @Test fun alwaysOnCoversEveryScreenButNotBackground() {
        assertTrue(AwakePolicy.keepScreenOn(AwakeMode.ALWAYS, true, false, Long.MAX_VALUE))
        assertFalse(AwakePolicy.keepScreenOn(AwakeMode.ALWAYS, false, true, 0))
        assertFalse(AwakePolicy.keepScreenOn(AwakeMode.SYSTEM, true, true, 0))
    }

    // ----- identity -----
    @Test fun identityIsByContentNotName() {
        val a = Identity.of(ByteArrayInputStream("same bytes".toByteArray()))
        val b = Identity.of(ByteArrayInputStream("same bytes".toByteArray()))
        val c = Identity.of(ByteArrayInputStream("corrected edition".toByteArray()))
        assertEquals(a, b)
        assertTrue(a != c)
    }

    // ----- library -----
    private fun doc(id: String, received: Long, identity: String = id, origin: String = identity) =
        Doc(id = id, uri = "content://x/$id", name = id, receivedAt = received, identity = identity, originIdentity = origin, kind = Doc.Kind.COPY)

    private fun tempLibrary(): Pair<Library, File> {
        val dir = NioFiles.createTempDirectory("lib").toFile()
        val file = File(dir, "library.json")
        return Library(file) to file
    }

    @Test fun orderIsByReceivedAndOpeningDoesNotMoveIt() {
        val (lib, _) = tempLibrary()
        lib.upsert(doc("old", 100)); lib.upsert(doc("new", 200))
        lib.update("old") { it.copy(lastOpened = 999, page = 7) }
        assertEquals(listOf("new", "old"), lib.all().map { it.id })
        assertEquals("old", lib.lastRead()!!.id)
    }

    @Test fun sameOriginalFoundAfterMarksChangedTheFile() {
        val (lib, _) = tempLibrary()
        lib.upsert(doc("a", 1, identity = "marked-version", origin = "original"))
        assertEquals("a", lib.byIdentity("original")!!.id)
        assertEquals("a", lib.byIdentity("marked-version")!!.id)
        assertNull(lib.byIdentity("corrected-edition"))
    }

    @Test fun libraryPersistsAcrossRestarts() {
        val (lib, file) = tempLibrary()
        lib.upsert(doc("a", 5))
        lib.update("a") { it.copy(page = 12, pages = 84) }
        val reopened = Library(file)
        assertEquals(12, reopened.get("a")!!.page)
        assertEquals(84, reopened.get("a")!!.pages)
    }

    @Test fun removeOnlyTouchesTheList() {
        val (lib, _) = tempLibrary()
        lib.upsert(doc("a", 1)); lib.upsert(doc("b", 2))
        lib.remove("a")
        assertEquals(listOf("b"), lib.all().map { it.id })
    }

    @Test fun fileNamesAreSafe() {
        assertEquals("a_b.pdf", Files.sanitize("a/b.pdf"))
        assertEquals("document.pdf", Files.sanitize("..."))
        assertEquals("חוברת וירא.pdf", Files.sanitize("חוברת וירא.PDF"))
    }
}
