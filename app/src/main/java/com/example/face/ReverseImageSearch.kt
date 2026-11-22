package com.example.face

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

/**
 * Sends one of your own reference photos to a reverse image search engine, which looks across the
 * engine's whole index for that photo and similar-looking ones — far wider than the app's own crawl.
 * The photo goes only to the engine you pick, and only when you pick it.
 */
object ReverseImageSearch {
    enum class Engine(
        val label: String,
        val packages: List<String>,
        val webUrl: String,
        val webHowTo: String,
    ) {
        YANDEX(
            "Yandex Images (best at finding faces)",
            listOf("ru.yandex.searchplugin", "com.yandex.browser"),
            "https://yandex.com/images/",
            "Tap the camera icon in the search box and upload your photo.",
        ),
        GOOGLE_LENS(
            "Google Lens (best at finding exact reposts)",
            listOf("com.google.ar.lens", "com.google.android.googlequicksearchbox"),
            "https://lens.google.com/",
            "Tap \"Upload\" and choose your photo.",
        ),
        BING(
            "Bing Visual Search",
            listOf("com.microsoft.bing"),
            "https://www.bing.com/visualsearch",
            "Tap \"Browse\" or the camera icon and upload your photo.",
        ),
    }

    /** Opens [engine] with [photo]: in its app when installed, otherwise its website with upload instructions. */
    fun search(activity: Activity, engine: Engine, photo: Uri) {
        val installed = engine.packages.firstOrNull { isInstalled(activity, it) }
        if (installed != null) {
            val shared = try { shareableCopy(activity, photo) } catch (_: Exception) { null }
            if (shared != null) {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "image/jpeg"
                    putExtra(Intent.EXTRA_STREAM, shared)
                    clipData = ClipData.newRawUri("photo", shared)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    setPackage(installed)
                }
                try {
                    activity.startActivity(send)
                    return
                } catch (_: ActivityNotFoundException) {
                    // That app doesn't accept shared photos; fall back to the website.
                }
            }
        }
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(engine.webUrl)))
            Toast.makeText(activity, engine.webHowTo, Toast.LENGTH_LONG).show()
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(activity, "No browser is available to open ${engine.webUrl}", Toast.LENGTH_LONG).show()
        }
    }

    private fun isInstalled(activity: Activity, packageName: String): Boolean = try {
        activity.packageManager.getPackageInfo(packageName, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    /** Copies the photo into the app's private cache so it can be shared through a short-lived link. */
    private fun shareableCopy(activity: Activity, photo: Uri): Uri {
        val dir = File(activity.cacheDir, "shared").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "photo-${System.currentTimeMillis()}.jpg")
        activity.contentResolver.openInputStream(photo).use { input ->
            requireNotNull(input) { "Couldn't read the photo." }
            file.outputStream().use { input.copyTo(it) }
        }
        return FileProvider.getUriForFile(activity, "${activity.packageName}.files", file)
    }
}
