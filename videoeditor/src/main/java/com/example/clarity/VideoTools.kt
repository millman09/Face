package com.example.clarity

import android.content.ContentValues
import android.content.Context
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.annotation.OptIn
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Size and length of a video, with width and height as it is shown (rotation applied). */
data class VideoInfo(val durationMs: Long, val width: Int, val height: Int) {
    val shortSide: Int get() = min(width, height)
}

object VideoProbe {

    fun info(context: Context, uri: Uri): VideoInfo? = withRetriever(context, uri) { r ->
        val duration = r.extract(MediaMetadataRetriever.METADATA_KEY_DURATION) ?: 0L
        val w = r.extract(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH) ?: 0L
        val h = r.extract(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT) ?: 0L
        val rotation = r.extract(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION) ?: 0L
        val sideways = rotation == 90L || rotation == 270L
        VideoInfo(
            durationMs = duration,
            width = (if (sideways) h else w).toInt(),
            height = (if (sideways) w else h).toInt()
        )
    }

    /**
     * Grabs [count] frames spread across [startMs]..[endMs], scaled down to about
     * [maxSide] pixels on the long side, for Auto Fix to study.
     */
    fun sampleFrames(
        context: Context,
        uri: Uri,
        startMs: Long,
        endMs: Long,
        count: Int = 8,
        maxSide: Int = 480,
    ): List<Frame> = withRetriever(context, uri) { r ->
        val info = info(context, uri)
        val scale = if (info == null || info.width <= 0 || info.height <= 0) 1f
        else maxSide.toFloat() / max(info.width, info.height)
        val dstW = ((info?.width ?: maxSide) * scale).roundToInt().coerceAtLeast(16)
        val dstH = ((info?.height ?: maxSide) * scale).roundToInt().coerceAtLeast(16)
        val span = (endMs - startMs).coerceAtLeast(1L)
        (0 until count).mapNotNull { i ->
            // Skip the very first and last moments, which are often black or blurred.
            val timeMs = startMs + span * (i + 0.5) / count
            val bitmap = r.getScaledFrameAtTime(
                (timeMs * 1000).toLong(),
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                dstW,
                dstH
            ) ?: return@mapNotNull null
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            val frame = Frame(pixels, bitmap.width, bitmap.height)
            bitmap.recycle()
            frame
        }
    } ?: emptyList()

    private fun MediaMetadataRetriever.extract(key: Int): Long? =
        extractMetadata(key)?.toLongOrNull()

    private fun <T> withRetriever(
        context: Context,
        uri: Uri,
        block: (MediaMetadataRetriever) -> T,
    ): T? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            block(retriever)
        } catch (e: RuntimeException) {
            null
        } finally {
            retriever.release()
        }
    }
}

/** What the export should do besides the look set in [EnhanceSettings]. */
data class ExportOptions(
    val startMs: Long,
    val endMs: Long,
    val upscale: Boolean,
    val removeAudio: Boolean,
)

/**
 * Renders the edited video with Media3 Transformer and saves it to
 * Movies/Clarity. Call from the main thread; callbacks arrive on the main thread.
 */
@OptIn(UnstableApi::class)
class VideoExporter(private val context: Context) {

    interface Callback {
        fun onProgress(percent: Int)
        fun onSaved(uri: Uri)
        fun onFailed(message: String)
    }

    private val handler = Handler(Looper.getMainLooper())
    private var transformer: Transformer? = null
    private var tempFile: File? = null

