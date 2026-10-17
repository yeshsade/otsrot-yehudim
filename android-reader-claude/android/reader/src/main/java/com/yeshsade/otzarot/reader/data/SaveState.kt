// Lean Otzarot reader. License: GPLv3.
package com.yeshsade.otzarot.reader.data

/**
 * Edits versus saves: a save never marks the document clean while an edit made during the save
 * is still only in memory. Pure logic; unit-tested.
 */
class SaveState {
    var generation = 0L
        private set
    private var saved = 0L
    private var inFlight: Long? = null

    val dirty get() = generation != saved
    val saving get() = inFlight != null

    fun edit() { generation++ }

    /** Starts a save if needed; returns the generation it covers, or null. */
    fun begin(): Long? {
        if (inFlight != null || !dirty) return null
        inFlight = generation
        return generation
    }

    /** Returns true when another save is needed (an edit arrived meanwhile, or this one failed). */
    fun finish(success: Boolean): Boolean {
        val covered = inFlight ?: return dirty
        inFlight = null
        if (success) saved = covered
        return dirty
    }
}

/** Undo of the last marks during this reading session (not kept after closing — as decided). */
class UndoStack(private val limit: Int = 200) {
    sealed interface Step
    /** A checkbox/radio changed; [before] is its state before the tap. */
    data class Mark(val page: Int, val annotation: Int, val before: Boolean) : Step
    /** A bookmark was added (before = null), removed (after = null), moved or changed. */
    data class BookmarkChange(val before: Bookmark?, val after: Bookmark?) : Step

    private val marks = ArrayDeque<Step>()
    val isEmpty get() = marks.isEmpty()

    fun push(step: Step) {
        marks.addLast(step)
        while (marks.size > limit) marks.removeFirst()
    }

    fun pop(): Step? = marks.removeLastOrNull()

    /** Takes back a step that turned out not to happen (the engine could not apply it). */
    fun remove(step: Step) { marks.lastIndexOf(step).takeIf { it >= 0 }?.let { marks.removeAt(it) } }
}
