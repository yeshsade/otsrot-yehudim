// Lean Otzarot reader. License: GPLv3.
package com.yeshsade.otzarot.reader.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * One document in the reader's list.
 * kind: SOURCE = the app writes to the original file (e.g. inside the granted WhatsApp folder);
 *       COPY = the app's single working copy (the original was read-only).
 */
data class Doc(
    val id: String,
    val uri: String,
    val name: String,
    val receivedAt: Long,
    val lastOpened: Long = 0,
    val page: Int = 0,
    val pages: Int = 0,
    val identity: String,
    /** Identity of the file as it first arrived; the same original arriving again opens this doc. */
    val originIdentity: String,
    val kind: Kind,
    /** The original location when kind == COPY (for reference only; never deleted). */
    val sourceUri: String? = null,
) {
    enum class Kind { SOURCE, COPY }

    fun matches(otherIdentity: String) = identity == otherIdentity || originIdentity == otherIdentity

    fun toJson() = JSONObject().apply {
        put("id", id); put("uri", uri); put("name", name); put("receivedAt", receivedAt)
        put("lastOpened", lastOpened); put("page", page); put("pages", pages)
        put("identity", identity); put("originIdentity", originIdentity); put("kind", kind.name)
        sourceUri?.let { put("sourceUri", it) }
    }

    companion object {
        fun fromJson(o: JSONObject) = Doc(
            id = o.getString("id"), uri = o.getString("uri"), name = o.getString("name"),
            receivedAt = o.getLong("receivedAt"), lastOpened = o.optLong("lastOpened"),
            page = o.optInt("page"), pages = o.optInt("pages"), identity = o.getString("identity"),
            originIdentity = o.optString("originIdentity", o.getString("identity")),
            kind = runCatching { Kind.valueOf(o.getString("kind")) }.getOrDefault(Kind.COPY),
            sourceUri = if (o.has("sourceUri")) o.getString("sourceUri") else null,
        )
    }
}

/**
 * The list of documents, kept as a small JSON file. Order is by time received, newest first —
 * opening or marking an old document never moves it (owner's requirement).
 */
class Library(private val file: File) {
    private val lock = Any()
    private var cache: MutableList<Doc>? = null

    private fun load(): MutableList<Doc> {
        cache?.let { return it }
        val list = mutableListOf<Doc>()
        if (file.exists()) runCatching {
            val arr = JSONArray(file.readText())
            for (i in 0 until arr.length()) list += Doc.fromJson(arr.getJSONObject(i))
        }
        cache = list
        return list
    }

    private fun persist(list: List<Doc>) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(JSONArray().apply { list.forEach { put(it.toJson()) } }.toString())
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }

    fun all(): List<Doc> = synchronized(lock) { load().sortedByDescending { it.receivedAt } }
    fun get(id: String): Doc? = synchronized(lock) { load().firstOrNull { it.id == id } }
    fun byUri(uri: String): Doc? = synchronized(lock) { load().firstOrNull { it.uri == uri || it.sourceUri == uri } }
    fun byIdentity(identity: String): Doc? = synchronized(lock) { load().firstOrNull { it.matches(identity) } }
    fun lastRead(): Doc? = synchronized(lock) { load().filter { it.lastOpened > 0 }.maxByOrNull { it.lastOpened } }

    fun upsert(doc: Doc) = synchronized(lock) {
        val list = load()
        val i = list.indexOfFirst { it.id == doc.id }
        if (i >= 0) list[i] = doc else list += doc
        persist(list)
    }

    fun update(id: String, change: (Doc) -> Doc): Doc? = synchronized(lock) {
        val list = load()
        val i = list.indexOfFirst { it.id == id }
        if (i < 0) return null
        list[i] = change(list[i])
        persist(list)
        list[i]
    }

    fun remove(id: String) = synchronized(lock) {
        val list = load()
        list.removeAll { it.id == id }
        persist(list)
    }
}
