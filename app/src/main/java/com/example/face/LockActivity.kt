package com.example.face

import android.app.Activity
import android.app.Application
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

/**
 * Asks for the phone's screen lock (fingerprint, face, PIN or pattern) before the app's screens are shown.
 * [AppLock] opens this screen on top of whatever was open when the app was away for over a minute.
 */
class LockActivity : AppCompatActivity() {

    private val confirm = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            AppLock.markActive()
            finish()
        } else {
            finishAffinity()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val keyguard = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        @Suppress("DEPRECATION")
        val intent = keyguard.createConfirmDeviceCredentialIntent("Face", "Unlock to continue")
        if (intent == null) {
            // No screen lock is set on this phone, so there is nothing to ask for.
            AppLock.markActive()
            finish()
        } else if (savedInstanceState == null) {
            confirm.launch(intent)
        }
    }

    override fun onDestroy() {
        AppLock.showing = false
        super.onDestroy()
    }
}

/** Tracks when the app was last in use and shows [LockActivity] after a minute away. */
object AppLock : Application.ActivityLifecycleCallbacks {
    private const val GRACE_MS = 60_000L
    @Volatile var showing = false
    private var lastActive = 0L

    fun markActive() { lastActive = SystemClock.elapsedRealtime() }

    override fun onActivityStarted(activity: Activity) {
        if (activity is LockActivity || showing) return
        if (!AppSettings.lockEnabled(activity)) return
        val away = SystemClock.elapsedRealtime() - lastActive
        if (lastActive == 0L || away > GRACE_MS) {
            showing = true
            activity.startActivity(Intent(activity, LockActivity::class.java))
        }
    }

    override fun onActivityStopped(activity: Activity) {
        if (activity !is LockActivity && !showing) markActive()
    }

    override fun onActivityResumed(activity: Activity) {
        if (activity !is LockActivity && !showing) markActive()
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}
}
