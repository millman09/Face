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
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.progressindicator.LinearProgressIndicator
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipInputStream
import java.io.BufferedInputStream
import kotlin.math.sqrt
import kotlin.math.abs
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
    private var modelButtons: List<MaterialButton> = emptyList()
    @Volatile private var cancelRequested = false
    private var scanning = false
    private lateinit var resultsContainer: LinearLayout
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(24))
        }
        setToolPage("Face matching", root)
        webScanMode = intent.getBooleanExtra(EXTRA_WEB_SCAN_MODE, false)
        autoStartScan = intent.getBooleanExtra(EXTRA_AUTO_START, false)
        @Suppress("DEPRECATION")
        referenceUris = intent.getParcelableArrayListExtra<Uri>(EXTRA_REFERENCE_URIS)?.distinct().orEmpty()
        referenceUri = referenceUris.firstOrNull()
        root.addSpaced(bodyText(
            if (webScanMode) {
                "Checks every enabled site in the built-in catalogue for public image links and compares them with your photos. Images are processed in memory on this phone and are not saved or uploaded."
            } else {
                "Runs on this phone with the MobileFaceNet model. Choose your reference photo(s) and the images to compare. Nothing is uploaded."
            }
        ), 16)
        val existingModel = File(filesDir, modelTargetName)
        if (existingModel.exists()) modelFile = existingModel
        // The model normally downloads on its own at first launch, so these two
        // buttons only show while no model is installed.
        modelButtons = listOf(
            addButton(root, "Download official MobileFaceNet model (~4 MB)") { downloadOfficialModel() },
            addButton(root, "Choose compatible TFLite model") { modelPicker.launch(arrayOf("*/*")) }
        )
        addButton(root, "Choose reference photo(s)") { referencePicker.launch(arrayOf("image/*")) }
        if (!webScanMode) addButton(root, "Choose candidate images") { imagesPicker.launch(arrayOf("image/*")) }
        scanButton = primaryButton(scanLabel()) {
            if (scanning) {
                cancelRequested = true
                status.text = "Stopping…"
            } else {
                runComparison()
            }
        }.apply { isEnabled = false }
        root.addSpaced(scanButton, 12)
        progress = LinearProgressIndicator(this).apply { visibility = ProgressBar.GONE }
        root.addSpaced(progress, 12)
        status = bodyText(if (webScanMode) "Install the model and choose reference photo(s)." else "No model or images selected.")
        status.setPadding(dp(16), dp(14), dp(16), dp(14))
        root.addSpaced(infoCard(status), 12)
        val resultsScroll = ScrollView(this).apply { layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f); isFillViewport = true }
        resultsContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        resultsScroll.addView(resultsContainer)
        root.addView(resultsScroll)
        handleSharedImages(intent)
        updateReady()
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

    private fun addButton(root: LinearLayout, label: String, action: () -> Unit): MaterialButton {
        val button = secondaryButton(label, action)
        root.addSpaced(button)
        return button
    }

    private fun scanLabel() =
        if (webScanMode) "Scan all public websites and match my photos" else "Compare faces on this phone"

    /** While a scan runs, the main button becomes a Stop button. Call on the main thread. */
    private fun setScanning(active: Boolean) {
        scanning = active
        if (active) {
            scanButton.text = "Stop scan"
            scanButton.isEnabled = true
        } else {
            scanButton.text = scanLabel()
            updateReady()
        }
    }

    private fun updateReady() {
        val modelReady = modelFile?.exists() == true
        modelButtons.forEach { it.visibility = if (modelReady) android.view.View.GONE else android.view.View.VISIBLE }
        scanButton.isEnabled = scanning || (modelFile?.exists() == true && referenceUris.isNotEmpty() &&
            (webScanMode || imageUris.isNotEmpty()))
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
     * One automatic workflow: find public image links on the catalogue sites (several
     * sites at once), then compare only the images that have not been checked yet.
     * Images are downloaded a few at a time while the previous one is being matched.
     */
    private fun runWebComparison() {
        val model = modelFile ?: return
        val references = referenceUris.toList()
        if (references.isEmpty()) return
        cancelRequested = false
        setScanning(true)
        progress.visibility = ProgressBar.VISIBLE
        progress.progress = 0
        resultsContainer.removeAllViews()
        status.text = "Getting ready…"
        executor.execute {
            var interpreter: Interpreter? = null
            var detector: FaceDetector? = null
            val alignedReferences = mutableListOf<Bitmap>()
            val database = PublicSourceDatabase(this)
            val downloaders = Executors.newFixedThreadPool(DOWNLOAD_THREADS)
            val queue = LinkedBlockingQueue<Fetched>(DOWNLOAD_QUEUE)
            var finalMessage = ""
            try {
                // ---- Step 1: discover image links, sites in parallel -------------------
                val sources = database.sources().filter { it.enabled }
                require(sources.isNotEmpty()) { "There are no enabled public sources in the catalogue." }
                val staleBefore = System.currentTimeMillis() - SOURCE_RECHECK_MS
                val dueSources = sources.filter { it.lastScan < staleBefore }
                if (dueSources.isNotEmpty()) {
                    val newLinks = AtomicInteger(0)
                    val finished = AtomicInteger(0)
                    val pool = Executors.newFixedThreadPool(DISCOVERY_THREADS)
                    try {
                        val tasks = dueSources.map { source ->
                            pool.submit(Runnable {
                                if (cancelRequested) return@Runnable
                                try {
                                    val found = PublicSourceScanner.scan(source)
                                    val added = database.saveCandidates(found)
                                    newLinks.addAndGet(added)
                                    database.updateSourceStatus(
                                        source.id, "Checked; ${found.size} public image link(s), $added new"
                                    )
                                } catch (e: Exception) {
                                    database.updateSourceStatus(
                                        source.id, "Could not scan: ${e.localizedMessage ?: "network error"}", scanned = false
                                    )
                                }
                                val done = finished.incrementAndGet()
                                postProgress(
                                    "Looking for images: $done of ${dueSources.size} sites checked, ${newLinks.get()} new link(s)…",
                                    done, dueSources.size
                                )
                            })
                        }
                        for (task in tasks) {
                            try { task.get() } catch (_: InterruptedException) { cancelRequested = true; break } catch (_: Exception) {}
                        }
                    } finally {
                        pool.shutdownNow()
                    }
                }
                if (cancelRequested) {
                    finalMessage = "Stopped before matching started."
                    return@execute
                }

                // ---- Step 2: work out which images still need checking ----------------
                // SCORING_VERSION is part of the key, so images compared with an older scoring method are checked again.
                val referenceKey = ("v$SCORING_VERSION|" + references.map { it.toString() }.sorted().joinToString("|")).hashCode()
                val scanState = getSharedPreferences("scan_state", MODE_PRIVATE)
                if (!scanState.contains("reference_key") || scanState.getInt("reference_key", 0) != referenceKey) {
                    // Different reference photos than last time, so earlier comparisons no longer apply.
                    database.resetResults()
                    scanState.edit().putInt("reference_key", referenceKey).apply()
                }
                val candidates = interleaveByHost(database.uncheckedCandidates(5000))
                if (candidates.isEmpty()) {
                    val known = database.candidateCount()
                    val saved = database.possibleMatches(2000).size
                    finalMessage = if (known == 0) {
                        "No image links were found. Many sites do not publish crawlable image sitemaps, so this is not a complete search of the internet."
                    } else {
                        "Nothing new to check: all $known known image links were already compared. $saved possible match(es) are saved in the Results tab."
                    }
                    return@execute
                }

                // ---- Step 3: load the model and prepare the reference faces -----------
                postProgress("Preparing face matching for ${candidates.size} new image(s)…", 0, candidates.size, force = true)
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
                // The reference faces never change, so their embeddings are computed once.
                val referenceEmbeddings = embedAll(activeInterpreter, alignedReferences)
                // With several reference photos, compare against their average. That is steadier
                // than taking the best of several single photos, which adds chances for false hits.
                val referenceTemplate = normalize(FloatArray(128) { i ->
                    referenceEmbeddings.sumOf { it[i].toDouble() }.toFloat()
                })
                alignedReferences.forEach { it.recycle() }
                alignedReferences.clear()

                // ---- Step 4: download in parallel, match in order ----------------------
                val nextIndex = AtomicInteger(0)
                val hostGates = ConcurrentHashMap<String, Semaphore>()
                repeat(DOWNLOAD_THREADS) {
                    downloaders.execute {
                        while (!cancelRequested) {
                            val i = nextIndex.getAndIncrement()
                            if (i >= candidates.size) break
                            val candidate = candidates[i]
                            val host = Uri.parse(candidate.imageUrl).host?.lowercase(java.util.Locale.US).orEmpty()
                            // At most two requests to one site at a time, to stay polite.
                            val gate = hostGates.getOrPut(host) { Semaphore(2) }
                            try {
                                gate.acquire()
                                val item = try {
                                    Fetched(candidate, downloadPublicImageInMemory(candidate.imageUrl), null)
                                } catch (e: Throwable) {
                                    Fetched(candidate, null, e.localizedMessage ?: "image unavailable")
                                } finally {
                                    gate.release()
                                }
                                queue.put(item)
                            } catch (_: InterruptedException) {
                                break
                            }
                        }
                    }
                }

                var processed = 0
                var possibleMatches = 0
                while (processed < candidates.size && !cancelRequested) {
                    val item = queue.poll(500, TimeUnit.MILLISECONDS) ?: continue
                    processed++
                    val candidate = item.candidate
                    val bitmap = item.bitmap
                    if (bitmap == null) {
                        database.updateCandidateResult(candidate.id, "Unavailable", null)
                    } else {
                        try {
                            val faces = Tasks.await(activeDetector.process(InputImage.fromBitmap(bitmap, 0)))
                            val alignedFaces = ArrayList<Bitmap>()
                            var best: Float? = null
                            try {
                                for (face in faces) {
                                    // Tiny or sideways faces give unreliable scores, so they are skipped.
                                    if (face.boundingBox.width() < MIN_FACE_PIXELS || abs(face.headEulerAngleY) > MAX_FACE_YAW) continue
                                    try { alignedFaces += alignFace(bitmap, face) } catch (_: IllegalArgumentException) {}
                                }
                                for (embedding in embedAll(activeInterpreter, alignedFaces)) {
                                    val score = dot(referenceTemplate, embedding)
                                    if (best == null || score > best) best = score
                                }
                            } finally {
                                alignedFaces.forEach { it.recycle() }
                            }
                            val similarity = best
                            val possible = similarity != null &&
                                Math.toDegrees(acos(similarity.coerceIn(-1f, 1f).toDouble())) <= 74.18
                            if (possible && similarity != null) {
                                possibleMatches++
                                val preview = Bitmap.createScaledBitmap(
                                    bitmap, 220,
                                    (220f * bitmap.height / bitmap.width).toInt().coerceAtLeast(1), true
                                )
                                runOnUiThread { addWebMatchPreview(candidate.imageUrl, similarity, preview) }
                            }
                            database.updateCandidateResult(candidate.id, if (possible) "Possible match" else "Compared", similarity)
                        } catch (e: Exception) {
                            if (!cancelRequested) database.updateCandidateResult(candidate.id, "Unavailable", null)
                        } finally {
                            bitmap.recycle()
                        }
                    }
                    postProgress(
                        "Matching: $processed of ${candidates.size} new images checked. $possibleMatches possible match(es) so far.",
                        processed, candidates.size
                    )
                }
                finalMessage = (if (cancelRequested) "Stopped. " else "Done. ") +
                    "Checked $processed new image(s) and found $possibleMatches possible match(es). " +
                    "Everything found is saved in the Results tab. A possible match is not proof, so review each one yourself."
            } catch (e: Exception) {
                finalMessage = "The scan could not finish: ${e.localizedMessage ?: "unknown error"}"
            } finally {
                cancelRequested = true // lets any download threads still running wind down
                downloaders.shutdownNow()
                queue.forEach { it.bitmap?.recycle() }
                alignedReferences.forEach { it.recycle() }
                detector?.close()
                interpreter?.close()
                database.close()
                val message = finalMessage
                runOnUiThread {
                    if (message.isNotEmpty()) status.text = message
                    progress.visibility = ProgressBar.GONE
                    setScanning(false)
                }
            }
        }
    }

    /** One downloaded image, or the reason it could not be fetched. */
    private class Fetched(val candidate: StoredCandidate, val bitmap: Bitmap?, val error: String?)

    /** Mixes the list so neighbouring images come from different sites, so parallel downloads spread out. */
    private fun interleaveByHost(items: List<StoredCandidate>): List<StoredCandidate> {
        val groups = items.groupBy { Uri.parse(it.imageUrl).host.orEmpty() }.values.map { it.iterator() }
        val result = ArrayList<StoredCandidate>(items.size)
        var added = true
        while (added) {
            added = false
            for (group in groups) {
                if (group.hasNext()) { result += group.next(); added = true }
            }
        }
        return result
    }

    @Volatile private var lastUiUpdate = 0L

    /** Posts a progress update, at most about four times a second so the screen stays smooth. */
    private fun postProgress(text: String, done: Int, total: Int, force: Boolean = false) {
        val now = android.os.SystemClock.uptimeMillis()
        if (!force && now - lastUiUpdate < 250) return
        lastUiUpdate = now
        runOnUiThread {
            status.text = text
            progress.max = total
            progress.progress = done
        }
    }

    private fun addWebMatchPreview(imageUrl: String, similarity: Float, preview: Bitmap) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        row.addView(ImageView(this).apply {
            setImageBitmap(preview)
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = LinearLayout.LayoutParams(dp(104), dp(104))
        })
        val details = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        details.addView(sectionTitle(matchLabel(similarity)))
        details.addView(bodyText("Similarity " + String.format(java.util.Locale.US, "%.3f", similarity)))
        details.addView(bodyText(Uri.parse(imageUrl).host ?: imageUrl))
        details.addView(secondaryButton("Open source") {
            try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(imageUrl))) } catch (_: Exception) {}
        })
        row.addView(details)
        val card = MaterialCardView(this, null, com.google.android.material.R.attr.materialCardViewFilledStyle)
        card.addView(row)
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
            // ImageDecoder applies the photo's rotation, which BitmapFactory ignores. Without
            // it, sideways phone photos would never show a face.
            return ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(data))) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val scale = minOf(1f, MAX_CANDIDATE_SIDE.toFloat() / maxOf(info.size.width, info.size.height))
                decoder.setTargetSize(
                    (info.size.width * scale).toInt().coerceAtLeast(1),
                    (info.size.height * scale).toInt().coerceAtLeast(1)
                )
            }
        } finally {
            connection?.disconnect()
        }
    }

    private fun alignFace(bitmap: Bitmap, face: Face): Bitmap {
        fun point(type: Int) = face.getLandmark(type)?.position
            ?: throw IllegalArgumentException("This face does not have all five landmarks needed for MobileFaceNet alignment.")
        // The alignment template expects the eye and mouth corner that appear on the left of the
        // photo first. Sorting by position makes this correct whichever side a landmark is labelled.
        val eyes = listOf(point(FaceLandmark.LEFT_EYE), point(FaceLandmark.RIGHT_EYE)).sortedBy { it.x }
        val mouth = listOf(point(FaceLandmark.MOUTH_LEFT), point(FaceLandmark.MOUTH_RIGHT)).sortedBy { it.x }
        val src = listOf(eyes[0], eyes[1], point(FaceLandmark.NOSE_BASE), mouth[0], mouth[1])
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
        val (a, b) = embedPair(interpreter, first, second)
        return dot(a, b)
    }

    /**
     * Runs the model once. It takes two faces and returns one embedding for each,
     * both already normalized to length 1.
     */
    private fun embedPair(interpreter: Interpreter, first: Bitmap, second: Bitmap): Pair<FloatArray, FloatArray> {
        val shape0 = interpreter.getInputTensor(0).shape()
        val shape1 = interpreter.getInputTensor(1).shape()
        val values0 = faceTensor(first, shape0)
        val values1 = if (second === first && shape1.contentEquals(shape0)) values0 else faceTensor(second, shape1)
        val inputs = arrayOf<Any>(toBuffer(values0), toBuffer(values1))
        val outputShape = interpreter.getOutputTensor(0).shape()
        val output = ByteBuffer.allocateDirect(outputShape.fold(1) { acc, dim -> acc * dim } * 4)
            .order(ByteOrder.nativeOrder())
        interpreter.runForMultipleInputsOutputs(inputs, mutableMapOf<Int, Any>(0 to output))
        output.rewind()
        val firstEmbedding = FloatArray(128) { output.float }
        val secondEmbedding = FloatArray(128) { output.float }
        return normalize(firstEmbedding) to normalize(secondEmbedding)
    }

    /**
     * One embedding per face. The model takes two inputs, so each run sees the face and its
     * mirror image, and the two results are averaged. That is steadier than a single view
     * and costs nothing extra, because the second input slot was otherwise unused.
     */
    private fun embedFace(interpreter: Interpreter, face: Bitmap): FloatArray {
        val shape0 = interpreter.getInputTensor(0).shape()
        val shape1 = interpreter.getInputTensor(1).shape()
        val pixels = readPixels(face)
        val inputs = arrayOf<Any>(
            toBuffer(tensorFrom(pixels, shape0)),
            toBuffer(tensorFrom(mirrorPixels(pixels), shape1))
        )
        val outputShape = interpreter.getOutputTensor(0).shape()
        val output = ByteBuffer.allocateDirect(outputShape.fold(1) { acc, dim -> acc * dim } * 4)
            .order(ByteOrder.nativeOrder())
        interpreter.runForMultipleInputsOutputs(inputs, mutableMapOf<Int, Any>(0 to output))
        output.rewind()
        val straight = normalize(FloatArray(128) { output.float })
        val mirrored = normalize(FloatArray(128) { output.float })
        return normalize(FloatArray(128) { straight[it] + mirrored[it] })
    }

    private fun embedAll(interpreter: Interpreter, faces: List<Bitmap>): List<FloatArray> =
        faces.map { embedFace(interpreter, it) }

    private fun dot(a: FloatArray, b: FloatArray): Float {
        var sum = 0f
        for (i in a.indices) sum += a[i] * b[i]
        return sum.coerceIn(-1f, 1f)
    }

    private fun faceTensor(bitmap: Bitmap, shape: IntArray): FloatArray = tensorFrom(readPixels(bitmap), shape)

    private fun readPixels(bitmap: Bitmap): IntArray {
        val pixels = IntArray(FACE_SIZE * FACE_SIZE)
        bitmap.getPixels(pixels, 0, FACE_SIZE, 0, 0, FACE_SIZE, FACE_SIZE)
        return pixels
    }

    private fun mirrorPixels(pixels: IntArray): IntArray {
        val flipped = IntArray(pixels.size)
        for (y in 0 until FACE_SIZE) for (x in 0 until FACE_SIZE) {
            flipped[y * FACE_SIZE + x] = pixels[y * FACE_SIZE + (FACE_SIZE - 1 - x)]
        }
        return flipped
    }

    /** Converts 112x112 pixels to the model's input layout and scales them to -1..1. */
    private fun tensorFrom(pixels: IntArray, shape: IntArray): FloatArray {
        val values = FloatArray(FACE_SIZE * FACE_SIZE * 3)
        if (shape.contentEquals(intArrayOf(1, 3, 112, 112))) {
            val plane = FACE_SIZE * FACE_SIZE
            for (i in pixels.indices) {
                val pixel = pixels[i]
                values[i] = ((pixel shr 16) and 255) / 127.5f - 1f
                values[plane + i] = ((pixel shr 8) and 255) / 127.5f - 1f
                values[2 * plane + i] = (pixel and 255) / 127.5f - 1f
            }
        } else {
            for (i in pixels.indices) {
                val pixel = pixels[i]
                values[i * 3] = ((pixel shr 16) and 255) / 127.5f - 1f
                values[i * 3 + 1] = ((pixel shr 8) and 255) / 127.5f - 1f
                values[i * 3 + 2] = (pixel and 255) / 127.5f - 1f
            }
        }
        return values
    }

    private fun toBuffer(values: FloatArray): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.nativeOrder())
        buffer.asFloatBuffer().put(values)
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
        cancelRequested = true
        executor.shutdownNow()
        super.onDestroy()
    }

    companion object {
        private const val DISCOVERY_THREADS = 8
        private const val DOWNLOAD_THREADS = 6
        private const val DOWNLOAD_QUEUE = 4
        private const val SOURCE_RECHECK_MS = 24L * 60 * 60 * 1000
        private const val MAX_CANDIDATE_SIDE = 1280
        private const val SCORING_VERSION = 2
        private const val FACE_SIZE = 112
        private const val MIN_FACE_PIXELS = 40
        private const val MAX_FACE_YAW = 45f
        const val EXTRA_WEB_SCAN_MODE = "com.example.face.EXTRA_WEB_SCAN_MODE"
        const val EXTRA_AUTO_START = "com.example.face.EXTRA_AUTO_START"
        const val EXTRA_REFERENCE_URIS = "com.example.face.EXTRA_REFERENCE_URIS"
    }
}
