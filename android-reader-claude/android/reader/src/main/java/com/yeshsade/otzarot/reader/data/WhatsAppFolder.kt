// Lean Otzarot reader. License: GPLv3.
package com.yeshsade.otzarot.reader.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract

/**
 * One-time access to WhatsApp's documents folder through Android's own folder picker
 * (Storage Access Framework). With it, new booklets appear in the list by themselves and
 * marks are written into WhatsApp's file — no copy. Without it, the app keeps one working copy.
 */
object WhatsAppFolder {
    private const val STORAGE = "com.android.externalstorage.documents"
    val CANDIDATES = listOf(
        "primary:Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Documents",
        "primary:Android/media/com.whatsapp.w4b/WhatsApp Business/Media/WhatsApp Business Documents",
        "primary:WhatsApp/Media/WhatsApp Documents", // phones that still use the pre-Android-11 layout
    )

    /** Picker intent that opens directly at WhatsApp's documents folder when the phone allows it. */
    fun pickerIntent(): Intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            putExtra(DocumentsContract.EXTRA_INITIAL_URI, DocumentsContract.buildDocumentUri(STORAGE, CANDIDATES[0]))
        }
    }

    /** Keeps the grant across restarts. Returns false if Android did not give read+write. */
    fun accept(context: Context, settings: Settings, tree: Uri): Boolean {
        val ok = runCatching {
            context.contentResolver.takePersistableUriPermission(
                tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            true
        }.getOrDefault(false)
        if (ok) settings.whatsAppTree = tree.toString()
        return ok
    }

    fun grantedTree(context: Context, settings: Settings): Uri? {
        val tree = settings.whatsAppTree?.let(Uri::parse) ?: return null
        val held = context.contentResolver.persistedUriPermissions.any { it.uri == tree && it.isWritePermission && it.isReadPermission }
        return if (held) tree else null
    }

    data class Entry(val uri: Uri, val name: String, val modified: Long, val size: Long)

    /** PDFs directly inside the granted folder (not "Sent" — those are copies of what the user sent). */
    fun list(context: Context, tree: Uri): List<Entry> {
        val treeId = DocumentsContract.getTreeDocumentId(tree)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, treeId)
        val cols = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_SIZE,
        )
        val out = mutableListOf<Entry>()
        runCatching {
            context.contentResolver.query(children, cols, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(1) ?: continue
                    val mime = c.getString(2)
                    if (mime != "application/pdf" && !name.endsWith(".pdf", true)) continue
                    out += Entry(DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0)), name, c.getLong(3), c.getLong(4))
                }
            }
        }
        return out
    }
}
