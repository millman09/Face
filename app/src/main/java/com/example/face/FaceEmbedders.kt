package com.example.face

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.Interpreter
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.sqrt

/** Turns an aligned 112×112 face into a unit-length vector; the same person gives nearby vectors. */
interface FaceEmbedder : Closeable {
    /** Identifies the model, so saved face measurements from one model are never compared with another's. */
    val id: String
    fun embed(face112: Bitmap): FloatArray
    /**
     * Maps this model's raw similarity onto one shared scale (0.40 = possible match, 0.50 = likely),
     * so thresholds, labels and the "Not a match" learning work the same whichever model is in use.
     */
    fun calibrate(raw: Float): Float
}

object FaceEmbedders {
    const val IMPROVED_FILE = "arcface_w600k_mbf.onnx"
    const val ORIGINAL_FILE = "qualcomm_mobilefacenet.tflite"

    /** InsightFace's official "buffalo_sc" release, which contains the w600k_mbf recognition model. */
    const val IMPROVED_URL = "https://github.com/deepinsight/insightface/releases/download/v0.7/buffalo_sc.zip"
    const val IMPROVED_ENTRY = "w600k_mbf.onnx"
    const val IMPROVED_SHA256 = "9cc6e4a75f0e2bf0b1aed94578f144d15175f357bdc05e815e5c4a02b319eb4f"

    private const val PREFS = "face_model"
    private const val KEY_USE_ORIGINAL = "use_original"

    fun prefersOriginal(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_USE_ORIGINAL, false)

    fun setPrefersOriginal(context: Context, original: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_USE_ORIGINAL, original).apply()
    }

    fun improvedFile(context: Context) = File(context.filesDir, IMPROVED_FILE)
    fun originalFile(context: Context) = File(context.filesDir, ORIGINAL_FILE)

    /** The model file to use: the improved one unless the original was chosen, falling back to whichever is installed. */
    fun selectedFile(context: Context): File? {
        val improved = improvedFile(context).takeIf { it.exists() }
        val original = originalFile(context).takeIf { it.exists() }
        return if (prefersOriginal(context)) original ?: improved else improved ?: original
    }

    fun isImproved(file: File) = file.name.endsWith(".onnx", ignoreCase = true)

    /** The [FaceEmbedder.id] the file's model will report, without loading it. */
    fun idFor(file: File): String = if (isImproved(file)) "arcface-w600k-mbf" else "mobilefacenet-qualcomm"

    fun open(file: File): FaceEmbedder = if (isImproved(file)) ArcFaceEmbedder(file) else MobileFaceNetEmbedder(file)
}

internal fun normalizeVector(vector: FloatArray): FloatArray {
    val length = sqrt(vector.sumOf { (it * it).toDouble() }).toFloat()
    require(length > 0f) { "The model returned an empty face vector." }
    return FloatArray(vector.size) { vector[it] / length }
}

private fun facePixels(bitmap: Bitmap, mirror: Boolean): IntArray {
    require(bitmap.width == 112 && bitmap.height == 112) { "Face input must be 112 by 112 pixels." }
    val pixels = IntArray(112 * 112)
    bitmap.getPixels(pixels, 0, 112, 0, 0, 112, 112)
    if (mirror) for (y in 0 until 112) pixels.reverse(y * 112, (y + 1) * 112)
    return pixels
}

/**
 * InsightFace w600k_mbf: a MobileFaceNet-sized network trained with ArcFace loss on WebFace600K, giving a
 * 512-value vector. Much better than the original model at the same person across angle, lighting and age.
 * Input is RGB, scaled to [-1, 1], channels first. The face and its mirror image are both measured and
 * averaged, which is steadier than one view.
 */
class ArcFaceEmbedder(file: File) : FaceEmbedder {
    override val id = "arcface-w600k-mbf"
    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession = env.createSession(
        file.absolutePath,
        OrtSession.SessionOptions().apply { setIntraOpNumThreads(4) }
    )
    private val inputName = session.inputNames.first()

    init {
        val shape = (session.inputInfo[inputName]?.info as? ai.onnxruntime.TensorInfo)?.shape
        require(shape != null && shape.size == 4 && shape[1] == 3L && shape[2] == 112L && shape[3] == 112L) {
            "This model doesn't take a 112×112 colour face (input shape ${shape?.contentToString()})."
        }
    }

