// Lean Otzarot reader. License: GPLv3.
package com.yeshsade.otzarot.reader.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import java.io.File
import java.util.UUID

/** Pure decision for an incoming file; unit-tested. */
object IntakePlan {
    enum class Action { OPEN_KNOWN, USE_FOLDER_FILE, USE_SOURCE, MAKE_COPY }

    fun decide(knownReadable: Boolean, folderMatch: Boolean, sourceWritable: Boolean): Action = when {
        knownReadable -> Action.OPEN_KNOWN       // same file or same original seen before: no new copy
        folderMatch -> Action.USE_FOLDER_FILE    // the same file is on the device where the app may write: use it
        sourceWritable -> Action.USE_SOURCE      // Android really allows writing to the original
        else -> Action.MAKE_COPY                 // read-only: exactly one working copy
    }
}

/** Turns "a PDF was opened" into a library document, following [IntakePlan]. */
class Intake(private val context: Context, private val library: Library, private val settings: Settings) {

    fun open(uri: Uri): Doc? {
        // opening a file on purpose brings it back to the list if the reader had removed it
        library.unhide(uri.toString())
        library.byUri(uri.toString())?.let { if (readable(Uri.parse(it.uri))) return it }
        val identity = Identity.of(context, uri) ?: return null
        val size = sizeOf(uri)
        val name = Files.displayName(context, uri)
        var known = library.byIdentity(identity)?.takeIf { readable(Uri.parse(it.uri)) }
        if (known == null && AllFiles.granted(context)) {
            // e.g. WhatsApp hands over a link to a file on the device that the reader had removed: bring that file back
            unhideSameContent(identity, size)
            // e.g. WhatsApp hands over a read-only link to a file that is on the device: find that file.
            scanDevice()
            known = sameContentOnDevice(identity, size)
        }
        val folderFile = if (known == null) findInFolder(name, identity) else null
        val writable = known == null && folderFile == null && Files.canWrite(context, uri)
        return when (IntakePlan.decide(known != null, folderFile != null, writable)) {
            IntakePlan.Action.OPEN_KNOWN -> known
            IntakePlan.Action.USE_FOLDER_FILE -> add(folderFile!!, name, identity, Doc.Kind.SOURCE, null, size = size)
            IntakePlan.Action.USE_SOURCE -> {
                keepGrant(uri, write = true)
                add(uri, name, identity, Doc.Kind.SOURCE, null, size = size)
            }
            IntakePlan.Action.MAKE_COPY -> {
                val copy = Files.createWorkingCopy(context, uri, name)
                if (copy != null) add(copy, name, identity, Doc.Kind.COPY, uri, size = size)
                else { keepGrant(uri, write = false); add(uri, name, identity, Doc.Kind.SOURCE, null, size = size) }
            }
        }
    }

    /**
     * With access to all files: adds every PDF on the device that is not yet in the list, and drops
     * entries whose file is gone (e.g. WhatsApp cleared it). Content is only read later, when needed.
     * Returns true if the list changed.
     */
    fun scanDevice(): Boolean {
        if (!AllFiles.granted(context)) return scanFolder() > 0
        var changed = false
        val found = DeviceScan.all(context)
        for (item in found) {
            val fileUri = Uri.fromFile(item.file).toString()
            if (library.byUri(fileUri) != null || library.isHidden(fileUri)) continue
            // A booklet added earlier through the WhatsApp-folder permission: point it at the real file instead of adding twice.
            val earlier = library.all().firstOrNull {
                it.kind == Doc.Kind.SOURCE && it.uri.startsWith("content://com.android.externalstorage.documents") &&
                    it.name == item.file.name.removeSuffix(".pdf") && (it.size == 0L || it.size == item.size)
            }
            if (earlier != null) {
                library.update(earlier.id) { it.copy(uri = fileUri, size = item.size) }
            } else if (alreadyListedByContent(item)) {
                continue // the same original was opened before and already has its own entry (with the marks)
            } else {
                add(Uri.fromFile(item.file), item.file.name, identity = "", kind = Doc.Kind.SOURCE, source = null,
                    receivedAt = item.modified.takeIf { it > 0 }, size = item.size)
            }
            changed = true
        }
        for (doc in library.all()) {
            val uri = Uri.parse(doc.uri)
            if (doc.kind == Doc.Kind.SOURCE && uri.scheme == "file" && !File(uri.path ?: "").exists()) {
                library.remove(doc.id)
                changed = true
            }
        }
        return changed
    }

    private fun unhideSameContent(identity: String, size: Long) {
        for (hidden in library.hidden()) {
            val u = Uri.parse(hidden)
            if (u.scheme != "file") continue
            val f = File(u.path ?: continue)
            if (f.isFile && (size <= 0 || f.length() == size) && Identity.of(f) == identity) library.unhide(hidden)
        }
    }

    private fun alreadyListedByContent(item: DeviceScan.Found): Boolean {
        val sameSize = library.withSize(item.size).filter { !it.identityUnknown }
        if (sameSize.isEmpty()) return false
        val identity = Identity.of(item.file) ?: return false
        return sameSize.any { it.matches(identity) }
    }

    /** Checks same-size documents by content, computing identity only for those few. */
    private fun sameContentOnDevice(identity: String, size: Long): Doc? {
        for (doc in library.withSize(size)) {
            val docIdentity = if (doc.identityUnknown) {
                val computed = Identity.of(context, Uri.parse(doc.uri)) ?: continue
                library.update(doc.id) { it.copy(identity = computed, originIdentity = computed) }
                computed
            } else doc.identity
            if (docIdentity == identity || doc.originIdentity == identity) return library.get(doc.id)
        }
        return null
    }

    /** Adds new PDFs from the WhatsApp folder granted earlier (only used without access to all files). */
    fun scanFolder(): Int {
        val tree = WhatsAppFolder.grantedTree(context, settings) ?: return 0
        var added = 0
        for (entry in WhatsAppFolder.list(context, tree)) {
            if (library.byUri(entry.uri.toString()) != null || library.isHidden(entry.uri.toString())) continue
            val identity = Identity.of(context, entry.uri) ?: continue
            if (library.byIdentity(identity) != null) continue
            add(entry.uri, entry.name, identity, Doc.Kind.SOURCE, null, receivedAt = entry.modified.takeIf { it > 0 }, size = entry.size)
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

    private fun add(
        uri: Uri, name: String, identity: String, kind: Doc.Kind, source: Uri?,
        receivedAt: Long? = null, size: Long = 0,
    ): Doc {
        val doc = Doc(
            id = UUID.randomUUID().toString(), uri = uri.toString(), name = name.removeSuffix(".pdf").removeSuffix(".PDF"),
            receivedAt = receivedAt ?: System.currentTimeMillis(), identity = identity, originIdentity = identity,
            kind = kind, sourceUri = source?.toString(), size = size,
        )
        library.upsert(doc)
        return doc
    }

    private fun sizeOf(uri: Uri): Long = runCatching {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: 0L
    }.getOrDefault(0L)

    private fun readable(uri: Uri) = runCatching {
        context.contentResolver.openInputStream(uri)?.use { true } ?: false
    }.getOrDefault(false)

    private fun keepGrant(uri: Uri, write: Boolean) {
        if (uri.scheme != "content") return
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or (if (write) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
        runCatching { context.contentResolver.takePersistableUriPermission(uri, flags) }
    }
}
