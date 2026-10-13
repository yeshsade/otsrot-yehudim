// Lean Otzarot reader. License: GPLv3.
package com.yeshsade.otzarot.reader.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import java.util.UUID

/** Pure decision for an incoming file; unit-tested. */
object IntakePlan {
    enum class Action { OPEN_KNOWN, USE_FOLDER_FILE, USE_SOURCE, MAKE_COPY }

    fun decide(knownReadable: Boolean, folderMatch: Boolean, sourceWritable: Boolean): Action = when {
        knownReadable -> Action.OPEN_KNOWN       // same file or same original seen before: no new copy
        folderMatch -> Action.USE_FOLDER_FILE    // the file sits in the granted WhatsApp folder: write there
        sourceWritable -> Action.USE_SOURCE      // Android really allows writing to the original
        else -> Action.MAKE_COPY                 // read-only: exactly one working copy
    }
}

/** Turns "a PDF was opened" into a library document, following [IntakePlan]. */
class Intake(private val context: Context, private val library: Library, private val settings: Settings) {

    fun open(uri: Uri): Doc? {
        library.byUri(uri.toString())?.let { if (readable(Uri.parse(it.uri))) return it }
        val identity = Identity.of(context, uri) ?: return null
        val known = library.byIdentity(identity)?.takeIf { readable(Uri.parse(it.uri)) }
        val name = Files.displayName(context, uri)
        val folderFile = if (known == null) findInFolder(name, identity) else null
        val writable = known == null && folderFile == null && Files.canWrite(context, uri)
        return when (IntakePlan.decide(known != null, folderFile != null, writable)) {
            IntakePlan.Action.OPEN_KNOWN -> known
            IntakePlan.Action.USE_FOLDER_FILE -> add(folderFile!!, name, identity, Doc.Kind.SOURCE, null)
            IntakePlan.Action.USE_SOURCE -> {
                keepGrant(uri, write = true)
                add(uri, name, identity, Doc.Kind.SOURCE, null)
            }
            IntakePlan.Action.MAKE_COPY -> {
                val copy = Files.createWorkingCopy(context, uri, name)
                if (copy != null) add(copy, name, identity, Doc.Kind.COPY, uri)
                else { keepGrant(uri, write = false); add(uri, name, identity, Doc.Kind.SOURCE, null) }
            }
        }
    }

    /** Adds new PDFs from the granted WhatsApp folder. Returns how many were added. */
    fun scanFolder(): Int {
        val tree = WhatsAppFolder.grantedTree(context, settings) ?: return 0
        var added = 0
        for (entry in WhatsAppFolder.list(context, tree)) {
            if (library.byUri(entry.uri.toString()) != null) continue
            val identity = Identity.of(context, entry.uri) ?: continue
            if (library.byIdentity(identity) != null) continue
            add(entry.uri, entry.name, identity, Doc.Kind.SOURCE, null, receivedAt = entry.modified.takeIf { it > 0 })
            added++
        }
        return added
    }

    private fun findInFolder(name: String, identity: String): Uri? {
        val tree = WhatsAppFolder.grantedTree(context, settings) ?: return null
        return WhatsAppFolder.list(context, tree)
            .sortedByDescending { it.name == name }
            .firstOrNull { Identity.of(context, it.uri) == identity }?.uri
    }

    private fun add(uri: Uri, name: String, identity: String, kind: Doc.Kind, source: Uri?, receivedAt: Long? = null): Doc {
        val doc = Doc(
            id = UUID.randomUUID().toString(), uri = uri.toString(), name = name.removeSuffix(".pdf"),
            receivedAt = receivedAt ?: System.currentTimeMillis(), identity = identity, originIdentity = identity,
            kind = kind, sourceUri = source?.toString(),
        )
        library.upsert(doc)
        return doc
    }

    private fun readable(uri: Uri) = runCatching {
        context.contentResolver.openInputStream(uri)?.use { true } ?: false
    }.getOrDefault(false)

    private fun keepGrant(uri: Uri, write: Boolean) {
        if (uri.scheme != "content") return
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or (if (write) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
        runCatching { context.contentResolver.takePersistableUriPermission(uri, flags) }
    }
}
