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
    @Test fun undoThenRedoGivesTheStepBackAndANewStepClearsRedo() {
        val u = UndoStack()
        val a = UndoStack.Mark(0, 1, false); val b = UndoStack.Mark(0, 2, false)
        u.push(a); u.push(b)
        assertEquals(b, u.pop())
        assertTrue(u.canRedo)
        assertEquals(b, u.redo())
        assertFalse(u.canRedo)
        assertEquals(b, u.pop())
        u.push(UndoStack.Mark(1, 1, true))
        assertFalse(u.canRedo)
        assertNull(u.redo())
    }

    @Test fun copyingAndIdentifyingTogetherGivesTheSameIdentityAndBytes() {
        val bytes = ByteArray(200_000) { (it * 31 % 251).toByte() }
        val out = java.io.ByteArrayOutputStream()
        val id = Identity.copyAndIdentify(ByteArrayInputStream(bytes), out)
        assertEquals(Identity.of(ByteArrayInputStream(bytes)), id)
        assertTrue(bytes.contentEquals(out.toByteArray()))
    }

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

    @Test fun newBookmarkFallsIntoPlaceThenHopsOnce() {
        assertEquals(1f, DropIn.lift(0f), 1e-4f)          // starts above its place
        assertEquals(0f, DropIn.lift(1f), 1e-4f)          // ends exactly in place
        assertEquals(0f, DropIn.lift(.7f), 1e-4f)         // lands, then the hop
        var last = 2f
        for (i in 0..70) { val v = DropIn.lift(i / 100f); assertTrue(v <= last); last = v }  // only falls at first
        val hop = (71..100).maxOf { DropIn.lift(it / 100f) }
        assertTrue(hop > 0f && hop <= DropIn.HOP + 1e-4f)  // one small hop, never back up high
        assertEquals(0f, DropIn.opacity(0f), 1e-4f)
        assertEquals(1f, DropIn.opacity(.25f), 1e-4f)
        assertEquals(1f, DropIn.lift(-1f), 1e-4f)         // out-of-range times are safe
    }

    @Test fun everySortHasItsOppositeAndSizeIsHiddenByDefault() {
        val a = doc("a", 1).copy(name = "ב", size = 10, pages = 3, lastOpened = 5)
        val b = doc("b", 2).copy(name = "א", size = 30, pages = 1, lastOpened = 0)
        val c = doc("c", 3).copy(name = "ג", size = 20, pages = 2, lastOpened = 9)
        val docs = listOf(a, b, c)
        fun ids(o: SortOrder) = o.apply(docs).map { it.id }
        assertEquals(listOf("c", "b", "a"), ids(SortOrder(SortOrder.By.RECEIVED)))           // newest first
        assertEquals(listOf("a", "b", "c"), ids(SortOrder(SortOrder.By.RECEIVED, true)))     // oldest first
        assertEquals(listOf("b", "a", "c"), ids(SortOrder(SortOrder.By.NAME)))               // א–ת
        assertEquals(listOf("c", "a", "b"), ids(SortOrder(SortOrder.By.NAME, true)))         // ת–א
        assertEquals(listOf("b", "c", "a"), ids(SortOrder(SortOrder.By.SIZE)))               // largest first
        assertEquals(listOf("a", "c", "b"), ids(SortOrder(SortOrder.By.PAGES)))              // most pages first
        assertEquals(listOf("b", "c", "a"), ids(SortOrder(SortOrder.By.PAGES, true)))
        assertEquals(listOf("c", "a", "b"), ids(SortOrder(SortOrder.By.LAST_READ)))
        assertFalse(SortOrder.By.SIZE in SortOrder.offered(showSize = false))
        assertTrue(SortOrder.By.SIZE in SortOrder.offered(showSize = true))
        // size was chosen, then switched off in the settings: back to newest first
        assertEquals(SortOrder.DEFAULT, SortOrder.restore("size", true, showSize = false))
        assertEquals(SortOrder(SortOrder.By.NAME, true), SortOrder.restore("name", true, showSize = false))
    }

    private fun mapJson(extra: String = "", schema: String = ProgressMap.SCHEMA) = """
        {"schema":"$schema","topics":[{"id":"mishnah","label_he":"משנה","order":4},{"id":"gemara","label_he":"גמרא","order":1}],
         "days":[{"id":"sunday","label_he":"יום ראשון","order":1},{"id":"monday","label_he":"יום שני","order":2}],
         "checks":[
          {"field":"m1","topic_id":"mishnah","day_id":"monday","order":1,"final_page":3},
          {"field":"m2","topic_id":"mishnah","day_id":"sunday","order":2,"final_page":1},
          {"field":"g1","topic_id":"gemara","day_id":"sunday","order":1,"pages":[1,2]},
          {"field":"old","topic_id":"mishnah","day_id":"sunday","week_offset":-1,"order":1,"final_page":9}$extra]}
    """

    @Test fun progressMapReadsTopicsDaysAndBoxesAndLeavesOutLastWeek() {
        val map = (ProgressMap.parse(mapJson()) as ProgressMap.Result.Ok).map
        assertEquals(listOf("g1", "m2", "m1"), map.checks.let { map.checksOf() }.map { it.field })   // by day, then topic order
        assertEquals(listOf("m2"), map.checksOf(topicId = "mishnah", dayId = "sunday").map { it.field })
        assertEquals(listOf(0, 1), map.checks.first { it.field == "g1" }.pages)   // one gemara box on two booklet pages
        assertNull(map.checks.firstOrNull { it.field == "old" })                    // השלמות are not shown
    }

    @Test fun aProgressMapThatCannotBeTrustedIsRefusedAsAWhole() {
        assertTrue(ProgressMap.parse(mapJson(schema = "otzarot.progress-map/2")) is ProgressMap.Result.Refused)
        assertTrue(ProgressMap.parse(mapJson(""",{"field":"x","topic_id":"nope","day_id":"sunday","order":1,"final_page":1}""")) is ProgressMap.Result.Refused)
        assertTrue(ProgressMap.parse(mapJson(""",{"field":"m1","topic_id":"mishnah","day_id":"sunday","order":3,"final_page":1}""")) is ProgressMap.Result.Refused)
        assertTrue(ProgressMap.parse("not json") is ProgressMap.Result.Refused)
        val map = (ProgressMap.parse(mapJson()) as ProgressMap.Result.Ok).map
        val onPages = mapOf(0 to setOf("m2", "g1"), 1 to setOf("g1"), 2 to setOf("m1"))
        assertTrue(ProgressMap.verify(map) { onPages[it].orEmpty() } is ProgressMap.Result.Ok)
        // a box named in the map but missing from the PDF: refused, never a partial marking
        assertTrue(ProgressMap.verify(map) { (onPages - 1)[it].orEmpty() } is ProgressMap.Result.Refused)
    }
}