    fun start(
        input: Uri,
        info: VideoInfo,
        settings: EnhanceSettings,
        options: ExportOptions,
        callback: Callback,
    ) {
        cancel()

        val clipping = MediaItem.ClippingConfiguration.Builder()
            .setStartPositionMs(options.startMs)
            .apply { if (options.endMs < info.durationMs) setEndPositionMs(options.endMs) }
            .build()
        val mediaItem = MediaItem.Builder()
            .setUri(input)
            .setClippingConfiguration(clipping)
            .build()

        // Copy the settings so moving a slider during export can't change the output.
        val frozen = settings
        val effects = mutableListOf<Effect>(EnhanceEffect { frozen })
        var outW = info.width
        var outH = info.height
        if (options.upscale && info.shortSide in 1 until HD_SHORT_SIDE) {
            val scale = HD_SHORT_SIDE.toFloat() / info.shortSide
            outW = even(info.width * scale)
            outH = even(info.height * scale)
            effects += Presentation.createForWidthAndHeight(
                outW, outH, Presentation.LAYOUT_SCALE_TO_FIT
            )
            // Resizing softens the picture, so give the new pixels a light crispening pass.
            val crisp = EnhanceSettings(sharpen = 30f)
            effects += EnhanceEffect { crisp }
        }

        val edited = EditedMediaItem.Builder(mediaItem)
            .setRemoveAudio(options.removeAudio)
            .setEffects(Effects(/* audioProcessors= */ listOf(), effects))
            .build()

        // A generous bitrate so the cleaned-up detail survives compression.
        val pixelsPerSecond = outW.toLong() * outH * 30
        val bitrate = (pixelsPerSecond * 0.15).toLong().coerceIn(6_000_000L, 50_000_000L).toInt()
        val encoderFactory = DefaultEncoderFactory.Builder(context)
            .setRequestedVideoEncoderSettings(
                VideoEncoderSettings.Builder().setBitrate(bitrate).build()
            )
            .build()

        val output = File(context.cacheDir, "export_${System.currentTimeMillis()}.mp4")
        tempFile = output

        val t = Transformer.Builder(context)
            .setEncoderFactory(encoderFactory)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    handler.removeCallbacksAndMessages(null)
                    transformer = null
                    tempFile = null
                    Thread {
                        val saved = try {
                            saveToGallery(output)
                        } catch (e: Exception) {
                            null
                        } finally {
                            output.delete()
                        }
                        handler.post {
                            if (saved != null) callback.onSaved(saved)
                            else callback.onFailed("Couldn't save the video to your gallery.")
                        }
                    }.start()
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException,
                ) {
                    handler.removeCallbacksAndMessages(null)
                    transformer = null
                    tempFile = null
                    output.delete()
                    callback.onFailed(
                        exportException.localizedMessage ?: "This video couldn't be processed."
                    )
                }
            })
            .build()
        transformer = t
        t.start(edited, output.absolutePath)

        val holder = ProgressHolder()
        handler.post(object : Runnable {
            override fun run() {
                val current = transformer ?: return
                if (current.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                    callback.onProgress(holder.progress)
                }
                handler.postDelayed(this, 250)
            }
        })
    }

    fun cancel() {
        handler.removeCallbacksAndMessages(null)
        transformer?.cancel()
        transformer = null
        tempFile?.delete()
        tempFile = null
    }

    private fun saveToGallery(file: File): Uri? {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val name = "Clarity_$stamp.mp4"
        val resolver = context.contentResolver

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/Clarity")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val uri = resolver.insert(collection, values) ?: return null
            try {
                resolver.openOutputStream(uri)?.use { out ->
                    file.inputStream().use { it.copyTo(out) }
                } ?: throw IllegalStateException("No output stream")
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return uri
        }

        // Android 9: write the file directly (needs storage permission) and index it.
        @Suppress("DEPRECATION")
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
            "Clarity"
        )
        dir.mkdirs()
        val target = File(dir, name)
        file.copyTo(target, overwrite = true)
        val latch = CountDownLatch(1)
        var scanned: Uri? = null
        MediaScannerConnection.scanFile(
            context, arrayOf(target.absolutePath), arrayOf("video/mp4")
        ) { _, uri ->
            scanned = uri
            latch.countDown()
        }
        latch.await(10, TimeUnit.SECONDS)
        return scanned
    }

    private fun even(value: Float): Int = (value / 2f).roundToInt() * 2

    companion object {
        const val HD_SHORT_SIDE = 1080
    }
}
