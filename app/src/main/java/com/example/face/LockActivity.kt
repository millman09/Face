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

/**
 * Asks for the screen lock when the app comes back after more than a minute in the background.
 *
 * Earlier this measured time since the last screen change, so staying on one screen for over a minute
 * (watching a scan, reading results) and then opening another screen asked for the fingerprint again even
 * though the app never left. Now the clock only runs while no Face screen is visible.
 */
object AppLock : Application.ActivityLifecycleCallbacks {
    private const val GRACE_MS = 60_000L
    @Volatile var showing = false
    private var startedScreens = 0
    private var backgroundedAt = 0L
    private var unlockedOnce = false

    fun markActive() {
        unlockedOnce = true
        backgroundedAt = 0L
    }

    override fun onActivityStarted(activity: Activity) {
        val comingBack = startedScreens == 0
        startedScreens++
        if (activity is LockActivity || showing) return
        if (!comingBack) return
        if (!AppSettings.lockEnabled(activity)) {
            markActive()
            return
        }
        val away = if (backgroundedAt == 0L) 0L else SystemClock.elapsedRealtime() - backgroundedAt
        if (!unlockedOnce || away > GRACE_MS) {
            showing = true
            activity.startActivity(Intent(activity, LockActivity::class.java))
        }
    }

    override fun onActivityStopped(activity: Activity) {
        startedScreens = (startedScreens - 1).coerceAtLeast(0)
        // The lock screen hands over to the system's fingerprint prompt; that isn't the app going away.
        if (startedScreens == 0 && activity !is LockActivity && !showing) {
            backgroundedAt = SystemClock.elapsedRealtime()
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityResumed(activity: Activity) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}
}
