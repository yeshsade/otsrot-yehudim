// Lean Otzarot reader. License: GPLv3.
package com.yeshsade.otzarot.reader.data

import java.util.Calendar
import java.util.TimeZone

/**
 * How much of a booklet was learned, from its map and the boxes ticked in the PDF (owner's decisions,
 * 2026-10-15/17): only the topics the reader chose in "מה אתה לומד?" count, and only "how much was done" is
 * told — never "missed" or "left". Pure logic; unit-tested.
 */
class Progress(private val map: ProgressMap, private val chosen: Set<String>, private val ticked: (String) -> Boolean) {
    data class Count(val done: Int, val total: Int) {
        val fraction get() = if (total == 0) 0f else done.toFloat() / total
        val complete get() = total > 0 && done == total
    }

    /** The topics this booklet has boxes for, in the booklet's order. */
    val topics: List<ProgressMap.Topic> = map.topics.filter { t -> map.checks.any { it.topicId == t.id } }

    /** The days this booklet has boxes for, in order. */
    val days: List<ProgressMap.Day> = map.days.filter { d -> map.checks.any { it.dayId == d.id } }

    private fun counted() = map.checks.filter { it.topicId in chosen }

    fun day(dayId: String): Count = count(counted().filter { it.dayId == dayId })
    fun week(): Count = count(counted())
    fun topicInDay(topicId: String, dayId: String): Count = count(map.checks.filter { it.topicId == topicId && it.dayId == dayId })
    fun topicInWeek(topicId: String): Count = count(map.checks.filter { it.topicId == topicId })

    private fun count(checks: List<ProgressMap.Check>) = Count(checks.count { ticked(it.field) }, checks.size)
}

/**
 * Which day the screen opens on (owner, 2026-10-15/17): the phone's day. A booklet of last week opens on Shabbat
 * (Saturday night: finishing last week), a booklet of next week on Sunday (Saturday night: starting the new one).
 * Which week a booklet belongs to comes from the map's week_start; without it, on Shabbat a booklet with marks
 * opens on Shabbat and a booklet without marks on Sunday. Returns a day id, or null for "the whole week" (the
 * day has no part in this booklet).
 */
object OpenDay {
    val IDS = listOf("sunday", "monday", "tuesday", "wednesday", "thursday", "friday", "saturday")

    /** [today] is the phone's date as y/m/d; [weekStart] is "YYYY-MM-DD" or null. */
    fun choose(days: List<String>, weekStart: String?, todayY: Int, todayM: Int, todayD: Int, anyMarked: Boolean): String? {
        val todayIndex = epochDay(todayY, todayM, todayD).let { (((it + 4) % 7 + 7) % 7).toInt() }   // 1970-01-01 was a Thursday; Sunday = 0
        val wanted = if (weekStart != null) {
            val (y, m, d) = weekStart.split("-").map { it.toInt() }
            val diff = epochDay(todayY, todayM, todayD) - epochDay(y, m, d)
            when {
                diff < 0 -> "sunday"
                diff > 6 -> "saturday"
                else -> IDS[diff.toInt()]
            }
        } else if (todayIndex == 6) {
            if (anyMarked) "saturday" else "sunday"
        } else IDS[todayIndex]
        return wanted.takeIf { it in days }
    }

    /** Days since 1970-01-01, in the Gregorian calendar (no java.time: the app runs on Android 6). */
    fun epochDay(y: Int, m: Int, d: Int): Long {
        val c = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        c.clear(); c.set(y, m - 1, d)
        return c.timeInMillis / 86_400_000L
    }

    fun today(): Triple<Int, Int, Int> = Calendar.getInstance().let { Triple(it.get(Calendar.YEAR), it.get(Calendar.MONTH) + 1, it.get(Calendar.DAY_OF_MONTH)) }
}
