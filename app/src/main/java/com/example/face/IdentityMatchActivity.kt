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
 * The model accepts two aligned RGB face crops and returns two 128-value embeddings.
 * The app aligns five ML Kit landmarks and prepares normalized RGB tensors locally.
 */
class IdentityMatchActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var scanButton: MaterialButton
    private lateinit var cancelButton: MaterialButton
    private lateinit var resultsContainer: LinearLayout
    private var activeWebScan: java.util.concurrent.Future<*>? = null
    private var modelFile: File? = null
    private var referenceUri: Uri? = null
    private var referenceUris: List<Uri> = emptyList()
    private var webScanMode = false
    private var autoStartScan = false
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

    private val exportResultsPicker = registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) exportSavedMatches(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(28))
            setBackgroundColor(android.graphics.Color.rgb(13, 16, 24))
        }
        setToolPage("Deep search", root)
        root.addView(TextView(this).apply {
            text = "FACE SEARCH  /  LIVE SCAN"
            textSize = 11f
            letterSpacing = 0.12f
            setTextColor(android.graphics.Color.rgb(155, 139, 255))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(8), 0, dp(8))
        })
        root.addView(TextView(this).apply {
            text = "Searching for\nyour face."
            textSize = 29f
            setTextColor(android.graphics.Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, dp(14))
        })
        webScanMode = intent.getBooleanExtra(EXTRA_WEB_SCAN_MODE, false)
        autoStartScan = intent.getBooleanExtra(EXTRA_AUTO_START, false)
        @Suppress("DEPRECATION")
        referenceUris = intent.getParcelableArrayListExtra<Uri>(EXTRA_REFERENCE_URIS)?.distinct().orEmpty()
        referenceUri = referenceUris.firstOrNull()
        root.addView(TextView(this).apply {
            text = if (webScanMode) {
                "Your selected reference photos stay on this device. The scan checks supported public sources and compares accessible images locally. Coverage depends on what each source makes publicly available."
            } else {
                "The face matcher runs on this phone. Select your reference photos and candidate images to compare them privately, without uploading your photos."
            }
            textSize = 14f
            setTextColor(android.graphics.Color.rgb(190, 195, 210))
            setLineSpacing(dp(3).toFloat(), 1.04f)
            setPadding(0, 0, 0, dp(16))
        })
        val existingModel = File(filesDir, modelTargetName)
        if (existingModel.exists()) modelFile = existingModel
        addButton(root, "Install face-matching model  ·  ~4 MB") { downloadOfficialModel() }
        addButton(root, "Choose a compatible face model") { modelPicker.launch(arrayOf("*/*")) }
        addButton(root, "Choose reference photos") { referencePicker.launch(arrayOf("image/*")) }
        if (webScanMode) addButton(root, "Export saved matches (CSV)") { exportResultsPicker.launch("face-search-results.csv") }
        if (!webScanMode) addButton(root, "Choose candidate images") { imagesPicker.launch(arrayOf("image/*")) }
        scanButton = MaterialButton(this).apply {
            text = if (webScanMode) "Start deep search" else "Compare selected photos"
            isAllCaps = false
            isEnabled = false
            setOnClickListener { runComparison() }
        }
        root.addSpaced(scanButton)
        progress =ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { visibility = ProgressBar.GONE }
        root.addView(progress)
        cancelButton = MaterialButton(this).apply {
            text = "Cancel search"
            isAllCaps = false
            visibility = android.view.View.GONE
            setOnClickListener {
                activeWebScan?.cancel(true)
                status.text = "Cancelling search…"
            }
        }
        root.addView(cancelButton)
        status = TextView(this).apply {
            text = if (webScanMode) "Install the model and choose reference photo(s)." else "No model or images selected."
            textSize = 14f
            setPadding(0, 16, 0, 0)
        }
        root.addView(status)
        resultsContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val resultSortRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, dp(6))
        }
        resultSortRow.addView(MaterialButton(this).apply {
            text = "Best matches"
            isAllCaps = false
            setOnClickListener { resultsContainer.removeAllViews(); restoreSavedMatches(bestFirst = true) }
        }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(5) })
        resultSortRow.addView(MaterialButton(this).apply {
            text = "Most recent"
            isAllCaps = false
            setOnClickListener { resultsContainer.removeAllViews(); restoreSavedMatches(bestFirst = false) }
        }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(5) })
        if (webScanMode) root.addView(resultSortRow)
        val resultsScroll = ScrollView(this).apply { layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f); isFillViewport = true }
        resultsScroll.addView(resultsContainer)
        root.addView(resultsScroll)
        handleSharedImages(intent)
        updateReady()
        if (webScanMode) restoreSavedMatches()
        if (autoStartScan && webScanMode) {
            if (modelFile?.exists() == true && referenceUris.isNotEmpty()) {
                runComparison()
            } else {
                status.text = "Install the face model once. The automatic website scan and matching will start after it is ready."
            }
        }
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
                    if (autoStartScan && webScanMode) runComparison()
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
                    if (autoStartScan && webScanMode) runComparison()
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
        val refs = referenceUris.ifEmpty { listOfNotNull(referenceUri) }
        if (refs.isEmpty()) return
        val candidates = imageUris.toList()
        scanButton.isEnabled = false
        progress.visibility = ProgressBar.VISIBLE
        progress.max = candidates.size
        progress.progress = 0
        status.text = "Preparing local MobileFaceNet matcher…"
        executor.execute {
            var interpreter: Interpreter? = null
            var faceDetector: FaceDetector? = null
            val alignedReferences = mutableListOf<Bitmap>()
            val resultsDatabase = PublicSourceDatabase(this)
            try {
                validateMobileFaceNetModel(model)
                interpreter = Interpreter(model, Interpreter.Options().setNumThreads(4))
                val options = FaceDetectorOptions.Builder()
                    .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                    .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                    .build()
                faceDetector = FaceDetection.getClient(options)

                var totalReferenceFaces = 0
                refs.forEachIndexed { refIndex, refUri ->
                    val referenceBitmap = decode(refUri)
                    try {
                        val detected = Tasks.await(faceDetector.process(InputImage.fromBitmap(referenceBitmap, 0)))
                        val referenceFace = detected.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
                            ?: throw IllegalArgumentException("No face was detected in reference photo ${refIndex + 1}.")
                        alignedReferences += alignFace(referenceBitmap, referenceFace)
                        totalReferenceFaces += detected.size
                    } finally {
                        referenceBitmap.recycle()
                    }
                }
                val referenceNote = if (totalReferenceFaces > refs.size) {
                    "Some reference photos contained multiple faces; the largest face in each was used.\n"
                } else {
                    ""
                }
                val referenceEmbeddings = alignedReferences.map { embedFace(interpreter, it) }
                val localMatches = mutableListOf<String>()
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
                                        val candidateEmbedding = embedFace(interpreter, aligned)
                                        val score = combineReferenceScores(
                                            referenceEmbeddings.map { reference -> cosineSimilarity(reference, candidateEmbedding) }
                                        )
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
                        val possible = similarity != null && similarity >= 0.4f
                        if (possible) {
                            matched++
                            localMatches += "${displayName(uri)} · cosine ${"%.3f".format(java.util.Locale.US, similarity)}"
                        }
                    } catch (_: Exception) {
                        // Keep the result view focused on successful possible matches.
                    }
                    checked++
                    if (checked % 10 == 0 || checked == candidates.size) {
                        runOnUiThread {
                            progress.progress = checked
                            status.text = "Compared $checked of ${candidates.size} images…"
                        }
                    }
                }
                val summary = buildString {
                    append("Finished: checked $checked of ${candidates.size} images and found $matched possible match(es). ")
                    append("The 0.4 cosine threshold was applied. Review possible matches manually.")
                    if (localMatches.isNotEmpty()) append("\n\nPossible matches:\n" + localMatches.take(50).joinToString("\n"))
                }
                runOnUiThread { status.text = summary; progress.visibility = ProgressBar.GONE; updateReady() }
            } catch (e: Exception) {
                runOnUiThread {
                    status.text = "Comparison could not run: ${e.localizedMessage ?: "unknown error"}"
                    progress.visibility = ProgressBar.GONE
                    updateReady()
                }
            } finally {
                alignedReferences.forEach { it.recycle() }
                faceDetector?.close()
                interpreter?.close()
                resultsDatabase.close()
            }
        }
    }

    /**
     * One automatic workflow: scan every enabled catalogue source, then compare
     * all collected public image candidates against every selected reference photo.
     * Source discovery and image matching both remain visible on this page.
     */
    private fun runWebComparison() {
        val model = modelFile ?: return
        val references = referenceUris.toList()
        if (references.isEmpty()) return
        scanButton.isEnabled = false
        progress.visibility = ProgressBar.VISIBLE
        progress.progress = 0
        status.text = "Preparing on-device face matching…"
        cancelButton.visibility = android.view.View.VISIBLE
        activeWebScan = executor.submit {
            var interpreter: Interpreter? = null
            var detector: FaceDetector? = null
            var downloadPool: java.util.concurrent.ExecutorService? = null
            val alignedReferences = mutableListOf<Bitmap>()
            val candidateDatabase = PublicSourceDatabase(this)
            try {
                val enabledSources = candidateDatabase.sources().filter { it.enabled }
                require(enabledSources.isNotEmpty()) { "There are no enabled public sources in the catalogue." }
                // Avoid re-crawling the same domains on every run. A source becomes eligible again after 24 hours.
                val rescanBefore = System.currentTimeMillis() - 24L * 60L * 60L * 1000L
                val sources = enabledSources.filter { it.lastScan == 0L || it.lastScan < rescanBefore }

                validateMobileFaceNetModel(model)
                val activeInterpreter = Interpreter(model, Interpreter.Options().setNumThreads(4))
                interpreter = activeInterpreter
                val activeDetector = FaceDetection.getClient(
                    FaceDetectorOptions.Builder()
                        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
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

                // Cache each reference embedding once. Each candidate face is embedded once,
                // then compared in memory against all references.
                val referenceEmbeddings = alignedReferences.map { embedFace(activeInterpreter, it) }

                var completedSources = 0
                var checked = 0
                var possibleMatches = 0
                var addedTotal = 0
                val processedIds = mutableSetOf<Long>()
                val report = StringBuilder()
                progress.max = 100
                progress.progress = 0

                // The bar and displayed percentage both represent the same metric:
                // completed public sources divided by the total enabled sources.
                // Image comparisons remain a separate count because the total number
                // of images is not known until each source has been scanned.
                fun updateSearchProgress(detail: String) {
                    val percent = if (sources.isEmpty()) 100 else ((completedSources * 100f) / sources.size)
                        .toInt().coerceIn(0, 100)
                    runOnUiThread {
                        progress.max = 100
                        progress.progress = percent
                        status.text = "Source scan: $percent% · $detail"
                    }
                }

                // Download up to six public images concurrently, but keep ML Kit detection and
                // TFLite inference on this single scan worker to avoid concurrent model access.
                val activeDownloadPool = Executors.newFixedThreadPool(6)
                downloadPool = activeDownloadPool
                fun comparePendingCandidates() {
                    val batch = candidateDatabase.pendingCandidates(5000)
                        .filter { processedIds.add(it.id) }
                    if (batch.isEmpty()) return
                    data class Downloaded(val candidate: StoredCandidate, val bitmap: Bitmap?, val error: String?)
                    data class MatchPreview(val id: Long, val pageUrl: String, val imageUrl: String, val score: Float, val bitmap: Bitmap)
                    val completion = java.util.concurrent.ExecutorCompletionService<Downloaded>(activeDownloadPool)
                    for (chunk in batch.chunked(24)) {
                        val futures = chunk.map { candidate ->
                            completion.submit(java.util.concurrent.Callable {
                                try { Downloaded(candidate, downloadPublicImageInMemory(candidate.imageUrl), null) }
                                catch (e: Exception) { Downloaded(candidate, null, e.localizedMessage ?: "image unavailable") }
                            })
                        }
                        val updates = mutableListOf<CandidateUpdate>()
                        val previews = mutableListOf<MatchPreview>()
                        repeat(futures.size) {
                            val downloaded = completion.take().get()
                            val candidate = downloaded.candidate
                            val bitmap = downloaded.bitmap
                            if (bitmap == null) {
                                updates += CandidateUpdate(candidate.id, "Unavailable", null)
                            } else {
                                try {
                                    val faces = Tasks.await(activeDetector.process(InputImage.fromBitmap(bitmap, 0)))
                                    var best: Float? = null
                                    for (face in faces) {
                                        var aligned: Bitmap? = null
                                        try {
                                            aligned = alignFace(bitmap, face)
                                            val candidateEmbedding = embedFace(activeInterpreter, aligned)
                                            val faceScore = combineReferenceScores(referenceEmbeddings.map { reference -> cosineSimilarity(reference, candidateEmbedding) })
                                            if (best == null || faceScore > best!!) best = faceScore
                                        } catch (_: IllegalArgumentException) {
                                            // Faces without the five required landmarks cannot be aligned.
                                        } finally { aligned?.recycle() }
                                    }
                                    val similarity = best
                                    val possible = similarity != null && similarity >= 0.4f
                                    if (possible) {
                                        possibleMatches++
                                        previews += MatchPreview(candidate.id, candidate.pageUrl, candidate.imageUrl, similarity!!, makeResultPreview(bitmap))
                                    }
                                    updates += CandidateUpdate(candidate.id, if (possible) "Possible match" else "Compared", if (possible) similarity else null)
                                } catch (_: Exception) {
                                    updates += CandidateUpdate(candidate.id, "Unavailable", null)
                                } finally { bitmap.recycle() }
                            }
                            checked++
                        }
                        // One transaction per chunk replaces one database write per image.
                        candidateDatabase.updateCandidateResults(updates)
                        if (previews.isNotEmpty()) runOnUiThread {
                            previews.forEach { item -> addWebMatchPreview(item.id, item.pageUrl, item.imageUrl, item.score, item.bitmap) }
                        }
                        // Progress updates are deliberately batched instead of posted once per image.
                        updateSearchProgress("$completedSources/${sources.size} sites checked, $checked images compared, $possibleMatches possible match(es)")
                    }
                }

                val sourcePool = Executors.newFixedThreadPool(6)
                try {
                    val completion = java.util.concurrent.ExecutorCompletionService<Triple<WebsiteSource, Int, Int>>(sourcePool)
                    val hostLocks = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.Semaphore>()
                    sources.forEach { source ->
                        completion.submit {
                            val host = URL(source.baseUrl).host.lowercase(java.util.Locale.US)
                            val hostLock = hostLocks.computeIfAbsent(host) { java.util.concurrent.Semaphore(1) }
                            hostLock.acquire()
                            try {
                                val discovered = PublicSourceScanner.scan(source)
                                val added = candidateDatabase.saveCandidates(discovered)
                                candidateDatabase.updateSourceStatus(
                                    source.id, "Checked; ${discovered.size} public image link(s), $added new"
                                )
                                Triple(source, discovered.size, added)
                            } catch (e: Exception) {
                                candidateDatabase.updateSourceStatus(
                                    source.id, "Could not scan: ${e.localizedMessage ?: "network error"}"
                                )
                                Triple(source, -1, 0)
                            } finally { hostLock.release() }
                        }
                    }
                    repeat(sources.size) {
                        if (Thread.currentThread().isInterrupted) throw InterruptedException("Search cancelled")
                        val (source, discoveredCount, added) = completion.take().get()
                        completedSources++
                        addedTotal += added
                        updateSearchProgress(
                            if (discoveredCount >= 0) {
                                "${source.name} returned $discoveredCount image link(s). $completedSources/${sources.size} sites checked; $checked images compared; $possibleMatches possible match(es)."
                            } else {
                                "${source.name} was unavailable. $completedSources/${sources.size} sites checked; $checked images compared."
                            }
                        )
                        comparePendingCandidates()
                    }
                } finally {
                    sourcePool.shutdownNow()
                }

                // Catch any final candidates saved just before the last completion signal.
                comparePendingCandidates()
                val totalIndexed = candidateDatabase.candidateCount()
                val summary = if (sources.isEmpty()) {
                    "All ${enabledSources.size} enabled public sources were scanned within the last 24 hours, so discovery was skipped. Checked $checked previously unprocessed images and found $possibleMatches possible match(es)."
                } else if (totalIndexed == 0) {
                    "No public image links were discovered. Supported sites may not expose crawlable public pages."
                } else {
                    "Search finished. Checked $completedSources/${sources.size} public sources, discovered $addedTotal new image link(s), compared $checked new or retryable image(s), and found $possibleMatches possible match(es). $totalIndexed image links remain indexed for future incremental searches. Public-source coverage is limited to pages made accessible by each website; this is not a complete index of the internet. Review matches manually."
                }
                runOnUiThread {
                    progress.max = 100
                    progress.progress = 100
                    status.text = "Source scan: 100% · $summary"
                    progress.visibility = ProgressBar.GONE
                    updateReady()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    status.text = if (e is InterruptedException || Thread.currentThread().isInterrupted) {
                        "Search cancelled. Previously saved matches are still available."
                    } else {
                        "Automatic scan or matching could not finish: ${e.localizedMessage ?: "unknown error"}"
                    }
                    progress.visibility = ProgressBar.GONE
                    updateReady()
                }
            } finally {
                downloadPool?.shutdownNow()
                runOnUiThread {
                    cancelButton.visibility = android.view.View.GONE
                    activeWebScan = null
                }
                alignedReferences.forEach { it.recycle() }
                detector?.close()
                interpreter?.close()
                candidateDatabase.close()
            }
        }
    }

    private fun exportSavedMatches(destination: Uri) {
        executor.execute {
            val db = PublicSourceDatabase(this)
            val matches = try { db.possibleMatches(2000, bestFirst = true) } finally { db.close() }
            try {
                contentResolver.openOutputStream(destination)?.bufferedWriter(Charsets.UTF_8).use { writer ->
                    requireNotNull(writer) { "Could not open the export destination." }
                    writer.appendLine("similarity_cosine,checked_at,source_url,page_url,image_url")
                    for (match in matches) {
                        val values = listOf(
                            match.similarity.toString(),
                            match.checkedAt.toString(),
                            match.sourceBaseUrl,
                            match.pageUrl,
                            match.imageUrl
                        ).joinToString(",") { "\"" + it.replace("\"", "\"\"") + "\"" }
                        writer.appendLine(values)
                    }
                }
                runOnUiThread {
                    Toast.makeText(this, "Exported ${matches.size} saved match(es).", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Export failed: ${e.localizedMessage ?: "unknown error"}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun makeResultPreview(bitmap: Bitmap): Bitmap {
        val targetWidth = 220
        val targetHeight = (targetWidth.toFloat() * bitmap.height / bitmap.width).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
        return if (scaled === bitmap) bitmap.copy(Bitmap.Config.ARGB_8888, false) else scaled
    }

    /** Restore recent match cards from the local database; image bytes remain temporary. */
    private fun restoreSavedMatches(bestFirst: Boolean = false) {
        executor.execute {
            val db = PublicSourceDatabase(this)
            val matches = try { db.possibleMatches(30, bestFirst) } finally { db.close() }
            var restored = 0
            for (match in matches) {
                try {
                    val bitmap = downloadPublicImageInMemory(match.imageUrl)
                    val preview = makeResultPreview(bitmap)
                    bitmap.recycle()
                    runOnUiThread {
                        addWebMatchPreview(match.id, match.pageUrl, match.imageUrl, match.similarity, preview)
                    }
                    restored++
                } catch (_: Exception) {
                    // A previously public image may have been removed or become unavailable.
                }
            }
            if (restored > 0) {
                runOnUiThread {
                    if (!isFinishing) status.text = "Restored $restored saved possible match(es). Start a new search to find newly discovered images."
                }
            }
        }
    }

    private fun addWebMatchPreview(candidateId: Long, pageUrl: String, imageUrl: String, similarity: Float, preview: Bitmap) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFF202832.toInt())
                cornerRadius = dp(12).toFloat()
            }
        }
        card.isClickable = true
        card.isFocusable = true
        card.setOnClickListener { openResultUrl(pageUrl.ifBlank { imageUrl }) }
        card.addView(ImageView(this).apply {
            setImageBitmap(preview)
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = LinearLayout.LayoutParams(dp(104), dp(104))
            contentDescription = "Possible match preview. Tap to open the source page."
            isClickable = true
            setOnClickListener { openResultUrl(imageUrl) }
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
            text = "Open source page"
            isAllCaps = false
            setOnClickListener { openResultUrl(pageUrl.ifBlank { imageUrl }) }
        })
        details.addView(MaterialButton(this).apply {
            text = "View image"
            isAllCaps = false
            setOnClickListener { openResultUrl(imageUrl) }
        })
        details.addView(MaterialButton(this).apply {
            text = "Not a match"
            isAllCaps = false
            setOnClickListener {
                executor.execute {
                    val db = PublicSourceDatabase(this@IdentityMatchActivity)
                    try { db.updateCandidateResult(candidateId, "Not a match", similarity) } finally { db.close() }
                }
                resultsContainer.removeView(card)
                Toast.makeText(this@IdentityMatchActivity, "Removed from possible matches.", Toast.LENGTH_SHORT).show()
            }
        })
        card.addView(details)
        resultsContainer.addView(card, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(10) })
    }

    private fun openResultUrl(address: String) {
        val uri = Uri.parse(address)
        if (uri.scheme !in listOf("https", "http") || uri.host.isNullOrBlank() || uri.userInfo != null) {
            Toast.makeText(this, "This result link is not a valid public web address.", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (_: Exception) {
            Toast.makeText(this, "No browser is available to open this result.", Toast.LENGTH_SHORT).show()
        }
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
            while (maxOf(bounds.outWidth / sample, bounds.outHeight / sample) > 1000) sample *= 2
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

    private fun combineReferenceScores(scores: List<Float>): Float {
        require(scores.isNotEmpty()) { "At least one reference embedding is required." }
        if (scores.size == 1) return scores.first()
        return scores.sortedDescending().take(2).average().toFloat()
    }

    /**
     * MobileFaceNet is a two-input verification model. Its two output vectors are
     * independently derived from the two input faces, so feeding the same face to
     * both inputs lets us extract one reusable embedding. Reference embeddings are
     * cached for the scan; each candidate face then needs only one model inference.
     */
    private fun embedFace(interpreter: Interpreter, bitmap: Bitmap): FloatArray {
        val inputShape0 = interpreter.getInputTensor(0).shape()
        val inputShape1 = interpreter.getInputTensor(1).shape()
        val input0 = makeInput(bitmap, inputShape0)
        val input1 = makeInput(bitmap, inputShape1)
        val outputShape = interpreter.getOutputTensor(0).shape()
        val output = ByteBuffer.allocateDirect(outputShape.fold(1) { acc, dim -> acc * dim } * 4)
            .order(ByteOrder.nativeOrder())
        interpreter.runForMultipleInputsOutputs(arrayOf(input0, input1), mutableMapOf<Int, Any>(0 to output))
        output.rewind()
        return normalize(FloatArray(128) { output.float })
    }

    private fun cosineSimilarity(first: FloatArray, second: FloatArray): Float {
        require(first.size == second.size) { "Embedding dimensions do not match." }
        return first.indices.sumOf { (first[it] * second[it]).toDouble() }
            .toFloat().coerceIn(-1f, 1f)
    }

    private fun makeInput(bitmap: Bitmap, shape: IntArray): ByteBuffer {
        require(bitmap.width == 112 && bitmap.height == 112) { "Face input must be 112 by 112 pixels." }
        val nchw = shape.contentEquals(intArrayOf(1, 3, 112, 112))
        val pixels = IntArray(112 * 112)
        bitmap.getPixels(pixels, 0, 112, 0, 0, 112, 112)
        val buffer = ByteBuffer.allocateDirect(112 * 112 * 3 * 4).order(ByteOrder.nativeOrder())
        if (nchw) {
            for (channel in 0..2) for (pixel in pixels) {
                val value = when (channel) {
                    0 -> (pixel shr 16) and 255
                    1 -> (pixel shr 8) and 255
                    else -> pixel and 255
                }
                buffer.putFloat(value / 255f)
            }
        } else {
            for (pixel in pixels) {
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
        val source = if (uri.scheme == "file") {
            ImageDecoder.createSource(File(uri.path ?: throw IllegalArgumentException("Missing image path.")))
        } else {
            ImageDecoder.createSource(contentResolver, uri)
        }
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val scale = minOf(1f, 1000f / maxOf(info.size.width, info.size.height))
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
        const val EXTRA_AUTO_START = "com.example.face.EXTRA_AUTO_START"
        const val EXTRA_REFERENCE_URIS = "com.example.face.EXTRA_REFERENCE_URIS"
    }
}
