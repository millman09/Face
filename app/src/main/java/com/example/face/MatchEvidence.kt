package com.example.face

import android.content.Context
import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest

/**
 * Keeps a private copy of every image that matched, in app storage, so "My matches" still shows it when
 * you're offline, when the site blocks re-downloading, or after the image has been taken down (useful as
 * a record for a removal request). Copies are deleted when you mark one "Not a match" or clear your data,
 * and the oldest are dropped once the folder passes its size cap.
 */
object MatchEvidence {
    private const val MAX_BYTES = 200L * 1024L * 1024L

    private fun dir(context: Context) = File(context.filesDir, "match_evidence").apply { mkdirs() }

    private fun fileFor(context: Context, imageUrl: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(imageUrl.toByteArray())
        return File(dir(context), digest.joinToString("") { "%02x".format(it) } + ".jpg")
    }

    fun put(context: Context, imageUrl: String, bitmap: Bitmap) {
        try {
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 82, out)
            fileFor(context, imageUrl).writeBytes(out.toByteArray())
            trim(context)
        } catch (_: Exception) {
            // A missing copy only means the image is downloaded again when shown.
        }
    }

    fun get(context: Context, imageUrl: String): ByteArray? =
        fileFor(context, imageUrl).takeIf { it.isFile }?.let { file -> try { file.readBytes() } catch (_: Exception) { null } }

    fun delete(context: Context, imageUrl: String) {
        fileFor(context, imageUrl).delete()
    }

    fun clear(context: Context) {
        dir(context).listFiles()?.forEach { it.delete() }
    }

    private fun trim(context: Context) {
        val files = dir(context).listFiles()?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (file in files) {
            if (total <= MAX_BYTES) break
            total -= file.length()
            file.delete()
        }
    }
}
