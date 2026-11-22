package com.example.face

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File

/**
 * Caches the face embedding computed for each of your own reference photos, keyed by the photo's
 * content Uri and file size (a cheap stand-in for "is this the same file"). Re-running a deep search
 * with the same reference photos — the common case — then skips face detection, alignment and TFLite
 * inference for each one, instead of redoing that work on every single run. Nothing about candidate
 * images (other people's photos) is ever cached here; only your own selected reference photos are.
 */
object ReferenceEmbeddingCache {
    private const val FILE_NAME = "reference_embeddings.cache"
    private var loaded: MutableMap<String, FloatArray>? = null

    @Synchronized
    private fun cache(context: Context): MutableMap<String, FloatArray> {
        loaded?.let { return it }
        val map = mutableMapOf<String, FloatArray>()
        try {
            val file = File(context.filesDir, FILE_NAME)
            if (file.exists()) {
                file.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        // The key itself ("uri|size") contains '|', so the values start after the last one.
                        val split = line.lastIndexOf('|')
                        if (split > 0) {
                            val values = line.substring(split + 1).split(',').mapNotNull { it.toFloatOrNull() }
                            if (values.isNotEmpty()) map[line.substring(0, split)] = values.toFloatArray()
                        }
                    }
                }
            }
        } catch (_: Exception) {
            // A corrupt or unreadable cache file just means starting from empty; never fatal.
        }
        loaded = map
        return map
    }

    private fun keyFor(context: Context, uri: Uri): String? {
        val size = try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getLong(0) else null
            }
        } catch (_: Exception) {
            null
        } ?: return null
        return "$uri|$size"
    }

    /**
     * Identifies a set of reference photos (order doesn't matter), so a search can tell when you've
     * switched to different photos. Null if any photo can't be read.
     */
    fun fingerprint(context: Context, uris: List<Uri>): String? {
        val keys = uris.map { keyFor(context, it) ?: return null }.sorted()
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(keys.joinToString("\n").toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** Null if this exact photo (by Uri and file size) hasn't been embedded and cached before. */
    fun get(context: Context, uri: Uri): FloatArray? {
        val key = keyFor(context, uri) ?: return null
        return cache(context)[key]
    }

    @Synchronized
    fun put(context: Context, uri: Uri, embedding: FloatArray) {
        val key = keyFor(context, uri) ?: return
        val map = cache(context)
        map[key] = embedding
        try {
            File(context.filesDir, FILE_NAME).bufferedWriter().use { writer ->
                map.forEach { (k, values) ->
                    writer.write(k)
                    writer.write("|")
                    writer.write(values.joinToString(",") { it.toString() })
                    writer.newLine()
                }
            }
        } catch (_: Exception) {
            // Best-effort: if the write fails, the embedding just gets recomputed next time.
        }
    }
}
