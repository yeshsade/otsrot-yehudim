// Lean Otzarot reader. License: GPLv3.
package com.yeshsade.otzarot.reader.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** File access helpers. Only [deleteDocument] deletes a file, and only when the reader asked for it. */
object Files {
    /** Visible folder for working copies (Documents/<this>). Temporary name until the product is named. */
    const val COPY_FOLDER = "קורא PDF"

    fun displayName(context: Context, uri: Uri): String {
        if (uri.scheme == "file") return File(uri.path ?: "").name.ifBlank { "document.pdf" }
        val name = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        }.getOrNull()
        return name?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "document.pdf"
    }

    /** True only when Android really lets this app write there; never assumed from a read grant. */
    fun canWrite(context: Context, uri: Uri): Boolean = when (uri.scheme) {
        "file" -> uri.path?.let { File(it) }?.let { it.isFile && it.canWrite() && it.parentFile?.canWrite() == true } == true
        "content" -> runCatching { context.contentResolver.openFileDescriptor(uri, "rw")?.use { true } ?: false }.getOrDefault(false)
        else -> false
    }

    /**
     * Writes [data] over [target] and verifies the result by content identity. Returns the identity of what
     * was written (null when the write failed or did not verify), so callers need not read the file again.
     * The data is read once (copied and identified together) and the result once (to verify it).
     * file:// targets use write-to-sibling + rename so a crash can't leave a half file.
     */
    fun writeVerified(context: Context, target: Uri, data: File): String? {
        if (target.scheme == "file") {
            val dest = File(target.path ?: return null)
            val sibling = File(dest.parentFile, dest.name + ".saving")
            return try {
                val expected = FileOutputStream(sibling).use { out ->
                    data.inputStream().use { Identity.copyAndIdentify(it, out) }.also { out.fd.sync() }
                }
                if (Identity.of(sibling) != expected || !sibling.renameTo(dest)) { sibling.delete(); null } else expected
            } catch (e: IOException) { sibling.delete(); null }
        }
        val expected = runCatching {
            context.contentResolver.openOutputStream(target, "wt")?.use { out -> data.inputStream().use { Identity.copyAndIdentify(it, out) } }
        }.getOrNull() ?: return null
        return expected.takeIf { Identity.of(context, target) == it }
    }

    /** The single working copy for a read-only original. Visible to the user in Documents/[COPY_FOLDER]. */
    fun createWorkingCopy(context: Context, source: Uri, name: String): Uri? {
        val fileName = sanitize(name)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOCUMENTS + "/" + COPY_FOLDER)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val target = runCatching { resolver.insert(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values) }.getOrNull()
                ?: return privateCopy(context, source, fileName)
            return runCatching {
                resolver.openOutputStream(target)!!.use { out -> resolver.openInputStream(source)!!.use { it.copyTo(out) } }
                values.clear(); values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(target, values, null, null)
                target
            }.getOrElse {
                runCatching { resolver.delete(target, null, null) }
                privateCopy(context, source, fileName)
            }
        }
        return privateCopy(context, source, fileName)
    }

    private fun privateCopy(context: Context, source: Uri, fileName: String): Uri? = runCatching {
        val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: context.filesDir, COPY_FOLDER).apply { mkdirs() }
        var target = File(dir, fileName)
        var n = 2
        while (target.exists()) target = File(dir, fileName.removeSuffix(".pdf") + " ($n).pdf").also { n++ }
        val partial = File(dir, target.name + ".part")
        partial.outputStream().use { out -> context.contentResolver.openInputStream(source)!!.use { it.copyTo(out) } }
        if (!partial.renameTo(target)) throw IOException("rename")
        Uri.fromFile(target)
    }.getOrNull()

    /** Deletes a working copy the app itself made. Never call with an original. */
    fun deleteWorkingCopy(context: Context, uri: Uri) {
        runCatching {
            if (uri.scheme == "file") File(uri.path!!).delete() else context.contentResolver.delete(uri, null, null)
        }
    }

    /**
     * "Delete from device" (owner, 2026-10-16): deletes the document's file — the file itself, or for a
     * read-only original (WhatsApp without access to all files) the app's working copy, never WhatsApp's file.
     * Returns true only when the file is really gone.
     */
    fun deleteDocument(context: Context, doc: Doc): Boolean {
        val uri = Uri.parse(doc.uri)
        val gone = runCatching {
            when {
                uri.scheme == "file" -> File(uri.path!!).let { it.delete() || !it.exists() }
                doc.kind == Doc.Kind.COPY -> context.contentResolver.delete(uri, null, null) > 0
                else -> android.provider.DocumentsContract.deleteDocument(context.contentResolver, uri)
            }
        }.getOrDefault(false)
        // a content link that cannot be read is not proof that the file is gone (the permission may be lost)
        return gone || (uri.scheme == "file" && !File(uri.path ?: "").exists())
    }

    fun sanitize(name: String): String {
        var base = name.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]"), "_").trim().trim('.')
        if (base.endsWith(".pdf", true)) base = base.dropLast(4)
        if (base.isBlank()) base = "document"
        return base.take(120) + ".pdf"
    }
}
