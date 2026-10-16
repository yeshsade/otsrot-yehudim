// Lean Otzarot reader. License: GPLv3.
package com.yeshsade.otzarot.reader.ui

import android.content.Context
import com.yeshsade.otzarot.reader.R
import com.yeshsade.otzarot.reader.data.SortOrder

/** The words of the sort window (approved 2026-10-16). */
object SortTexts {
    fun name(context: Context, by: SortOrder.By) = context.getString(when (by) {
        SortOrder.By.RECEIVED -> R.string.sort_received
        SortOrder.By.NAME -> R.string.sort_name
        SortOrder.By.SIZE -> R.string.sort_size
        SortOrder.By.LAST_READ -> R.string.sort_last_read
        SortOrder.By.PAGES -> R.string.sort_pages
    })

    fun direction(context: Context, by: SortOrder.By, reversed: Boolean) = context.getString(when (by) {
        SortOrder.By.RECEIVED, SortOrder.By.LAST_READ -> if (reversed) R.string.dir_oldest else R.string.dir_newest
        SortOrder.By.NAME -> if (reversed) R.string.dir_za else R.string.dir_az
        SortOrder.By.SIZE -> if (reversed) R.string.dir_small else R.string.dir_big
        SortOrder.By.PAGES -> if (reversed) R.string.dir_few else R.string.dir_many
    })

    /** The line above the list: "מהחדש לישן" for the date received, else "שם (א–ת)" and the like. */
    fun short(context: Context, order: SortOrder): String {
        val way = direction(context, order.by, order.reversed)
        return if (order.by == SortOrder.By.RECEIVED) way else name(context, order.by) + " (" + way + ")"
    }
}
