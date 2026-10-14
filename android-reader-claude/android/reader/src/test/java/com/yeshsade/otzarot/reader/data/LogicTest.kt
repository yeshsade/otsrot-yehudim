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
        assertEquals(3, (u.pop() as UndoStack.Mark).annotation)
        assertEquals(1, (u.pop() as UndoStack.Mark).annotation)
        assertNull(u.pop())
        assertTrue(u.isEmpty)
    }

    @Test fun undoIsBounded() {
        val u = UndoStack(limit = 2)
        repeat(5) { u.push(UndoStack.Mark(it, it, false)) }
        assertEquals(4, (u.pop() as UndoStack.Mark).page)
        assertEquals(3, (u.pop() as UndoStack.Mark).page)
        assertNull(u.pop())
    }

    @Test fun undoKeepsBookmarkSteps() {
        val u = UndoStack()
        val b = Bookmark("a", 1, 50f, 60f, false, 19f, Bookmark.DEFAULT_COLOR)
        u.push(UndoStack.BookmarkChange(null, b))
        assertEquals(UndoStack.BookmarkChange(null, b), u.pop())
    }

    // ----- bookmarks -----
    @Test fun bookmarkNameRoundTrips() {
        val b = Bookmark("abc123", 4, 100f, 200f, true, 27f, 0x8031A0D0.toInt())
        assertEquals("otzr-bm:v1;o=h;s=27.0;c=8031A0D0;id=abc123", b.name)
        val back = Bookmark.parse(b.name, 4, b.frameRect())!!
        assertEquals(b, back)
    }

    @Test fun foreignStampsAreNotBookmarks() {
        assertNull(Bookmark.parse("some-other-stamp", 0, floatArrayOf(0f, 0f, 1f, 1f)))
        assertNull(Bookmark.parse("otzr-bm:v1;o=v", 0, floatArrayOf(0f, 0f, 1f, 1f))) // no id
    }

    @Test fun verticalRibbonPointsUpAndHasNotchDown() {
        val p = Bookmark.shape(false, 0f, 0f, 10f, 30f)
        assertEquals(10, p.size)
        // top edge slants: left corner lower than right corner
        assertTrue(p[1] > p[3])
        assertEquals(0f, p[3], 0.001f)
        // the notch point is in the middle, above the bottom
        assertEquals(5f, p[6], 0.001f)
        assertTrue(p[7] < 30f && p[7] > 20f)
    }

    @Test fun horizontalRibbonIsLongSideways() {
        val b = Bookmark("x", 0, 100f, 100f, true, 19f, 0)
        assertTrue(b.width > b.height)
        val v = b.copy(horizontal = false)
        assertTrue(v.height > v.width)
    }

    @Test fun bookmarkStaysOnPage() {
        val b = Bookmark("x", 0, -40f, 9999f, false, 19f, 0).keptOn(400f, 600f)
        val r = b.frameRect()
        assertTrue(r[0] >= 0f && r[2] <= 400f && r[1] >= 0f && r[3] <= 600f)
    }

    @Test fun opacityIsKeptInColour() {
        val c = Bookmark.withOpacity(0xFF57479F.toInt(), .8f)
        assertEquals(0xCC57479F.toInt(), c)
        assertEquals(.8f, Bookmark.opacityOf(c), 0.01f)
    }

    @Test fun ribbonsFrom022KeepTheirSize() {
        val r = floatArrayOf(0f, 0f, 10f, 30f)
        assertEquals(13f, Bookmark.parse("otzr-bm:v1;o=v;s=s;c=CC57479F;id=a", 0, r)!!.thickness, 0f)
        assertEquals(27f, Bookmark.parse("otzr-bm:v1;o=v;s=l;c=CC57479F;id=a", 0, r)!!.thickness, 0f)
        assertEquals(41.5f, Bookmark.parse("otzr-bm:v1;o=v;s=41.5;c=CC57479F;id=a", 0, r)!!.thickness, 0f)
        assertEquals(Bookmark.DEFAULT_THICKNESS, Bookmark.parse("otzr-bm:v1;o=v;s=x;c=CC57479F;id=a", 0, r)!!.thickness, 0f)
    }

    @Test fun sizeBarCoversOwnersRange() {
        val pageHeight = 842f // A4
        val max = Bookmark.maxThickness(pageHeight)
        // smallest = half the old "small"; largest = a ribbon a third of the page long
        assertEquals(6.5f, Bookmark.thicknessAt(0, max), 0.01f)
        assertEquals(pageHeight / 3, Bookmark.thicknessAt(100, max) * Bookmark.LENGTH_RATIO, 0.1f)
        // the default sits on the bar, and the bar goes back and forth without drift
        val p = Bookmark.progressOf(Bookmark.DEFAULT_THICKNESS, max)
        assertTrue(p in 1..99)
        assertEquals(Bookmark.DEFAULT_THICKNESS, Bookmark.thicknessAt(p, max), 0.6f)
        assertEquals(p, Bookmark.progressOf(Bookmark.thicknessAt(p, max), max))
        // always grows
        for (i in 1..100) assertTrue(Bookmark.thicknessAt(i, max) > Bookmark.thicknessAt(i - 1, max))
    }

    // ----- frame and page handle -----
    @Test fun frameHidesAfterTenSecondsByDefault() {
        assertEquals(10_000L, FramePolicy.hideAfterMillis(FramePolicy.AUTO, FramePolicy.DEFAULT_SECONDS))
        assertNull(FramePolicy.hideAfterMillis(FramePolicy.TAP, 10))
        assertNull(FramePolicy.hideAfterMillis(FramePolicy.ALWAYS, 10))
        assertFalse(FramePolicy.canHide(FramePolicy.ALWAYS))
        assertTrue(FramePolicy.canHide(FramePolicy.TAP))
    }

    @Test fun frameTimeStaysBetweenThreeSecondsAndAMinute() {
        assertEquals(3_000L, FramePolicy.hideAfterMillis(FramePolicy.AUTO, 0))
        assertEquals(60_000L, FramePolicy.hideAfterMillis(FramePolicy.AUTO, 600))
    }

    @Test fun handleHoldTimeIsInQuarterSeconds() {
        assertEquals(500, ScrubberPolicy.DEFAULT_HOLD_MS)
        assertEquals(0, ScrubberPolicy.clampHold(-5))
        assertEquals(2000, ScrubberPolicy.clampHold(9000))
        assertEquals(750, ScrubberPolicy.clampHold(700))
    }

    @Test fun handleMapsTrackToPages() {
        assertEquals(0, ScrubberPolicy.pageAt(0f, 40))
        assertEquals(39, ScrubberPolicy.pageAt(1f, 40))
        assertEquals(20, ScrubberPolicy.pageAt(.5f, 40))
        assertEquals(0, ScrubberPolicy.pageAt(.7f, 0))
    }

    @Test fun bookmarkLabelUsesNearestChapter() {
        val chapters = listOf(0 to "פתיחה", 10 to "משניות", 20 to "גמרא")
        assertEquals("משניות", BookmarkLabel.of(14, chapters) { "עמוד $it" })
        assertEquals("גמרא", BookmarkLabel.of(20, chapters) { "עמוד $it" })
        assertEquals("עמוד 4", BookmarkLabel.of(3, emptyList()) { "עמוד $it" })
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
