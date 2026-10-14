// Lean Otzarot reader. License: GPLv3.
package com.yeshsade.otzarot.reader.data

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings as AndroidSettings
import java.io.File

/**
 * "Access to all files" (owner's decision): every PDF on the device appears in the list by itself,
 * including what arrives in WhatsApp, and marks are written into the file itself — no copy.
 * Android requires the user to switch it on once; without it the app keeps one working copy per document.
 */
object AllFiles {
    fun granted(context: Context): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED &&
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ||
                context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED)
    }

    /** Opens Android's own switch for this app (Android 11+), or asks the classic permission (older). */
    fun request(activity: Activity, requestCode: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val forApp = Intent(AndroidSettings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + activity.packageName))
            val ok = runCatching { activity.startActivity(forApp) }.isSuccess
            if (!ok) runCatching { activity.startActivity(Intent(AndroidSettings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
        } else {
            activity.requestPermissions(
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE), requestCode)
        }
    }
}

/** Finds PDFs on the device. Never opens, changes or deletes them while scanning. */
object DeviceScan {
    data class Found(val file: File, val size: Long, val modified: Long)

    /** Folders that hold copies the app made itself, or system/private data — never listed twice. */
    fun excluded(context: Context, path: String): Boolean {
        val root = Environment.getExternalStorageDirectory().absolutePath
        val own = listOfNotNull(
            "$root/${Environment.DIRECTORY_DOCUMENTS}/${Files.COPY_FOLDER}",
            context.getExternalFilesDir(null)?.absolutePath,
            "$root/Android/data", "$root/Android/obb",
        )
        if (own.any { path.startsWith(it) }) return true
        // Hidden folders (".Trash", ".Statuses", ".thumbnails"…) are not the user's documents.
        return path.removePrefix(root).split('/').any { it.startsWith(".") }
    }

    fun all(context: Context): List<Found> {
        val found = LinkedHashMap<String, Found>()
        fromMediaStore(context).forEach { found[it.file.absolutePath] = it }
        // WhatsApp's documents are walked directly too, in case the media index has not caught up yet.
        whatsAppFolders().forEach { dir -> walk(dir, 6) { found.putIfAbsent(it.file.absolutePath, it) } }
        return found.values.filter { !excluded(context, it.file.absolutePath) && it.size > 0 }
    }

    fun whatsAppFolders(): List<File> {
        val root = Environment.getExternalStorageDirectory()
        return listOf(
            "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Documents",
            "Android/media/com.whatsapp.w4b/WhatsApp Business/Media/WhatsApp Business Documents",
            "WhatsApp/Media/WhatsApp Documents",
            "WhatsApp Business/Media/WhatsApp Business Documents",
        ).map { File(root, it) }.filter { it.isDirectory }
    }

    @Suppress("DEPRECATION")
    private fun fromMediaStore(context: Context): List<Found> {
        val out = mutableListOf<Found>()
        val uri = MediaStore.Files.getContentUri("external")
        val cols = arrayOf(MediaStore.MediaColumns.DATA, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_MODIFIED)
        runCatching {
            context.contentResolver.query(
                uri, cols,
                "${MediaStore.MediaColumns.MIME_TYPE} = ? OR ${MediaStore.MediaColumns.DATA} LIKE ?",
                arrayOf("application/pdf", "%.pdf"), null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val path = c.getString(0) ?: continue
                    val file = File(path)
                    if (!file.isFile) continue
                    out += Found(file, file.length(), file.lastModified())
                }
            }
        }
        return out
    }

    private fun walk(dir: File, depth: Int, onPdf: (Found) -> Unit) {
        if (depth < 0) return
        val children = dir.listFiles() ?: return
        for (child in children) {
            if (child.name.startsWith(".")) continue
            if (child.isDirectory) walk(child, depth - 1, onPdf)
            else if (child.name.endsWith(".pdf", true)) onPdf(Found(child, child.length(), child.lastModified()))
        }
    }
}
