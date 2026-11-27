package com.example.face

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.BufferedInputStream
import java.io.File
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * Downloads the real improved model (same URL and checksum the app uses) and checks that it loads and
 * produces a 512-value unit vector, and that the same face measured twice scores as identical.
 */
@RunWith(AndroidJUnit4::class)
class FaceModelTest {
    private fun face(seed: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(112, 112, Bitmap.Config.ARGB_8888)
        for (y in 0 until 112) for (x in 0 until 112) {
            bitmap.setPixel(x, y, Color.rgb((x * 2 + seed) % 256, (y * 2 + seed * 3) % 256, ((x + y) + seed * 7) % 256))
        }
        return bitmap
    }

    @Test fun improvedModelLoadsAndEmbeds() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val file = File(context.cacheDir, "test-model.onnx")
        val digest = MessageDigest.getInstance("SHA-256")
        ZipInputStream(BufferedInputStream(URL(FaceEmbedders.IMPROVED_URL).openStream())).use { zip ->
            var entry = zip.nextEntry
            while (entry != null && entry.name.substringAfterLast('/') != FaceEmbedders.IMPROVED_ENTRY) entry = zip.nextEntry
            requireNotNull(entry) { "model not in archive" }
            file.outputStream().use { out ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = zip.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                    out.write(buffer, 0, n)
                }
            }
        }
        assertEquals(FaceEmbedders.IMPROVED_SHA256, digest.digest().joinToString("") { "%02x".format(it) })

        ArcFaceEmbedder(file).use { model ->
            val a = model.embed(face(1))
            val again = model.embed(face(1))
            val b = model.embed(face(90))
            assertEquals(512, a.size)
            val norm = kotlin.math.sqrt(a.sumOf { (it * it).toDouble() })
            assertEquals(1.0, norm, 1e-3)
            val same = a.indices.sumOf { (a[it] * again[it]).toDouble() }
            val different = a.indices.sumOf { (a[it] * b[it]).toDouble() }
            assertEquals(1.0, same, 1e-4)
            assertTrue("different pictures should not score as identical", different < 0.999)
            assertTrue(model.calibrate(0.28f) in 0.399f..0.401f)
        }
        file.delete()
    }
}
