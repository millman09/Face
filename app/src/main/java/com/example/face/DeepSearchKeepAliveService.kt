package com.example.face

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/**
 * Does nothing but hold a foreground-priority notification (and a wake lock) for as long as a deep
 * search is running (started in [IdentityMatchActivity.runWebComparison], stopped when it finishes, is
 * cancelled, or errors out). The actual scan runs on its own background thread, independent of any
 * Activity, so it already survives the user navigating elsewhere in the app, including pressing back.
 * What it does *not* survive on its own is the OS reclaiming the whole process once no Activity is in
 * the foreground and nothing else marks the process as important, or the CPU going to sleep if the
 * screen turns off — which is exactly what this service's foreground status and wake lock prevent, for
 * as long as it's running. (Some phone manufacturers ship their own, more aggressive battery managers
 * on top of stock Android that can still kill background apps despite this; if a search still stops
 * after this, check whether this app is exempted from battery optimization in the phone's own Settings.)
 */
class DeepSearchKeepAliveService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, buildNotification(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        )
        if (wakeLock == null) {
            val power = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Face:deepSearch").apply {
                setReferenceCounted(false)
                // A safety ceiling, not an expected duration: if the scan somehow never calls stop(),
                // this guarantees the lock is released rather than draining the battery indefinitely.
                acquire(4 * 60 * 60 * 1000L)
            }
        }
        // If the process is ever killed anyway (e.g. extreme memory pressure), don't restart this
        // empty shell with no scan to protect — restarting the real scan automatically isn't safe.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    private fun buildNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle("Face")
        .setContentText("Deep search running in the background…")
        .setSmallIcon(R.drawable.ic_arrow_back)
        .setOngoing(true)
        .setContentIntent(
            PendingIntent.getActivity(
                this, 0,
                Intent(this, IdentityMatchActivity::class.java).apply {
                    putExtra(IdentityMatchActivity.EXTRA_WEB_SCAN_MODE, true)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                },
                PendingIntent.FLAG_IMMUTABLE
            )
        )
        .build()

    companion object {
        private const val CHANNEL_ID = "deep_search_running"
        private const val NOTIFICATION_ID = 7421

        fun start(context: Context) {
            ensureChannel(context)
            val intent = Intent(context, DeepSearchKeepAliveService::class.java)
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, DeepSearchKeepAliveService::class.java))
        }

        private fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            if (manager.getNotificationChannel(CHANNEL_ID) != null) return
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Deep search running", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }
}
