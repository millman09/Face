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
                        val parts = line.split('|')
                        if (parts.size == 2) {
                            val values = parts[1].split(',').mapNotNull { it.toFloatOrNull() }
                            if (values.isNotEmpty()) map[parts[0]] = values.toFloatArray()
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
