// Lean Otzarot reader. License: GPLv3.
package com.yeshsade.otzarot.reader.data

/**
 * Search by name in the list. Forgiving the way people type: no matter upper or lower case, vowel marks,
 * quotation marks (״ " ׳ '), dashes, underscores, dots or several spaces. Pure logic; unit-tested.
 */
object NameSearch {
    fun matches(name: String, query: String): Boolean {
        val q = normalize(query)
        return q.isEmpty() || normalize(name).contains(q)
    }

    fun normalize(text: String): String {
        val out = StringBuilder(text.length)
        for (ch in text.lowercase()) {
            when {
                ch in '\u0591'..'\u05C7' -> Unit // Hebrew vowel and cantillation marks
                ch in "\"'״׳`" -> Unit
                ch in "-_.,־–—" || ch.isWhitespace() -> if (out.isNotEmpty() && out.last() != ' ') out.append(' ')
                else -> out.append(ch)
            }
        }
        return out.toString().trim()
    }
}
