// Lean Otzarot reader. License: GPLv3.
package com.yeshsade.otzarot.reader.data

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * Content identity of a PDF: same bytes = same document, whatever the name or location.
 * A corrected edition has different bytes, so it is a different document.
 */
object Identity {
    fun of(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { of(it) }
    }.getOrNull()

    fun of(file: File): String? = runCatching { file.inputStream().use { of(it) } }.getOrNull()

    /**
     * Copies [input] to [output] and returns the copy's identity (the same value as [of]) — one read of the
     * data instead of two. Saving a big booklet reads every byte; this halves it.
     */
    fun copyAndIdentify(input: InputStream, output: java.io.OutputStream): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            output.write(buffer, 0, n)
            md.update(buffer, 0, n)
            total += n
        }
        return "s1-" + total + "-" + md.digest().joinToString("") { "%02x".format(it) }
    }

    fun of(input: InputStream): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            md.update(buffer, 0, n)
            total += n
        }
        return "s1-" + total + "-" + md.digest().joinToString("") { "%02x".format(it) }
    }
}
