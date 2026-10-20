// Lean Otzarot reader. License: GPLv3.
package com.yeshsade.otzarot.reader.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * The hidden map inside a booklet (stage 2, owner's decisions 2026-10-13/15): for every check box, its topic,
 * its day and its order. It says only where a box belongs; whether it is ticked lives only in the PDF itself.
 *
 * Format: the JSON file [FILE_NAME] attached inside the PDF, schema [SCHEMA] (proposed by the booklet side, GPT,
 * in android-reader-gpt/docs/PROGRESS_MAP_AND_ALL_TOPICS_CHECKBOX_PLAN.md). A map that cannot be trusted is
 * refused as a whole — never a partial or guessed marking. Pure logic; unit-tested.
 */
data class ProgressMap(
    val topics: List<Topic>,
    val days: List<Day>,
    val checks: List<Check>,
    val booklet: Booklet = Booklet(null, null, null),
    val pages: List<PageInfo> = emptyList(),
) {
    data class Topic(val id: String, val label: String, val order: Int)
    data class Day(val id: String, val label: String, val order: Int)

    /** The booklet's week: [weekStart] is the Sunday of its week (YYYY-MM-DD), so the app knows last week from this. */
    data class Booklet(val weekStart: String?, val parsha: String?, val issue: String?)

    /**
     * What a booklet page belongs to (0-based [page]), for the day name on the page and the line at the top.
     * A page split between two days (Tehillim) has [regions]: each part's day and where it is on the page,
     * in points of the page as shown, from its top-left corner.
     */
    data class PageInfo(val page: Int, val topicId: String?, val dayId: String?, val weekOffset: Int, val regions: List<Region>)
    data class Region(val dayId: String?, val column: Int, val left: Float, val top: Float, val right: Float, val bottom: Float)

    /**
     * One box. [field] is the PDF form field's name — the link to the real box. A gemara amud printed on two
     * booklet pages is one field with two widgets, so one [Check] here (owner, 2026-10-15). [pages] are where its
     * widgets are (0-based), for finding them quickly.
     */
    data class Check(
        val id: String, val field: String, val topicId: String, val dayId: String?, val order: Int, val pages: List<Int>,
        /** Which page of the day's reading of this topic (1, 2, 3) and which column on it (1 = the rightmost). */
        val pageInDay: Int = 1, val column: Int = 1,
        /** The box's own name ("עמוד א׳", "חלק 1") and its row's title ("עמוד 1"), where there is one. */
        val label: String? = null, val group: String? = null,
    )

    fun pageInfo(page: Int): PageInfo? = pages.firstOrNull { it.page == page }
    fun day(id: String?) = days.firstOrNull { it.id == id }
    fun topic(id: String?) = topics.firstOrNull { it.id == id }

    fun checksOf(topicId: String? = null, dayId: String? = null) =
        checks.filter { (topicId == null || it.topicId == topicId) && (dayId == null || it.dayId == dayId) }
            .sortedWith(compareBy({ dayOrder(it.dayId) }, { topicOrder(it.topicId) }, { it.order }))

    private fun dayOrder(id: String?) = days.firstOrNull { it.id == id }?.order ?: Int.MAX_VALUE
    private fun topicOrder(id: String) = topics.firstOrNull { it.id == id }?.order ?: Int.MAX_VALUE

    sealed interface Result {
        data class Ok(val map: ProgressMap) : Result
        /** Why the map was refused (for logs and tests; the reader only sees "this file does not support it"). */
        data class Refused(val reason: String) : Result
    }

    companion object {
        const val FILE_NAME = "otzarot-progress-map.v1.json"
        const val SCHEMA = "otzarot.progress-map/1"

        fun parse(text: String): Result = try {
            parseChecked(JSONObject(text))
        } catch (e: Exception) {
            Result.Refused("not readable: ${e.message}")
        }

        private fun parseChecked(o: JSONObject): Result {
            if (o.optString("schema") != SCHEMA) return Result.Refused("unknown schema '${o.optString("schema")}'")
            val topics = list(o.optJSONArray("topics")) { Topic(it.getString("id"), it.optString("label_he", it.getString("id")), it.optInt("order")) }
            val days = list(o.optJSONArray("days")) { Day(it.getString("id"), it.optString("label_he", it.getString("id")), it.optInt("order")) }
            val topicIds = topics.map { it.id }.toSet()
            val dayIds = days.map { it.id }.toSet()
            if (topicIds.size != topics.size) return Result.Refused("a topic appears twice")
            if (dayIds.size != days.size) return Result.Refused("a day appears twice")
            val checks = mutableListOf<Check>()
            val raw = o.optJSONArray("checks") ?: return Result.Refused("no checks")
            for (i in 0 until raw.length()) {
                val c = raw.getJSONObject(i)
                // material from the previous week (השלמות) is not shown in the consolidated screen (owner, 2026-10-15)
                if (c.optInt("week_offset", 0) != 0) continue
                val field = c.optString("field")
                val topic = c.optString("topic_id")
                val day = if (c.isNull("day_id")) null else c.optString("day_id")
                if (field.isEmpty()) return Result.Refused("check $i has no field")
                if (topic !in topicIds) return Result.Refused("check '$field' has an unknown topic '$topic'")
                if (day != null && day !in dayIds) return Result.Refused("check '$field' has an unknown day '$day'")
                val pages = c.optJSONArray("pages")?.let { a -> (0 until a.length()).map { a.getInt(it) - 1 } }
                    ?: listOf(c.optInt("final_page", 0) - 1)
                if (pages.any { it < 0 }) return Result.Refused("check '$field' has no page")
                checks += Check(c.optString("id", field), field, topic, day, c.optInt("order"), pages,
                    pageInDay = c.optInt("page_in_day", 1), column = c.optInt("column", 1),
                    label = c.optString("label_he").takeIf { it.isNotBlank() && !c.isNull("label_he") },
                    group = c.optString("group_he").takeIf { it.isNotBlank() && !c.isNull("group_he") })
            }
            if (checks.map { it.field }.toSet().size != checks.size) return Result.Refused("a field appears twice")
            if (checks.isEmpty()) return Result.Refused("no checks for this week")
            val b = o.optJSONObject("booklet")
            fun text(key: String) = b?.takeIf { it.has(key) && !it.isNull(key) }?.optString(key)?.takeIf { it.isNotBlank() }
            val booklet = Booklet(text("week_start")?.takeIf { Regex("\\d{4}-\\d{2}-\\d{2}").matches(it) }, text("parsha"), text("issue"))
            val pages = list(o.optJSONArray("pages")) { p ->
                val regions = list(p.optJSONArray("reading_regions")) { r ->
                    val rect = r.getJSONArray("rect")
                    Region(if (r.isNull("day_id")) null else r.optString("day_id"), r.optInt("column", 1),
                        rect.getDouble(0).toFloat(), rect.getDouble(1).toFloat(), rect.getDouble(2).toFloat(), rect.getDouble(3).toFloat())
                }
                PageInfo(p.getInt("page") - 1, p.optString("topic_id").takeIf { it.isNotBlank() && !p.isNull("topic_id") },
                    if (p.isNull("day_id")) null else p.optString("day_id").takeIf { it.isNotBlank() }, p.optInt("week_offset", 0), regions)
            }
            return Result.Ok(ProgressMap(topics.sortedBy { it.order }, days.sortedBy { it.order }, checks, booklet, pages))
        }

        private fun <T> list(a: JSONArray?, item: (JSONObject) -> T): List<T> =
            if (a == null) emptyList() else (0 until a.length()).map { item(a.getJSONObject(it)) }

        /**
         * The map against the boxes really in the file: every field in the map must exist on the pages it names.
         * [fieldsOnPage] gives the field names found on a page. A box missing → the whole map is refused.
         */
        fun verify(map: ProgressMap, fieldsOnPage: (Int) -> Set<String>): Result {
            val cache = HashMap<Int, Set<String>>()
            for (check in map.checks) {
                for (page in check.pages) {
                    val names = cache.getOrPut(page) { fieldsOnPage(page) }
                    if (check.field !in names) return Result.Refused("field '${check.field}' is not on page ${page + 1}")
                }
            }
            return Result.Ok(map)
        }
    }
}
