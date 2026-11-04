package com.example.face

import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.android.gms.tasks.Tasks
import org.tensorflow.lite.Interpreter
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import kotlin.math.sqrt

/**
 * Local 1:N face comparison. The user supplies a MobileFaceNet-compatible
 * float32 TFLite model; model weights are deliberately not redistributed
 * because their provenance/licensing must be verified independently.
 *
 * Expected model contract: [1,112,112,3] RGB float input normalized to [-1,1],
 * and [1,N] float embedding output. Face crops are bounding-box crops (not
 * landmark-aligned), so match scores must be treated as leads, not proof.
 */
class IdentityMatchActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var scanButton: MaterialButton
    private var modelFile: File? = null
    private var referenceUri: Uri? = null
    private var imageUris: List<Uri> = emptyList()
    private val executor = Executors.newSingleThreadExecutor()
    private var detector: FaceDetector? = null

    private val modelPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                val target = File(filesDir, "face_embedding_model.tflite")
                contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(target).use { output -> input.copyTo(output) }
                } ?: throw IllegalStateException("Could not read the selected model.")
                modelFile = target
                status.text = "Model selected: ${target.length() / 1024} KB. Choose your reference photo and scan images."
                updateReady()
            } catch (e: Exception) {
                status.text = "Model import failed: ${e.localizedMessage ?: "unknown error"}"
            }
        }
    }
    private val referencePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { referenceUri = uri; status.text = "Reference photo selected."; updateReady() }
    }
    private val imagesPicker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) { imageUris = uris; status.text = "${uris.size} candidate images selected."; updateReady() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 28, 36, 36)
            layoutParams = ViewGroup.LayoutParams(-1, -1)
        }
        setContentView(root)
        root.addView(TextView(this).apply {
            text = "On-device face matching"
            textSize = 24f
            setPadding(0, 0, 0, 12)
        })
        root.addView(TextView(this).apply {
            text = "This compares face embeddings locally. Select a compatible MobileFaceNet TFLite model, one clear reference photo, and images to inspect. Nothing is uploaded. The model file is not bundled; only use a model whose license and input/output format you have verified."
            textSize = 14f
            setPadding(0, 0, 0, 16)
        })
        addButton(root, "Choose TFLite face model") { modelPicker.launch(arrayOf("*/*")) }
        addButton(root, "Choose reference photo") { referencePicker.launch(arrayOf("image/*")) }
        addButton(root, "Choose candidate images") { imagesPicker.launch(arrayOf("image/*")) }
        scanButton = MaterialButton(this).apply {
            text = "Compare faces on this phone"
            isAllCaps = false
            isEnabled = false
            setOnClickListener { runComparison() }
        }
        root.addView(scanButton)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { visibility = ProgressBar.GONE }
        root.addView(progress)
        status = TextView(this).apply {
            text = "No model or images selected."
            textSize = 14f
            setPadding(0, 16, 0, 0)
        }
        root.addView(status)
    }

    private fun addButton(root: LinearLayout, label: String, action: () -> Unit) {
        root.addView(MaterialButton(this).apply { text = label; isAllCaps = false; setOnClickListener { action() } })
    }

    private fun updateReady() {
        scanButton.isEnabled = modelFile?.exists() == true && referenceUri != null && imageUris.isNotEmpty()
    }

    private fun runComparison() {
        val model = modelFile ?: return
        val ref = referenceUri ?: return
        val candidates = imageUris.toList()
        scanButton.isEnabled = false
        progress.visibility = ProgressBar.VISIBLE
        progress.max = candidates.size
        progress.progress = 0
        status.text = "Preparing local face matcher…"
        executor.execute {
            var interpreter: Interpreter? = null
            var faceDetector: FaceDetector? = null
            try {
                interpreter = Interpreter(model, Interpreter.Options().setNumThreads(4))
                val inputShape = interpreter.getInputTensor(0).shape()
                val outputShape = interpreter.getOutputTensor(0).shape()
                require(inputShape.contentEquals(intArrayOf(1, 112, 112, 3))) {
                    "Unsupported model input ${inputShape.contentToString()}. Expected [1,112,112,3]."
                }
                require(outputShape.size == 2 && outputShape[0] == 1 && outputShape[1] > 0) {
                    "Unsupported model output ${outputShape.contentToString()}. Expected [1,N]."
                }
                require(interpreter.getInputTensor(0).dataType() == org.tensorflow.lite.DataType.FLOAT32 &&
                    interpreter.getOutputTensor(0).dataType() == org.tensorflow.lite.DataType.FLOAT32) {
                    "This first version requires float32 model input and output."
                }
                val options = FaceDetectorOptions.Builder()
                    .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                    .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                    .build()
                faceDetector = FaceDetection.getClient(options)
                val referenceBitmap = decode(ref)
                val referenceFaces = Tasks.await(faceDetector.process(InputImage.fromBitmap(referenceBitmap, 0)))
                require(referenceFaces.isNotEmpty()) { "No face was detected in the reference photo." }
                val referenceEmbedding = embedding(interpreter, referenceBitmap, referenceFaces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }!!)
                referenceBitmap.recycle()

                val report = StringBuilder("Reference face enrolled for this scan session.\n")
                var checked = 0
                var matched = 0
                candidates.forEachIndexed { index, uri ->
                    try {
                        val bitmap = decode(uri)
                        val faces = Tasks.await(faceDetector.process(InputImage.fromBitmap(bitmap, 0)))
                        val scores = faces.map { face ->
                            cosine(referenceEmbedding, embedding(interpreter, bitmap, face))
                        }
                        bitmap.recycle()
                        val best = scores.maxOrNull()
                        val label = if (best != null && best >= 0.60f) {
                            matched++
                            "POSSIBLE MATCH"
                        } else "no strong match"
                        report.append("${index + 1}. ${displayName(uri)}: ${faces.size} face(s)")
                        if (best != null) report.append(", best similarity ${"%.3f".format(java.util.Locale.US, best)} - $label")
                        report.append("\n")
                    } catch (e: Exception) {
                        report.append("${index + 1}. ${displayName(uri)}: error - ${e.localizedMessage ?: "unknown"}\n")
                    }
                    checked++
                    runOnUiThread {
                        progress.progress = checked
                        status.text = "Compared $checked of ${candidates.size} images…"
                    }
                }
                report.append("\nFinished: $matched possible match(es). Review each image yourself. The 0.60 threshold is only a starting point and must be calibrated for the exact model and photo conditions.")
                runOnUiThread { status.text = report.toString(); progress.visibility = ProgressBar.GONE; scanButton.isEnabled = true }
            } catch (e: Exception) {
                runOnUiThread {
                    status.text = "Comparison could not run: ${e.localizedMessage ?: "unknown error"}\n\nUse a verified MobileFaceNet-compatible float32 model with input [1,112,112,3] and output [1,N]."
                    progress.visibility = ProgressBar.GONE
                    scanButton.isEnabled = true
                }
            } finally {
                faceDetector?.close()
                interpreter?.close()
            }
        }
    }

    private fun embedding(interpreter: Interpreter, bitmap: Bitmap, face: Face): FloatArray {
        val bounds = face.boundingBox
        val side = maxOf(bounds.width(), bounds.height()).toFloat() * 1.25f
        val cx = bounds.centerX().toFloat()
        val cy = bounds.centerY().toFloat()
        val left = (cx - side / 2f).toInt().coerceIn(0, bitmap.width - 1)
        val top = (cy - side / 2f).toInt().coerceIn(0, bitmap.height - 1)
        val right = (cx + side / 2f).toInt().coerceIn(left + 1, bitmap.width)
        val bottom = (cy + side / 2f).toInt().coerceIn(top + 1, bitmap.height)
        val crop = Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
        val scaled = Bitmap.createScaledBitmap(crop, 112, 112, true)
        if (crop !== bitmap) crop.recycle()
        val input = Array(1) { Array(112) { Array(112) { FloatArray(3) } } }
        for (y in 0 until 112) for (x in 0 until 112) {
            val pixel = scaled.getPixel(x, y)
            input[0][y][x][0] = (((pixel shr 16) and 255) / 127.5f) - 1f
            input[0][y][x][1] = (((pixel shr 8) and 255) / 127.5f) - 1f
            input[0][y][x][2] = ((pixel and 255) / 127.5f) - 1f
        }
        scaled.recycle()
        val output = Array(1) { FloatArray(interpreter.getOutputTensor(0).shape()[1]) }
        interpreter.run(input, output)
        return normalize(output[0])
    }

    private fun normalize(vector: FloatArray): FloatArray {
        val norm = sqrt(vector.sumOf { (it * it).toDouble() }).toFloat().coerceAtLeast(1e-12f)
        return FloatArray(vector.size) { vector[it] / norm }
    }

    private fun cosine(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return -1f
        return a.indices.sumOf { (a[it] * b[it]).toDouble() }.toFloat()
    }

    private fun decode(uri: Uri): Bitmap {
        val source = ImageDecoder.createSource(contentResolver, uri)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val scale = minOf(1f, 2000f / maxOf(info.size.width, info.size.height))
            decoder.setTargetSize((info.size.width * scale).toInt().coerceAtLeast(1), (info.size.height * scale).toInt().coerceAtLeast(1))
        }
    }

    private fun displayName(uri: Uri): String = try {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else uri.lastPathSegment ?: "Image"
        } ?: "Image"
    } catch (_: Exception) { "Image" }

    override fun onDestroy() {
        executor.shutdown()
        super.onDestroy()
    }
}
