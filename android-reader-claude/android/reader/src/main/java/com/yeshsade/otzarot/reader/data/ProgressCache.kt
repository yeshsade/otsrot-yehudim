// Lean Otzarot reader. License: GPLv3.
package com.yeshsade.otzarot.reader.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * What the reader learned about a booklet's boxes, kept so the progress screen is ready at once the next time
 * (owner, 2026-10-20: "עדיין בהתחלה מד ההתקדמות לא הופיע"). Reading every box of a booklet takes the phone a
 * few seconds; this remembers, per booklet, where each box of the map is and which are ticked.
 *
 * [Entry.key] says which file it describes (the private copy's stamp and size): a file changed by anyone else
 * no longer matches and is read again. [Entry.mapHash] ties it to the exact map in that file.
 */
object ProgressCache {
    data class Entry(
        val key: String,
        val mapHash: String,
        /** field → its widgets (page, annotation index). */
        val widgets: Map<String, List<Pair<Int, Int>>>,
        val ticked: Set<String>,
    )

    private fun file(context: Context, docId: String) = File(File(context.filesDir, "progress-cache").apply { mkdirs() }, "$docId.json")

    fun hash(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }

    /** The key of the private copy [snapshot]: its stamp (what the document was when copied) and its size. */
    fun keyOf(snapshot: File?): String? {
        if (snapshot == null || !snapshot.isFile) return null
        val stamp = runCatching { File(snapshot.path + ".stamp").readText() }.getOrNull()?.takeIf { it.isNotBlank() } ?: return null
        return "$stamp|${snapshot.length()}"
    }

    fun load(context: Context, docId: String): Entry? = runCatching {
        val o = JSONObject(file(context, docId).readText())
        val widgets = HashMap<String, List<Pair<Int, Int>>>()
        val w = o.getJSONObject("widgets")
        for (name in w.keys()) {
            val a = w.getJSONArray(name)
            widgets[name] = (0 until a.length() step 2).map { a.getInt(it) to a.getInt(it + 1) }
        }
        val t = o.getJSONArray("ticked")
        Entry(o.getString("key"), o.getString("map"), widgets, (0 until t.length()).map { t.getString(it) }.toSet())
    }.getOrNull()

    fun save(context: Context, docId: String, entry: Entry) {
        runCatching {
            val w = JSONObject()
            for ((name, list) in entry.widgets) w.put(name, JSONArray().apply { list.forEach { put(it.first); put(it.second) } })
            val o = JSONObject().put("key", entry.key).put("map", entry.mapHash).put("widgets", w)
                .put("ticked", JSONArray(entry.ticked.sorted()))
            val target = file(context, docId)
            val part = File(target.path + ".part")
            part.writeText(o.toString())
            if (!part.renameTo(target)) { target.delete(); part.renameTo(target) }
        }
    }

    fun forget(context: Context, docId: String) { runCatching { file(context, docId).delete() } }
}
