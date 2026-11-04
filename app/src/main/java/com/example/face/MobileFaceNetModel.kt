package com.example.face

import android.content.Context
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * Downloads the public MobileFaceNet model once, on the first app launch.
 * The archive and extracted model stay in app-private storage.
 */
object MobileFaceNetModel {
    private const val MODEL_URL = "https://qaihub-public-assets.s3.us-west-2.amazonaws.com/qai-hub-models/models/mobile_facenet/releases/v0.65.0/mobile_facenet-tflite-float.zip"
    private const val MODEL_NAME = "qualcomm_mobilefacenet.tflite"

    @Synchronized
    fun ensureDownloaded(context: Context): File {
        val directory = context.applicationContext.filesDir
        val target = File(directory, MODEL_NAME)
        if (target.isFile && target.length() > 100_000L) return target

        val temporary = File(directory, "$MODEL_NAME.download")
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 90_000
                instanceFollowRedirects = true
                requestMethod = "GET"
                setRequestProperty("User-Agent", "FacePersonalScanner/1.0")
            }
            connection.connect()
            check(connection.responseCode in 200..299) {
                "Model download returned HTTP ${connection.responseCode}"
            }

            var extracted = false
            ZipInputStream(BufferedInputStream(connection.inputStream)).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory && entry.name.lowercase().endsWith(".tflite")) {
                        var total = 0L
                        FileOutputStream(temporary).use { output ->
                            val buffer = ByteArray(16 * 1024)
                            while (true) {
                                val count = zip.read(buffer)
                                if (count < 0) break
                                total += count
                                check(total <= 30L * 1024L * 1024L) { "Model file exceeds the 30 MB safety limit." }
                                output.write(buffer, 0, count)
                            }
                        }
                        check(total > 100_000L) { "The downloaded model is empty or incomplete." }
                        extracted = true
                        break
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
            check(extracted) { "The official archive did not contain a TFLite model." }
            validate(temporary)
            if (target.exists()) check(target.delete()) { "Could not replace the old model file." }
            check(temporary.renameTo(target)) { "Could not install the downloaded model." }
            return target
        } catch (error: Exception) {
            temporary.delete()
            throw error
        } finally {
            connection?.disconnect()
        }
    }

    private fun validate(file: File) {
        val interpreter = Interpreter(file, Interpreter.Options().setNumThreads(2))
        try {
            check(interpreter.inputTensorCount == 2) {
                "The downloaded model has an unexpected input count."
            }
            for (index in 0..1) {
                val tensor = interpreter.getInputTensor(index)
                val shape = tensor.shape()
                check(
                    shape.contentEquals(intArrayOf(1, 112, 112, 3)) ||
                    shape.contentEquals(intArrayOf(1, 3, 112, 112))
                ) { "The downloaded model has an unsupported input shape." }
                check(tensor.dataType() == DataType.FLOAT32) {
                    "The downloaded model has an unsupported input type."
                }
            }
            check(interpreter.outputTensorCount == 1) {
                "The downloaded model has an unexpected output count."
            }
            val output = interpreter.getOutputTensor(0)
            val elements = output.shape().fold(1) { result, dimension -> result * dimension }
            check(elements == 256 && output.dataType() == DataType.FLOAT32) {
                "The downloaded model has an unexpected output format."
            }
        } finally {
            interpreter.close()
        }
    }
}
