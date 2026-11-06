package com.example.face

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Once a week, reminds the user to run a deep search and mentions how many matches are still
 * waiting for review. The scan itself starts when the user taps the reminder, because it needs
 * the on-screen results list and takes a while.
 */
class ScanReminderWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        if (!AppSettings.reminderEnabled(applicationContext)) return Result.success()
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return Result.success()

        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Weekly check", NotificationManager.IMPORTANCE_DEFAULT)
        )
        val waiting = try {
            val db = PublicSourceDatabase(applicationContext)
            try { db.possibleMatches(2000).count { !it.confirmed } } finally { db.close() }
        } catch (_: Exception) { 0 }
        val text = if (waiting > 0) "Time for your weekly check. $waiting possible match(es) are waiting for review."
        else "Time for your weekly check. Tap to search public sources again."
        val open = PendingIntent.getActivity(
            applicationContext, 0, Intent(applicationContext, WebScanActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(R.drawable.ic_nav_results)
            .setContentTitle("Face")
            .setContentText(text)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        manager.notify(1, notification)
        return Result.success()
    }

    companion object {
        private const val CHANNEL = "weekly_check"
        private const val WORK = "weekly_check"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<ScanReminderWorker>(7, TimeUnit.DAYS).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK)
        }
    }
}
