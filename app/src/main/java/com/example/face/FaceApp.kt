package com.example.face

import android.app.Application

/** App setup. The app uses its own fixed color palette, so it does not apply the phone's wallpaper colors. */
class FaceApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashReport.install(this)
        registerActivityLifecycleCallbacks(AppLock)
        ImageCache.trim(this)
        if (AppSettings.reminderEnabled(this)) ScanReminderWorker.schedule(this)
    }
}
