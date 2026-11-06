package com.example.face

import android.content.Context
import java.io.File
import java.security.MessageDigest

/**
 * Short-lived copies of images that were shown as matches, so reopening the results (or building a
 * report) does not download them all again. Scanning itself never stores images. Files older than
 * a week are deleted at app start, and the folder is capped in size.
 */
object ImageCache {
    private const val MAX_AGE_MS = 7L * 24L * 60L * 60L * 1000L
    private const val MAX_BYTES = 50L * 1024L * 1024L

    private fun dir(context: Context) = File(context.cacheDir, "match_images").apply { mkdirs() }

    private fun fileFor(context: Context, url: String): File {
        val hash = MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(dir(context), "$hash.jpg")
    }

    fun get(context: Context, url: String): ByteArray? {
        val file = fileFor(context, url)
        return try { if (file.isFile) file.readBytes() else null } catch (_: Exception) { null }
    }

    fun put(context: Context, url: String, jpegBytes: ByteArray) {
        try { fileFor(context, url).writeBytes(jpegBytes) } catch (_: Exception) {}
    }

    /** Removes expired files, then the oldest ones while the folder is over its size cap. */
    fun trim(context: Context) {
        try {
            val files = dir(context).listFiles()?.filter { it.isFile } ?: return
            val cutoff = System.currentTimeMillis() - MAX_AGE_MS
            files.filter { it.lastModified() < cutoff }.forEach { it.delete() }
            var remaining = files.filter { it.exists() }.sortedBy { it.lastModified() }
            var total = remaining.sumOf { it.length() }
            for (file in remaining) {
                if (total <= MAX_BYTES) break
                total -= file.length()
                file.delete()
            }
        } catch (_: Exception) {}
    }

    fun clear(context: Context) {
        try { dir(context).listFiles()?.forEach { it.delete() } } catch (_: Exception) {}
    }
}
