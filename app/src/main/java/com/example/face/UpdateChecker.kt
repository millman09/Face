package com.example.face

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Checks whether the "latest-debug" GitHub release is a newer build than the one installed, and can
 * download and launch the installer for it. The app is sideloaded (not distributed through a store),
 * so this is the only update path it has.
 */
object UpdateChecker {
    private const val RELEASE_API = "https://api.github.com/repos/millman09/Face/releases/tags/latest-debug"

    data class UpdateInfo(val sha: String, val downloadUrl: String)

    /** Null if already up to date, unreachable, or the release has no APK asset. Call from a background thread. */
    fun check(): UpdateInfo? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(RELEASE_API).openConnection() as HttpURLConnection).apply {
                connectTimeout = 6000
                readTimeout = 8000
                requestMethod = "GET"
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "FaceSelfCheck/1.0")
            }
            if (connection.responseCode != 200) return null
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            val remoteSha = json.optString("target_commitish")
            if (remoteSha.isEmpty() || BuildConfig.GIT_SHA.isEmpty()) return null
            // The release always points at the commit it was built from; if it matches what's running
            // (a prefix match covers both a full and a short SHA on either side), there's nothing new.
            if (remoteSha.startsWith(BuildConfig.GIT_SHA) || BuildConfig.GIT_SHA.startsWith(remoteSha)) return null

            val assets = json.optJSONArray("assets") ?: return null
            var apkUrl: String? = null
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                if (asset.optString("name") == "face-debug.apk") {
                    apkUrl = asset.optString("browser_download_url")
                    break
                }
            }
            apkUrl?.let { UpdateInfo(remoteSha, it) }
        } catch (_: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * Downloads [info]'s APK with the system Download Manager and launches the installer once it finishes.
     * Registers a one-shot receiver for the download; [context] should be an Activity so the install prompt
     * has somewhere to show.
     */
    fun downloadAndInstall(context: Context, info: UpdateInfo) {
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val request = DownloadManager.Request(Uri.parse(info.downloadUrl))
            .setTitle("Face update")
            .setDescription("Downloading the latest build")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setMimeType("application/vnd.android.package-archive")
            .setDestinationInExternalFilesDir(context, android.os.Environment.DIRECTORY_DOWNLOADS, "face-update.apk")
        val downloadId = manager.enqueue(request)

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) != downloadId) return
                context.unregisterReceiver(this)
                val uri = try { manager.getUriForDownloadedFile(downloadId) } catch (_: Exception) { null }
                if (uri == null) {
                    android.widget.Toast.makeText(context, "The download didn't finish. Try again later.", android.widget.Toast.LENGTH_LONG).show()
                    return
                }
                val install = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(install)
            }
        }
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }
    }
}
