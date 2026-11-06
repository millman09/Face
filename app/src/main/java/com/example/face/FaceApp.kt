package com.example.face

import android.app.Application
import com.google.android.material.color.DynamicColors

/** Uses the phone's wallpaper colors for the app theme on Android 12 and newer. */
class FaceApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashReport.install(this)
        DynamicColors.applyToActivitiesIfAvailable(this)
    }
}
