package com.example.face

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.ViewGroup
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
 * First stage of the local scanner.
 *
 * This screen detects and counts faces in user-selected photos. It does NOT yet
 * identify a person or compare facial identity. Identity matching requires a
 * separate, reviewed face-embedding model and is intentionally not represented
 * by face-detection results.
 */
class ScanActivity : AppCompatActivity() {

    private lateinit var referenceLabel: TextView
    private lateinit var results: TextView
    private lateinit var progress: ProgressBar
    private lateinit var scanButton: MaterialButton
    private lateinit var detector: FaceDetector
    private lateinit var decodeExecutor: ExecutorService

    private var referenceUri: Uri? = null
    private var selectedImages: List<Uri> = emptyList()
    private var currentIndex = 0
    private var totalFaces = 0

    private val preferences by lazy { getSharedPreferences("local_scan", MODE_PRIVATE) }

    private val referencePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            persistReadPermission(uri)
            referenceUri = uri
            preferences.edit().putString("reference_uri", uri.toString()).apply()
            referenceLabel.text = "Reference photo: ${displayName(uri)}"
        }
    }

    private val imagePicker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            uris.forEach { persistReadPermission(it) }
            selectedImages = uris
            results.text = "${uris.size} images selected. Press Scan selected images."
            scanButton.isEnabled = true
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        decodeExecutor = Executors.newSingleThreadExecutor()
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .build()
        detector = FaceDetection.getClient(options)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 32, 40, 32)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        setContentView(root)

        root.addView(TextView(this).apply {
            text = "On-device photo scan"
            textSize = 24f
            setPadding(0, 0, 0, 20)
        })
        root.addView(TextView(this).apply {
            text = "Select a reference photo, then select pictures on your phone to inspect. Android's photo picker grants access only to the images you choose."
            textSize = 16f
            setPadding(0, 0, 0, 20)
        })

        referenceLabel = TextView(this).apply {
            textSize = 14f
            text = "Reference photo: none selected"
            setPadding(0, 8, 0, 16)
        }
        root.addView(referenceLabel)

        root.addView(MaterialButton(this).apply {
            text = "Choose my reference photo"
            isAllCaps = false
            setOnClickListener { referencePicker.launch(arrayOf("image/*")) }
        })

        root.addView(MaterialButton(this).apply {
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
        root.addView(scanButton)

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            visibility = ProgressBar.GONE
        }
        root.addView(progress)

        results = TextView(this).apply {
            textSize = 15f
            setPadding(0, 20, 0, 0)
            text = "No scan has been run yet."
        }
        root.addView(results)

        preferences.getString("reference_uri", null)?.let {
            referenceUri = Uri.parse(it)
            referenceLabel.text = "Saved reference photo: ${displayName(referenceUri!!)}"
        }
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
        progress.visibility = ProgressBar.VISIBLE
        scanButton.isEnabled = false
        results.text = "Scanning locally…\n"
        scanNext()
    }

    private fun scanNext() {
        if (isFinishing || isDestroyed) return
        if (currentIndex >= selectedImages.size) {
            results.append("\nFinished. Found $totalFaces face(s) across ${selectedImages.size} image(s).")
            results.append("\n\nImportant: this is face detection only, not identity matching. A detected face is not evidence that it is you.")
            progress.visibility = ProgressBar.GONE
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
                    detector.process(InputImage.fromBitmap(bitmap, 0))
                        .addOnSuccessListener { faces ->
                            totalFaces += faces.size
                            val name = displayName(uri)
                            results.append("$itemNumber. $name: ${faces.size} face(s) detected\n")
                        }
                        .addOnFailureListener { error ->
                            results.append("$itemNumber. ${displayName(uri)}: scan failed (${error.localizedMessage ?: "unknown error"})\n")
                        }
                        .addOnCompleteListener {
                            bitmap.recycle()
                            currentIndex++
                            progress.progress = currentIndex
                            scanNext()
                        }
                }
            } catch (error: Exception) {
                runOnUiThread {
                    results.append("$itemNumber. ${displayName(uri)}: unable to read image (${error.localizedMessage ?: "unknown error"})\n")
                    currentIndex++
                    progress.progress = currentIndex
                    scanNext()
                }
            }
        }
    }

    private fun decodeScaled(uri: Uri): Bitmap {
        val source = ImageDecoder.createSource(contentResolver, uri)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val width = info.size.width
            val height = info.size.height
            val scale = minOf(1f, 1600f / maxOf(width, height))
            decoder.setTargetSize(
                (width * scale).toInt().coerceAtLeast(1),
                (height * scale).toInt().coerceAtLeast(1)
            )
            decoder.isMutableRequired = false
        }
    }

    private fun persistReadPermission(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
            // Some document providers do not offer persistable permissions.
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
