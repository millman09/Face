package com.example.face

import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.util.Locale

/**
 * Downloads only image URLs hosted on the same public host as the registered
 * source. Redirects are disabled to avoid silently following a link elsewhere.
 */
object CandidateImageDownloader {
    private const val MAX_IMAGE_BYTES = 8 * 1024 * 1024

    fun download(candidate: StoredCandidate, cacheDirectory: File): File? {
        var connection: HttpURLConnection? = null
        var outputFile: File? = null
        return try {
            val image = URL(candidate.imageUrl)
            val base = URL(candidate.sourceBaseUrl)
            require(image.protocol == "https" || image.protocol == "http")
            require(image.host.equals(base.host, ignoreCase = true)) {
                "Image is hosted on a different domain; skipped for privacy and safety."
            }
            require(isPublicHost(image.host)) { "Private or local network host blocked." }

            connection = (image.openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 10000
                instanceFollowRedirects = false
                requestMethod = "GET"
                setRequestProperty("User-Agent", "FacePersonalScanner/1.0 (public image candidate)")
                setRequestProperty("Accept", "image/jpeg,image/png,image/webp,image/gif")
            }
            val code = connection.responseCode
            require(code == HttpURLConnection.HTTP_OK) { "Image server returned HTTP $code." }
            val type = connection.contentType?.substringBefore(';')?.trim()?.lowercase(Locale.US)
            require(type in setOf("image/jpeg", "image/png", "image/webp", "image/gif")) {
                "URL did not return a supported image."
            }
            val declared = connection.contentLengthLong
            require(declared <= MAX_IMAGE_BYTES) { "Image exceeds the 8 MB download limit." }

            if (!cacheDirectory.exists()) cacheDirectory.mkdirs()
            val suffix = when (type) {
                "image/png" -> ".png"
                "image/webp" -> ".webp"
                "image/gif" -> ".gif"
                else -> ".jpg"
            }
            outputFile = File.createTempFile("face_candidate_", suffix, cacheDirectory)
            var total = 0
            connection.inputStream.use { input ->
                FileOutputStream(outputFile).use { output ->
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= MAX_IMAGE_BYTES) { "Image exceeds the 8 MB download limit." }
                        output.write(buffer, 0, count)
                    }
                }
            }
            require(total > 0) { "Image was empty." }
            outputFile
        } catch (_: Exception) {
            outputFile?.delete()
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun isPublicHost(host: String): Boolean {
        return try {
            InetAddress.getAllByName(host).isNotEmpty() && InetAddress.getAllByName(host).all { address ->
                !address.isAnyLocalAddress && !address.isLoopbackAddress &&
                    !address.isLinkLocalAddress && !address.isSiteLocalAddress &&
                    !address.isMulticastAddress && !isCarrierGradeNat(address.address)
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun isCarrierGradeNat(bytes: ByteArray): Boolean {
        if (bytes.size != 4) return false
        val first = bytes[0].toInt() and 0xff
        val second = bytes[1].toInt() and 0xff
        return first == 100 && second in 64..127
    }
}
