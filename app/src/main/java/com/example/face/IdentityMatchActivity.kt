package com.example.face

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.content.Intent
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
import com.google.mlkit.vision.face.FaceLandmark
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
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream
import java.io.BufferedInputStream
import kotlin.math.sqrt
import kotlin.math.acos

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
    private val modelUrl = "https://qaihub-public-assets.s3.us-west-2.amazonaws.com/qai-hub-models/models/mobile_facenet/releases/v0.65.0/mobile_facenet-tflite-float.zip"
    private val modelTargetName = "qualcomm_mobilefacenet.tflite"

    private val modelPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                val target = File(filesDir, "face_embedding_model.tflite")
                contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(target).use { output -> input.copyTo(output) }
                } ?: throw IllegalStateException("Could not read the selected model.")
                validateMobileFaceNetModel(target)
                modelFile = target
                status.text = "Compatible MobileFaceNet model selected (${target.length() / 1024} KB). Choose your reference photo and candidate images."
                updateReady()
            } catch (e: Exception) {
                modelFile = null
                status.text = "Model import failed: ${e.localizedMessage ?: "unknown error"}"
                updateReady()
            }
        }
    }
    private val referencePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { referenceUri = uri; status.text = "Reference photo selected."; updateReady() }
    }
    private val imagesPicker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) { addCandidateUris(uris); status.text = "${imageUris.size} candidate image(s) ready."; updateReady() }
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
            text = "Downloads Qualcomm AI Hub MobileFaceNet directly from its public model release. The model runs on this phone. Select one clear reference photo and candidate images. The model needs two aligned face images per comparison; images are not uploaded."
            textSize = 14f
            setPadding(0, 0, 0, 16)
        })
        val existingModel = File(filesDir, modelTargetName)
        if (existingModel.exists()) modelFile = existingModel
        addButton(root, "Download official MobileFaceNet model (~4 MB)") { downloadOfficialModel() }
        addButton(root, "Choose compatible TFLite model") { modelPicker.launch(arrayOf("*/*")) }
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
        handleSharedImages(intent)
        updateReady()
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleSharedImages(intent)
    }

    private fun handleSharedImages(incoming: android.content.Intent?) {
        if (incoming == null) return
        val shared = mutableListOf<Uri>()
        when (incoming.action) {
            Intent.ACTION_SEND -> {
                val uri = if (android.os.Build.VERSION.SDK_INT >= 33) {
                    incoming.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    incoming.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
                }
                if (uri != null) shared.add(uri)
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val items = if (android.os.Build.VERSION.SDK_INT >= 33) {
                    incoming.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    incoming.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                }
                if (items != null) shared.addAll(items)
            }
        }
        val clip = incoming.clipData
        if (clip != null) {
            for (i in 0 until clip.itemCount) {
                clip.getItemAt(i).uri?.let { shared.add(it) }
            }
        }
        if (shared.isNotEmpty()) {
            addCandidateUris(shared)
            status.text = "Received ${shared.distinct().size} shared image(s). Choose your model and reference photo, then compare."
            updateReady()
        }
    }

    private fun addCandidateUris(uris: List<Uri>) {
        imageUris = (imageUris + uris).distinct()
    }

    private fun downloadOfficialModel() {
        if (modelFile?.exists() == true) {
            status.text = "MobileFaceNet is already installed on this phone."
            updateReady()
            return
        }
        status.text = "Downloading official MobileFaceNet model…"
        scanButton.isEnabled = false
        executor.execute {
            var connection: HttpURLConnection? = null
            val target = File(filesDir, modelTargetName)
            val temp = File(filesDir, "$modelTargetName.tmp")
            try {
                connection = (URL(modelUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 20000
                    readTimeout = 60000
                    instanceFollowRedirects = true
                    requestMethod = "GET"
                }
                connection.connect()
                require(connection.responseCode in 200..299) { "Model download returned HTTP ${connection.responseCode}." }
                var extracted = false
                ZipInputStream(BufferedInputStream(connection.inputStream)).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        if (!entry.isDirectory && entry.name.lowercase().endsWith(".tflite")) {
                            var total = 0L
                            FileOutputStream(temp).use { output ->
                                val buffer = ByteArray(16 * 1024)
                                while (true) {
                                    val count = zip.read(buffer)
                                    if (count < 0) break
                                    total += count
                                    require(total <= 30L * 1024L * 1024L) { "Model file is unexpectedly large." }
                                    output.write(buffer, 0, count)
                                }
                            }
                            require(total > 100_000L) { "The model file in the archive is empty or incomplete." }
                            extracted = true
                            break
                        }
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                }
                require(extracted) { "The official archive did not contain a TFLite model." }
                validateMobileFaceNetModel(temp)
                if (target.exists()) target.delete()
                check(temp.renameTo(target)) { "Could not save the model in app storage." }
                modelFile = target
                runOnUiThread {
                    status.text = "Official MobileFaceNet downloaded and validated. Choose your reference photo and candidate images."
                    updateReady()
                }
            } catch (e: Exception) {
                temp.delete()
                runOnUiThread {
                    status.text = "Model download failed: ${e.localizedMessage ?: "unknown error"}. You can retry when connected to the internet."
                    updateReady()
                }
            } finally {
                connection?.disconnect()
            }
        }
    }

    private fun validateMobileFaceNetModel(file: File) {
        val interpreter = Interpreter(file, Interpreter.Options().setNumThreads(2))
        try {
            require(interpreter.inputTensorCount == 2) {
                "This model must accept two face images for pairwise verification."
            }
            for (i in 0..1) {
                val shape = interpreter.getInputTensor(i).shape()
                val nhwc = shape.contentEquals(intArrayOf(1, 112, 112, 3))
                val nchw = shape.contentEquals(intArrayOf(1, 3, 112, 112))
                require(nhwc || nchw) { "Input $i has unsupported shape ${shape.contentToString()}." }
                require(interpreter.getInputTensor(i).dataType() == org.tensorflow.lite.DataType.FLOAT32) {
                    "Input $i must be float32."
                }
            }
            require(interpreter.outputTensorCount == 1) { "Expected one embedding output tensor." }
            val output = interpreter.getOutputTensor(0)
            val elements = output.shape().fold(1) { acc, dim -> acc * dim }
            require(elements == 256 && output.dataType() == org.tensorflow.lite.DataType.FLOAT32) {
                "Expected 256 float32 output values (two 128-value embeddings), got ${output.shape().contentToString()}."
            }
        } finally {
            interpreter.close()
        }
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
        status.text = "Preparing local MobileFaceNet matcher…"
        executor.execute {
            var interpreter: Interpreter? = null
            var faceDetector: FaceDetector? = null
            var referenceAligned: Bitmap? = null
            try {
                validateMobileFaceNetModel(model)
                interpreter = Interpreter(model, Interpreter.Options().setNumThreads(4))
                val options = FaceDetectorOptions.Builder()
                    .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                    .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                    .build()
                faceDetector = FaceDetection.getClient(options)

                val referenceBitmap = decode(ref)
                val referenceFaces = Tasks.await(faceDetector.process(InputImage.fromBitmap(referenceBitmap, 0)))
                val referenceFace = referenceFaces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
                    ?: throw IllegalArgumentException("No face was detected in the reference photo.")
                referenceAligned = alignFace(referenceBitmap, referenceFace)
                referenceBitmap.recycle()

                val report = StringBuilder("Reference face aligned for this scan session.\\n")
                var checked = 0
                var matched = 0
                candidates.forEachIndexed { index, uri ->
                    try {
                        val bitmap = decode(uri)
                        val faces = Tasks.await(faceDetector.process(InputImage.fromBitmap(bitmap, 0)))
                        var best: Float? = null
                        faces.forEach { face ->
                            val aligned = alignFace(bitmap, face)
                            val score = comparePair(interpreter, referenceAligned!!, aligned)
                            aligned.recycle()
                            if (best == null || score > best!!) best = score
                        }
                        bitmap.recycle()
                        val similarity = best
                        val angle = if (similarity != null) Math.toDegrees(acos(similarity.coerceIn(-1f, 1f).toDouble())) else null
                        val possible = angle != null && angle <= 74.18
                        if (possible) matched++
                        report.append("${index + 1}. ${displayName(uri)}: ${faces.size} face(s)")
                        if (similarity != null && angle != null) {
                            report.append(", cosine ${"%.3f".format(java.util.Locale.US, similarity)}, angle ${"%.1f".format(java.util.Locale.US, angle)}° - ")
                            report.append(if (possible) "POSSIBLE MATCH" else "lower similarity")
                        }
                        report.append("\\n")
                    } catch (e: Exception) {
                        report.append("${index + 1}. ${displayName(uri)}: error - ${e.localizedMessage ?: "unknown"}\\n")
                    }
                    checked++
                    runOnUiThread {
                        progress.progress = checked
                        status.text = "Compared $checked of ${candidates.size} images…"
                    }
                }
                report.append("\\nFinished: $matched possible match(es). Qualcomm's published 74.18° threshold is a benchmark starting point, not proof of identity. Review every candidate manually.")
                runOnUiThread { status.text = report.toString(); progress.visibility = ProgressBar.GONE; updateReady() }
            } catch (e: Exception) {
                runOnUiThread {
                    status.text = "Comparison could not run: ${e.localizedMessage ?: "unknown error"}"
                    progress.visibility = ProgressBar.GONE
                    updateReady()
                }
            } finally {
                referenceAligned?.recycle()
                faceDetector?.close()
                interpreter?.close()
            }
        }
    }

    private fun alignFace(bitmap: Bitmap, face: Face): Bitmap {
        val landmarkTypes = intArrayOf(
            FaceLandmark.LEFT_EYE,
            FaceLandmark.RIGHT_EYE,
            FaceLandmark.NOSE_BASE,
            FaceLandmark.MOUTH_LEFT,
            FaceLandmark.MOUTH_RIGHT
        )
        val src = landmarkTypes.map { type ->
            face.getLandmark(type)?.position
                ?: throw IllegalArgumentException("This face does not have all five landmarks needed for MobileFaceNet alignment.")
        }
        val dstX = doubleArrayOf(44.1964, 67.6879, 56.0168, 46.3662, 65.8199)
        val dstY = doubleArrayOf(53.1309, 53.0009, 66.4911, 80.2437, 80.1361)
        val meanX = src.map { it.x.toDouble() }.average()
        val meanY = src.map { it.y.toDouble() }.average()
        val meanU = dstX.average()
        val meanV = dstY.average()
        var numeratorA = 0.0
        var numeratorB = 0.0
        var denominator = 0.0
        for (i in src.indices) {
            val x = src[i].x - meanX
            val y = src[i].y - meanY
            val u = dstX[i] - meanU
            val v = dstY[i] - meanV
            numeratorA += x * u + y * v
            numeratorB += x * v - y * u
            denominator += x * x + y * y
        }
        require(denominator > 1e-6) { "Face landmarks are too close together to align." }
        val a = numeratorA / denominator
        val b = numeratorB / denominator
        val tx = meanU - a * meanX + b * meanY
        val ty = meanV - b * meanX - a * meanY
        val matrix = Matrix().apply {
            setValues(floatArrayOf(
                a.toFloat(), (-b).toFloat(), tx.toFloat(),
                b.toFloat(), a.toFloat(), ty.toFloat(),
                0f, 0f, 1f
            ))
        }
        val aligned = Bitmap.createBitmap(112, 112, Bitmap.Config.ARGB_8888)
        Canvas(aligned).drawBitmap(bitmap, matrix, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        return aligned
    }

    private fun comparePair(interpreter: Interpreter, first: Bitmap, second: Bitmap): Float {
        val inputShape0 = interpreter.getInputTensor(0).shape()
        val inputShape1 = interpreter.getInputTensor(1).shape()
        val inputs = arrayOf(makeInput(first, inputShape0), makeInput(second, inputShape1))
        val outputShape = interpreter.getOutputTensor(0).shape()
        val output = ByteBuffer.allocateDirect(outputShape.fold(1) { acc, dim -> acc * dim } * 4)
            .order(ByteOrder.nativeOrder())
        interpreter.runForMultipleInputsOutputs(inputs, mutableMapOf(0 to output))
        output.rewind()
        val firstEmbedding = FloatArray(128) { output.float }
        val secondEmbedding = FloatArray(128) { output.float }
        val a = normalize(firstEmbedding)
        val b = normalize(secondEmbedding)
        return a.indices.sumOf { (a[it] * b[it]).toDouble() }.toFloat().coerceIn(-1f, 1f)
    }

    private fun makeInput(bitmap: Bitmap, shape: IntArray): ByteBuffer {
        val nchw = shape.contentEquals(intArrayOf(1, 3, 112, 112))
        val buffer = ByteBuffer.allocateDirect(112 * 112 * 3 * 4).order(ByteOrder.nativeOrder())
        if (nchw) {
            for (channel in 0..2) for (y in 0 until 112) for (x in 0 until 112) {
                val pixel = bitmap.getPixel(x, y)
                val value = when (channel) {
                    0 -> (pixel shr 16) and 255
                    1 -> (pixel shr 8) and 255
                    else -> pixel and 255
                }
                buffer.putFloat(value / 255f)
            }
        } else {
            for (y in 0 until 112) for (x in 0 until 112) {
                val pixel = bitmap.getPixel(x, y)
                buffer.putFloat(((pixel shr 16) and 255) / 255f)
                buffer.putFloat(((pixel shr 8) and 255) / 255f)
                buffer.putFloat((pixel and 255) / 255f)
            }
        }
        buffer.rewind()
        return buffer
    }

    private fun normalize(vector: FloatArray): FloatArray {
        val norm = sqrt(vector.sumOf { (it * it).toDouble() }).toFloat().coerceAtLeast(1e-12f)
        return FloatArray(vector.size) { vector[it] / norm }
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
