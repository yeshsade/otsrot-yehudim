// Lean Otzarot reader. License: GPLv3.
package com.yeshsade.otzarot.reader.data

/**
 * How the list is ordered (owner, 2026-10-16): what to sort by, and which way — every choice has its opposite.
 * The first way of each is the one shown first in the window. Size is offered only when switched on in the
 * settings ("it doesn't really interest anyone"). Pure logic; unit-tested.
 */
data class SortOrder(val by: By, val reversed: Boolean = false) {
    enum class By(val key: String) {
        RECEIVED("received"), NAME("name"), SIZE("size"), LAST_READ("last_read"), PAGES("pages");

        companion object {
            fun of(key: String?) = entries.firstOrNull { it.key == key } ?: RECEIVED
        }
    }

    fun apply(docs: List<Doc>, collator: Comparator<in String> = Comparator.naturalOrder<String>()): List<Doc> {
        val first: Comparator<Doc> = when (by) {
            By.RECEIVED -> compareByDescending { it.receivedAt }
            By.NAME -> Comparator { a, b -> collator.compare(a.name, b.name) }
            By.SIZE -> compareByDescending { it.size }
            By.LAST_READ -> compareByDescending<Doc> { it.lastOpened }.thenByDescending { it.receivedAt }
            By.PAGES -> compareByDescending { it.pages }
        }
        return docs.sortedWith(if (reversed) first.reversed() else first)
    }

    companion object {
        val DEFAULT = SortOrder(By.RECEIVED)

        /** The kinds offered in the window; size only when the reader switched it on. */
        fun offered(showSize: Boolean) = By.entries.filter { showSize || it != By.SIZE }

        /** The saved order, or the default when it is not offered any more (size switched off). */
        fun restore(key: String?, reversed: Boolean, showSize: Boolean): SortOrder {
            val by = By.of(key)
            return if (by in offered(showSize)) SortOrder(by, reversed) else DEFAULT
        }
    }
}
