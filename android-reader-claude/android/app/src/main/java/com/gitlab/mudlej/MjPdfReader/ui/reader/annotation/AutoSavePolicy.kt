// Auto-save policy for the Otzarot reader. Based on MJ PDF by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.reader.annotation

/**
 * Decides, without asking the reader, where the marks of the open document are written.
 *
 * Order of preference:
 *  1. The destination already used in this session (one working file per document).
 *  2. A destination remembered for this source (earlier session), if it is still writable.
 *  3. The source itself, when Android gives the app a real write path to it.
 *  4. The app's own library copy of this document (made once when a shared file is opened).
 *  5. A new working copy - created only when none of the above exists.
 *
 * Writing to the source is never assumed: a read-only grant (as WhatsApp gives) falls through
 * to the working copy. The original is never deleted.
 */
object AutoSavePolicy {

    enum class Kind { CURRENT_DESTINATION, REMEMBERED_DESTINATION, SOURCE, LIBRARY_COPY, NEW_WORKING_COPY }

    data class Candidates(
        val sourceUri: String,
        val currentDestination: String? = null,
        val currentDestinationWritable: Boolean = false,
        val rememberedDestination: String? = null,
        val rememberedDestinationWritable: Boolean = false,
        val sourceWritable: Boolean = false,
        val sourceIsRemote: Boolean = false,
        val libraryCopy: String? = null,
        val libraryCopyWritable: Boolean = false,
    )

    data class Choice(val kind: Kind, val uri: String?)

    fun choose(c: Candidates): Choice {
        if (c.currentDestination != null && c.currentDestinationWritable) {
            return Choice(Kind.CURRENT_DESTINATION, c.currentDestination)
        }
        if (c.rememberedDestination != null && c.rememberedDestinationWritable) {
            return Choice(Kind.REMEMBERED_DESTINATION, c.rememberedDestination)
        }
        if (c.sourceWritable && !c.sourceIsRemote) {
            return Choice(Kind.SOURCE, c.sourceUri)
        }
        if (c.libraryCopy != null && c.libraryCopy != c.sourceUri && c.libraryCopyWritable) {
            return Choice(Kind.LIBRARY_COPY, c.libraryCopy)
        }
        return Choice(Kind.NEW_WORKING_COPY, null)
    }

    /** Key under which "original file identity -> working file" is remembered. */
    fun originalIdentityKey(identity: String): String = "orig:$identity"
}

/**
 * Tracks edits versus saves so that a save never marks the document clean while an edit that
 * arrived during the save is still only in memory.
 */
class AutoSaveState {
    var generation: Long = 0
        private set
    private var savedGeneration: Long = 0
    private var inFlightGeneration: Long? = null
    private var failuresShown = 0

    val isDirty: Boolean get() = generation != savedGeneration
    val isSaving: Boolean get() = inFlightGeneration != null

    fun onEdit(): Long {
        generation++
        return generation
    }

    /** Returns the generation the save covers, or null if a save is already running or nothing changed. */
    fun beginSave(force: Boolean = false): Long? {
        if (inFlightGeneration != null) return null
        if (!force && !isDirty) return null
        inFlightGeneration = generation
        return generation
    }

    enum class After { CLEAN, SAVE_AGAIN, KEEP_DIRTY }

    fun finishSave(success: Boolean): After {
        val covered = inFlightGeneration ?: return if (isDirty) After.KEEP_DIRTY else After.CLEAN
        inFlightGeneration = null
        if (!success) return After.KEEP_DIRTY
        savedGeneration = covered
        return if (isDirty) After.SAVE_AGAIN else After.CLEAN
    }

    /** A failure is reported once per document; later failures stay quiet (the journal keeps the marks). */
    fun shouldReportFailure(): Boolean = failuresShown++ == 0

    fun reset() {
        generation = 0
        savedGeneration = 0
        inFlightGeneration = null
        failuresShown = 0
    }
}