    private fun run(bitmap: Bitmap, mirror: Boolean): FloatArray {
        val pixels = facePixels(bitmap, mirror)
        val data = FloatBuffer.allocate(3 * 112 * 112)
        val plane = 112 * 112
        for (i in pixels.indices) {
            val p = pixels[i]
            data.put(i, (((p shr 16) and 0xFF) - 127.5f) / 127.5f)
            data.put(plane + i, (((p shr 8) and 0xFF) - 127.5f) / 127.5f)
            data.put(2 * plane + i, ((p and 0xFF) - 127.5f) / 127.5f)
        }
        OnnxTensor.createTensor(env, data, longArrayOf(1, 3, 112, 112)).use { tensor ->
            session.run(mapOf(inputName to tensor)).use { result ->
                @Suppress("UNCHECKED_CAST")
                val output = (result[0].value as Array<FloatArray>)[0]
                return normalizeVector(output)
            }
        }
    }

    override fun embed(face112: Bitmap): FloatArray {
        val straight = run(face112, mirror = false)
        val mirrored = run(face112, mirror = true)
        return normalizeVector(FloatArray(straight.size) { straight[it] + mirrored[it] })
    }

    /**
     * For this model, different people rarely score above about 0.2 and the same person usually scores
     * 0.3 or more. Raw 0.28 maps to 0.40 ("possible") and raw 0.40 to 0.50 ("likely").
     */
    override fun calibrate(raw: Float): Float = (0.40f + (raw - 0.28f) * (0.10f / 0.12f)).coerceIn(0f, 1f)

    override fun close() {
        session.close()
    }
}

/**
 * The original Qualcomm MobileFaceNet (two inputs, two 128-value outputs). The face goes into the first
 * input and its mirror into the second, and the two vectors are averaged.
 */
class MobileFaceNetEmbedder(file: File) : FaceEmbedder {
    override val id = "mobilefacenet-qualcomm"
    private val interpreter = Interpreter(file, Interpreter.Options().setNumThreads(4))

    init {
        try {
            require(interpreter.inputTensorCount == 2) { "This model must accept two face images for pairwise verification." }
            for (i in 0..1) {
                val shape = interpreter.getInputTensor(i).shape()
                require(shape.contentEquals(intArrayOf(1, 112, 112, 3)) || shape.contentEquals(intArrayOf(1, 3, 112, 112))) {
                    "Input $i has unsupported shape ${shape.contentToString()}."
                }
                require(interpreter.getInputTensor(i).dataType() == org.tensorflow.lite.DataType.FLOAT32) { "Input $i must be float32." }
            }
            require(interpreter.outputTensorCount == 1) { "Expected one embedding output tensor." }
            val output = interpreter.getOutputTensor(0)
            require(output.shape().fold(1) { acc, dim -> acc * dim } == 256 && output.dataType() == org.tensorflow.lite.DataType.FLOAT32) {
                "Expected 256 float32 output values (two 128-value embeddings), got ${output.shape().contentToString()}."
            }
        } catch (e: Exception) {
            interpreter.close()
            throw e
        }
    }

    private fun input(bitmap: Bitmap, shape: IntArray, mirror: Boolean): ByteBuffer {
        val pixels = facePixels(bitmap, mirror)
        val nchw = shape.contentEquals(intArrayOf(1, 3, 112, 112))
        val buffer = ByteBuffer.allocateDirect(112 * 112 * 3 * 4).order(ByteOrder.nativeOrder())
        if (nchw) {
            for (channel in 0 until 3) for (p in pixels) {
                val value = when (channel) { 0 -> (p shr 16) and 0xFF; 1 -> (p shr 8) and 0xFF; else -> p and 0xFF }
                buffer.putFloat(value / 255f)
            }
        } else {
            // This model expects colour values scaled to 0..1.
            for (p in pixels) {
                buffer.putFloat(((p shr 16) and 0xFF) / 255f)
                buffer.putFloat(((p shr 8) and 0xFF) / 255f)
                buffer.putFloat((p and 0xFF) / 255f)
            }
        }
        buffer.rewind()
        return buffer
    }

    override fun embed(face112: Bitmap): FloatArray {
        val input0 = input(face112, interpreter.getInputTensor(0).shape(), mirror = false)
        val input1 = input(face112, interpreter.getInputTensor(1).shape(), mirror = true)
        val outputShape = interpreter.getOutputTensor(0).shape()
        val output = ByteBuffer.allocateDirect(outputShape.fold(1) { acc, dim -> acc * dim } * 4).order(ByteOrder.nativeOrder())
        interpreter.runForMultipleInputsOutputs(arrayOf(input0, input1), mutableMapOf<Int, Any>(0 to output))
        output.rewind()
        val straight = normalizeVector(FloatArray(128) { output.float })
        val mirrored = normalizeVector(FloatArray(128) { output.float })
        return normalizeVector(FloatArray(128) { straight[it] + mirrored[it] })
    }

    override fun calibrate(raw: Float): Float = raw

    override fun close() {
        interpreter.close()
    }
}
