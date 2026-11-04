package com.example.face

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.content.Intent
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.ImageView
import android.widget.ScrollView
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
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.zip.ZipInputStream
import java.io.BufferedInputStream
import kotlin.math.sqrt
import kotlin.math.acos

/**
 * Local pairwise face verification using Qualcomm AI Hub MobileFaceNet.
 * The model accepts two aligned RGB faces, applies its own torchvision
 * normalization and test-time augmentation, and returns two 128-value vectors.
 * The app aligns five ML Kit landmarks to the model reference geometry.
 */
class IdentityMatchActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var scanButton: MaterialButton
    private lateinit var resultsContainer: LinearLayout
    private var modelFile: File? = null
    private var referenceUri: Uri? = null
    private var referenceUris: List<Uri> = emptyList()
    private var webScanMode = false
    private var imageUris: List<Uri> = emptyList()
    private val executor = Executors.newSingleThreadExecutor()
    private val modelUrl = "https://qaihub-public-assets.s3.us-west-2.amazonaws.com/qai-hub-models/models/mobile_facenet/releases/v0.65.0/mobile_facenet-tflite-float.zip"
    private val modelTargetName = "qualcomm_mobilefacenet.tflite"

    private val modelPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importModel(uri)
    }
    private val referencePicker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            referenceUris = uris.distinct()
            referenceUris.forEach { uri ->
                try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
            }
            referenceUri = referenceUris.first()
            status.text = "${referenceUris.size} reference photo(s) selected."
            updateReady()
        }
    }
    private val imagesPicker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) { addCandidateUris(uris); status.text = "${imageUris.size} candidate image(s) ready."; updateReady() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(24))
        }
        setToolPage("Face matching", root)
        webScanMode = intent.getBooleanExtra(EXTRA_WEB_SCAN_MODE, false)
        @Suppress("DEPRECATION")
        referenceUris = intent.getParcelableArrayListExtra<Uri>(EXTRA_REFERENCE_URIS)?.distinct().orEmpty()
        referenceUri = referenceUris.firstOrNull()
        root.addView(TextView(this).apply {
            text = if (webScanMode) {
                "Your selected reference photos are compared with public image links collected by Image Search. Candidate images are retrieved temporarily in memory, analyzed on this phone, and not saved to storage or uploaded. Only public HTTPS image links are attempted."
            } else {
                "Downloads Qualcomm AI Hub MobileFaceNet from its public release (Apache 2.0 model weights). It runs locally on this phone. Select reference photo(s) and candidate images. Photos are aligned on-device; no images are uploaded."
            }
            textSize = 14f
            setPadding(0, 0, 0, 16)
        })
        val existingModel = File(filesDir, modelTargetName)
        if (existingModel.exists()) modelFile = existingModel
        addButton(root, "Download official MobileFaceNet model (~4 MB)") { downloadOfficialModel() }
        addButton(root, "Choose compatible TFLite model") { modelPicker.launch(arrayOf("*/*")) }
        addButton(root, "Choose reference photo(s)") { referencePicker.launch(arrayOf("image/*")) }
        if (!webScanMode) addButton(root, "Choose candidate images") { imagesPicker.launch(arrayOf("image/*")) }
        scanButton = MaterialButton(this).apply {
            text = if (webScanMode) "Match against collected website images" else "Compare faces on this phone"
            isAllCaps = false
            isEnabled = false
            setOnClickListener { runComparison() }
        }
        root.addSpaced(scanButton)
        progress =ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { visibility = ProgressBar.GONE }
        root.addView(progress)
        status = TextView(this).apply {
            text = if (webScanMode) "Install the model and choose reference photo(s)." else "No model or images selected."
            textSize = 14f
            setPadding(0, 16, 0, 0)
        }
        root.addView(status)
        val resultsScroll = ScrollView(this).apply { layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f); isFillViewport = true }
        resultsContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        resultsScroll.addView(resultsContainer)
        root.addView(resultsScroll)
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

    /**
     * Copies a user-chosen model into app storage and checks it off the main thread.
     * The file is validated before it replaces anything, so a bad pick never
     * destroys a model that already works.
     */
    private fun importModel(uri: Uri) {
        status.text = "Checking the selected model…"
        scanButton.isEnabled = false
        executor.execute {
            val temp = File(filesDir, "$modelTargetName.import")
            try {
                var total = 0L
                contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(temp).use { output ->
                        val buffer = ByteArray(16 * 1024)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            require(total <= 30L * 1024L * 1024L) { "Model file is unexpectedly large." }
                            output.write(buffer, 0, count)
                        }
                    }
                } ?: throw IllegalStateException("Could not read the selected model.")
                require(total > 100_000L) { "The selected file is empty or too small to be this model." }
                validateMobileFaceNetModel(temp)
                val target = File(filesDir, modelTargetName)
                if (target.exists()) target.delete()
                check(temp.renameTo(target)) { "Could not save the model in app storage." }
                modelFile = target
                runOnUiThread {
                    status.text = "Compatible MobileFaceNet model selected (${target.length() / 1024} KB). Choose your reference photo and candidate images."
                    updateReady()
                }
            } catch (e: Exception) {
                temp.delete()
                runOnUiThread {
                    status.text = "Model import failed: ${e.localizedMessage ?: "unknown error"}. Any model you installed before was kept."
                    updateReady()
                }
            }
        }
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
        root.addSpaced(MaterialButton(this).apply { text = label; isAllCaps = false; setOnClickListener { action() } })
    }

    private fun updateReady() {
        scanButton.isEnabled = modelFile?.exists() == true && referenceUris.isNotEmpty() &&
            (webScanMode || imageUris.isNotEmpty())
    }

    private fun runComparison() {
        if (webScanMode) {
            runWebComparison()
            return
        }
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
            val resultsDatabase = PublicSourceDatabase(this)
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

                val referenceNote = if (referenceFaces.size > 1) {
                    "The reference photo has ${referenceFaces.size} faces, so the largest one was used.\n"
                } else {
                    ""
                }
                val report = StringBuilder("Reference face aligned for this scan session.\n$referenceNote")
                var checked = 0
                var matched = 0
                candidates.forEachIndexed { index, uri ->
                    try {
                        val bitmap = decode(uri)
                        var best: Float? = null
                        var skipped = 0
                        val faces = try {
                            val detected = Tasks.await(faceDetector.process(InputImage.fromBitmap(bitmap, 0)))
                            detected.forEach { face ->
                                try {
                                    val aligned = alignFace(bitmap, face)
                                    try {
                                        val score = comparePair(interpreter, referenceAligned!!, aligned)
                                        if (best == null || score > best!!) best = score
                                    } finally {
                                        aligned.recycle()
                                    }
                                } catch (e: IllegalArgumentException) {
                                    // A face seen from the side may lack the landmarks needed to align it.
                                    skipped++
                                }
                            }
                            detected
                        } finally {
                            bitmap.recycle()
                        }
                        val similarity = best
                        val angle = if (similarity != null) Math.toDegrees(acos(similarity.coerceIn(-1f, 1f).toDouble())) else null
                        val possible = angle != null && angle <= 74.18
                        if (possible) matched++
                        report.append("${index + 1}. ${displayName(uri)}: ${faces.size} face(s)")
                        if (skipped > 0) report.append(" ($skipped skipped, landmarks not clear)")
                        if (similarity != null && angle != null) {
                            report.append(", cosine ${"%.3f".format(java.util.Locale.US, similarity)}, angle ${"%.1f".format(java.util.Locale.US, angle)}° - ")
                            report.append(if (possible) "POSSIBLE MATCH" else "lower similarity")
                        }
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
                report.append("\nFinished: $matched possible match(es). Qualcomm's published 74.18° threshold is a benchmark starting point, not proof of identity. Review every candidate manually.")
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
                resultsDatabase.close()
            }
        }
    }

    /**
     * Compares selected reference photos with public image URLs already collected
     * by the sitemap/feed scanner. Candidate bytes stay in memory and are never saved.
     */
    private fun runWebComparison() {
        val model = modelFile ?: return
        val references = referenceUris.toList()
        if (references.isEmpty()) return
        val candidateDatabase = PublicSourceDatabase(this)
        val candidates = try { candidateDatabase.allCandidates(500) } finally { candidateDatabase.close() }
        if (candidates.isEmpty()) {
            status.text = "No public image links are collected yet. Return to Image Search and run Scan all websites first."
            return
        }
        scanButton.isEnabled = false
        progress.visibility = ProgressBar.VISIBLE
        progress.max = candidates.size
        progress.progress = 0
        resultsContainer.removeAllViews()
        status.text = "Preparing on-device face matcher…"
        executor.execute {
            var interpreter: Interpreter? = null
            var detector: FaceDetector? = null
            val alignedReferences = mutableListOf<Bitmap>()
            try {
                validateMobileFaceNetModel(model)
                val activeInterpreter = Interpreter(model, Interpreter.Options().setNumThreads(4))
                interpreter = activeInterpreter
                val activeDetector = FaceDetection.getClient(
                    FaceDetectorOptions.Builder()
                        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                        .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                        .build()
                )
                detector = activeDetector
                references.forEachIndexed { index, uri ->
                    val bitmap = decode(uri)
                    try {
                        val faces = Tasks.await(activeDetector.process(InputImage.fromBitmap(bitmap, 0)))
                        val face = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
                            ?: throw IllegalArgumentException("No face found in reference photo ${index + 1}.")
                        alignedReferences += alignFace(bitmap, face)
                    } finally {
                        bitmap.recycle()
                    }
                }
                val report = StringBuilder("Using ${alignedReferences.size} reference photo(s). Candidate image files are processed in memory only.\n\n")
                var checked = 0
                var possibleMatches = 0
                candidates.forEachIndexed { index, candidate ->
                    try {
                        val bitmap = downloadPublicImageInMemory(candidate.imageUrl)
                        try {
                            val faces = Tasks.await(activeDetector.process(InputImage.fromBitmap(bitmap, 0)))
                            var best: Float? = null
                            var skipped = 0
                            for (face in faces) {
                                var aligned: Bitmap? = null
                                try {
                                    aligned = alignFace(bitmap, face)
                                    for (reference in alignedReferences) {
                                        val score = comparePair(activeInterpreter, reference, aligned)
                                        if (best == null || score > best!!) best = score
                                    }
                                } catch (_: IllegalArgumentException) {
                                    skipped++
                                } finally {
                                    aligned?.recycle()
                                }
                            }
                            val similarity = best
                            val angle = similarity?.let {
                                Math.toDegrees(acos(it.coerceIn(-1f, 1f).toDouble()))
                            }
                            val possible = angle != null && angle <= 74.18
                            if (possible) {
                                possibleMatches++
                                val preview = Bitmap.createScaledBitmap(bitmap, 220, (220f * bitmap.height / bitmap.width).toInt().coerceAtLeast(1), true)
                                runOnUiThread { addWebMatchPreview(candidate.imageUrl, similarity ?: 0f, preview) }
                            }
                            report.append("${index + 1}. ${Uri.parse(candidate.imageUrl).host}: ${faces.size} face(s)")
                            if (skipped > 0) report.append(" ($skipped could not be aligned)")
                            if (similarity != null && angle != null) {
                                report.append(", cosine ${"%.3f".format(java.util.Locale.US, similarity)}, angle ${"%.1f".format(java.util.Locale.US, angle)}° - ")
                                report.append(if (possible) "POSSIBLE MATCH" else "lower similarity")
                            }
                            report.append("\n${candidate.imageUrl}\n")
                        } finally {
                            bitmap.recycle()
                        }
                    } catch (e: Exception) {
                        report.append("${index + 1}. ${Uri.parse(candidate.imageUrl).host ?: "website"}: skipped - ${e.localizedMessage ?: "image unavailable"}\n")
                    }
                    checked++
                    val current = checked
                    runOnUiThread {
                        progress.progress = current
                        status.text = "Checked $current of ${candidates.size} collected image links…"
                    }
                }
                report.append("\nFinished: $possibleMatches possible match(es). This is only a screening result, not proof of identity. Open and review any candidate manually.")
                runOnUiThread {
                    status.text = report.toString()
                    progress.visibility = ProgressBar.GONE
                    updateReady()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    status.text = "Website image matching could not run: ${e.localizedMessage ?: "unknown error"}"
                    progress.visibility = ProgressBar.GONE
                    updateReady()
                }
            } finally {
                alignedReferences.forEach { it.recycle() }
                detector?.close()
                interpreter?.close()
            }
        }
    }

    private fun addWebMatchPreview(imageUrl: String, similarity: Float, preview: Bitmap) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFF202832.toInt())
                cornerRadius = dp(12).toFloat()
            }
        }
        card.addView(ImageView(this).apply {
            setImageBitmap(preview)
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = LinearLayout.LayoutParams(dp(104), dp(104))
        })
        val details = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        details.addView(TextView(this).apply { text = "Possible match"; textSize = 16f })
        details.addView(TextView(this).apply {
            text = "Cosine similarity: " + String.format(java.util.Locale.US, "%.3f", similarity)
            textSize = 14f
        })
        details.addView(TextView(this).apply { text = Uri.parse(imageUrl).host ?: imageUrl; textSize = 12f })
        details.addView(MaterialButton(this).apply {
            text = "Open source"
            isAllCaps = false
            setOnClickListener {
                try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(imageUrl))) } catch (_: Exception) {}
            }
        })
        card.addView(details)
        resultsContainer.addView(card, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(10) })
    }

    /** Fetches a public HTTPS image into a bounded byte array; never writes it to disk. */
    private fun downloadPublicImageInMemory(address: String): Bitmap {
        val uri = Uri.parse(address)
        require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null) {
            "Only public HTTPS image URLs are allowed."
        }
        val host = uri.host!!.lowercase(java.util.Locale.US)
        require(host != "localhost" && !host.endsWith(".localhost") && !host.endsWith(".local")) {
            "Local network addresses are not allowed."
        }
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(address).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 12000
                instanceFollowRedirects = false
                requestMethod = "GET"
                setRequestProperty("User-Agent", "FacePersonalScanner/1.0")
                setRequestProperty("Accept", "image/*")
            }
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
            val data = bytes.toByteArray()
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
            require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Image could not be decoded." }
            var sample = 1
            while (maxOf(bounds.outWidth / sample, bounds.outHeight / sample) > 2000) sample *= 2
            val options = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            return BitmapFactory.decodeByteArray(data, 0, data.size, options)
                ?: throw IllegalArgumentException("Image could not be decoded.")
        } finally {
            connection?.disconnect()
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
        val dstX = doubleArrayOf(38.2946, 73.5318, 56.0252, 41.5493, 70.7299)
        val dstY = doubleArrayOf(51.6963, 51.5014, 71.7366, 92.3655, 92.2041)
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
        interpreter.runForMultipleInputsOutputs(inputs, mutableMapOf<Int, Any>(0 to output))
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
                buffer.putFloat(value / 127.5f - 1f)
            }
        } else {
            for (y in 0 until 112) for (x in 0 until 112) {
                val pixel = bitmap.getPixel(x, y)
                buffer.putFloat(((pixel shr 16) and 255) / 127.5f - 1f)
                buffer.putFloat(((pixel shr 8) and 255) / 127.5f - 1f)
                buffer.putFloat((pixel and 255) / 127.5f - 1f)
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
        val source = if (uri.scheme == "file") {
            ImageDecoder.createSource(File(uri.path ?: throw IllegalArgumentException("Missing image path.")))
        } else {
            ImageDecoder.createSource(contentResolver, uri)
        }
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val scale = minOf(1f, 2000f / maxOf(info.size.width, info.size.height))
            decoder.setTargetSize((info.size.width * scale).toInt().coerceAtLeast(1), (info.size.height * scale).toInt().coerceAtLeast(1))
        }
    }

    private fun displayName(uri: Uri): String {
        if (uri.scheme == "file") return File(uri.path ?: "").name
        return try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else uri.lastPathSegment ?: "Image"
            } ?: "Image"
        } catch (_: Exception) {
            "Image"
        }
    }

    override fun onDestroy() {
        executor.shutdown()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_WEB_SCAN_MODE = "com.example.face.EXTRA_WEB_SCAN_MODE"
        const val EXTRA_REFERENCE_URIS = "com.example.face.EXTRA_REFERENCE_URIS"
    }
}
