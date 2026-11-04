package com.example.face

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Detects and counts faces in photos the user picks.
 *
 * This screen does NOT identify anyone or compare faces. Matching lives in
 * IdentityMatchActivity. Photos are read once, on this phone, and access to
 * them is not kept after the scan.
 */
class ScanActivity : AppCompatActivity() {

    private lateinit var results: TextView
    private lateinit var progress: ProgressBar
    private lateinit var scanButton: MaterialButton
    private lateinit var detector: FaceDetector
    private lateinit var decodeExecutor: ExecutorService

    private var selectedImages: List<Uri> = emptyList()
    private var currentIndex = 0
    private var totalFaces = 0

    private val imagePicker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            selectedImages = uris
            results.text = "${uris.size} images selected. Press Scan selected images."
            scanButton.isEnabled = true
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        decodeExecutor = Executors.newSingleThreadExecutor()

        // Earlier versions saved a reference-photo address here. It was never used, so clear it.
        getSharedPreferences("local_scan", MODE_PRIVATE).edit().clear().apply()

        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .build()
        detector = FaceDetection.getClient(options)

        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(24))
        }
        setScrollablePage(page)

        page.addView(TextView(this).apply {
            text = "On-device photo scan"
            textSize = 24f
            setPadding(0, 0, 0, dp(8))
        })
        page.addView(TextView(this).apply {
            text = "Pick photos on your phone and this screen counts the faces in each one. Nothing is uploaded, and Android only gives the app the photos you choose."
            textSize = 16f
            setPadding(0, 0, 0, dp(16))
        })

        page.addSpaced(MaterialButton(this).apply {
            text = "Choose images to scan"
            isAllCaps = false
            setOnClickListener { imagePicker.launch(arrayOf("image/*")) }
        })

        scanButton = MaterialButton(this).apply {
            text = "Scan selected images"
            isAllCaps = false
            isEnabled = false
            setOnClickListener { beginScan() }
        }
        page.addSpaced(scanButton)

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            this.progress = 0
            visibility = View.GONE
        }
        page.addView(progress)

        results = TextView(this).apply {
            textSize = 15f
            setPadding(0, dp(16), 0, 0)
            text = "No scan has been run yet."
        }
        page.addView(results)
    }

    private fun beginScan() {
        if (selectedImages.isEmpty()) {
            Toast.makeText(this, "Choose images first.", Toast.LENGTH_SHORT).show()
            return
        }
        currentIndex = 0
        totalFaces = 0
        progress.max = selectedImages.size
        progress.progress = 0
        progress.visibility = View.VISIBLE
        scanButton.isEnabled = false
        results.text = "Scanning locally…\n"
        scanNext()
    }

    private fun advance() {
        currentIndex++
        progress.progress = currentIndex
        scanNext()
    }

    private fun scanNext() {
        if (isFinishing || isDestroyed) return
        if (currentIndex >= selectedImages.size) {
            results.append("\nFinished. Found $totalFaces face(s) across ${selectedImages.size} image(s).")
            results.append("\n\nThis is face detection only, not identity matching. A detected face is not evidence that it is you.")
            progress.visibility = View.GONE
            scanButton.isEnabled = true
            return
        }

        val uri = selectedImages[currentIndex]
        val itemNumber = currentIndex + 1
        decodeExecutor.execute {
            try {
                val bitmap = decodeScaled(uri)
                runOnUiThread {
                    if (isFinishing || isDestroyed) {
                        bitmap.recycle()
                        return@runOnUiThread
                    }
                    val image = try {
                        InputImage.fromBitmap(bitmap, 0)
                    } catch (error: Exception) {
                        bitmap.recycle()
                        results.append("$itemNumber. ${displayName(uri)}: unable to read image (${error.localizedMessage ?: "unknown error"})\n")
                        advance()
                        return@runOnUiThread
                    }
                    detector.process(image)
                        .addOnSuccessListener { faces ->
                            totalFaces += faces.size
                            results.append("$itemNumber. ${displayName(uri)}: ${faces.size} face(s) detected\n")
                        }
                        .addOnFailureListener { error ->
                            results.append("$itemNumber. ${displayName(uri)}: scan failed (${error.localizedMessage ?: "unknown error"})\n")
                        }
                        .addOnCompleteListener {
                            bitmap.recycle()
                            advance()
                        }
                }
            } catch (error: Exception) {
                runOnUiThread {
                    results.append("$itemNumber. ${displayName(uri)}: unable to read image (${error.localizedMessage ?: "unknown error"})\n")
                    advance()
                }
            }
        }
    }

    private fun decodeScaled(uri: Uri): Bitmap {
        val source = ImageDecoder.createSource(contentResolver, uri)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            // ML Kit cannot read hardware-backed bitmaps, so ask for a normal one.
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val width = info.size.width
            val height = info.size.height
            val scale = minOf(1f, 1600f / maxOf(width, height))
            decoder.setTargetSize(
                (width * scale).toInt().coerceAtLeast(1),
                (height * scale).toInt().coerceAtLeast(1)
            )
        }
    }

    private fun displayName(uri: Uri): String {
        return try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            } ?: (uri.lastPathSegment ?: "Selected image")
        } catch (_: Exception) {
            uri.lastPathSegment ?: "Selected image"
        }
    }

    override fun onDestroy() {
        if (::detector.isInitialized) detector.close()
        if (::decodeExecutor.isInitialized) decodeExecutor.shutdown()
        super.onDestroy()
    }
}
