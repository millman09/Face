package com.example.face

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer

/** Safe downloading and decoding of public images, shared by the scan and the saved-images gallery. */
object PublicImageLoader {
    /** Throws unless [address] is a public HTTPS address (no local hosts, no embedded logins). */
    fun requirePublicHttps(address: String) {
        val uri = Uri.parse(address)
        require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null) {
            "Only public HTTPS image URLs are allowed."
        }
        val host = uri.host!!.lowercase(java.util.Locale.US)
        require(host != "localhost" && !host.endsWith(".localhost") && !host.endsWith(".local")) {
            "Local network addresses are not allowed."
        }
    }

    /**
     * [referer] is the page the image appears on; image hosts commonly refuse requests without it,
     * exactly as they would for a browser showing the page. Plain-http links are tried over https,
     * and up to four redirects (image CDNs use them constantly) are followed, re-checking every hop.
     */
    fun download(original: String, referer: String? = null): Bitmap {
        var address = if (original.startsWith("http://", ignoreCase = true)) "https://" + original.substring(7) else original
        requirePublicHttps(address)
        var opened: HttpURLConnection? = null
        try {
            var hops = 0
            while (true) {
                val attempt = (URL(address).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8000
                    readTimeout = 12000
                    instanceFollowRedirects = false
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", "FacePersonalScanner/1.0")
                    setRequestProperty("Accept", "image/*")
                    if (referer != null && referer.startsWith("https://")) setRequestProperty("Referer", referer)
                }
                opened = attempt
                val code = attempt.responseCode
                val location = attempt.getHeaderField("Location")
                if (code in 300..399 && hops < 4 && location != null) {
                    var next = URL(URL(address), location).toString()
                    if (next.startsWith("http://", ignoreCase = true)) next = "https://" + next.substring(7)
                    requirePublicHttps(next)
                    attempt.disconnect()
                    opened = null
                    address = next
                    hops++
                    continue
                }
                break
            }
            val connection = opened!!
            require(connection.responseCode in 200..299) { "HTTP ${connection.responseCode}" }
            val type = connection.contentType?.substringBefore(';')?.trim()?.lowercase(java.util.Locale.US).orEmpty()
            require(type.startsWith("image/")) { "The link did not return an image." }
            require(connection.contentLengthLong <= 8L * 1024L * 1024L || connection.contentLengthLong < 0) {
                "Image exceeds the 8 MB temporary processing limit."
            }
            val bytes = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(16 * 1024)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= 8 * 1024 * 1024) { "Image exceeds the 8 MB temporary processing limit." }
                    bytes.write(buffer, 0, count)
                }
            }
            return decode(bytes.toByteArray())
        } finally {
            opened?.disconnect()
        }
    }

    fun decode(data: ByteArray): Bitmap {
        // ImageDecoder applies the photo's rotation, which BitmapFactory ignores. Without it,
        // sideways phone photos would never show a face.
        return ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(data))) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            // 1280px keeps faces in wide shots large enough to match, without large memory use.
            val scale = minOf(1f, 1280f / maxOf(info.size.width, info.size.height))
            decoder.setTargetSize(
                (info.size.width * scale).toInt().coerceAtLeast(1),
                (info.size.height * scale).toInt().coerceAtLeast(1)
            )
        }
    }


    /** The saved copy of a matched image if there is one, else the short-term cache, else a fresh download. */
    fun loadForDisplay(context: Context, address: String, referer: String? = null): Bitmap {
        MatchEvidence.get(context, address)?.let { saved ->
            try { return decode(saved) } catch (_: Exception) { /* fall through */ }
        }
        ImageCache.get(context, address)?.let { cached ->
            try { return decode(cached) } catch (_: Exception) { /* fall through and download again */ }
        }
        val bitmap = download(address, referer)
        try {
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
            ImageCache.put(context, address, out.toByteArray())
        } catch (_: Exception) {}
        return bitmap
    }
}
