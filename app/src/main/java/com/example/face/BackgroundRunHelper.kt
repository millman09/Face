package com.example.face

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/**
 * Asks Android to stop battery-optimizing Face, so a long deep search isn't killed while it runs in the
 * background. Some phone makers add their own, stricter battery managers on top of this; those still need
 * a manual setting change, which [manufacturerHint] points to.
 */
object BackgroundRunHelper {
    private const val PREFS = "background_run"
    private const val KEY_DECLINED_AT = "declined_at"
    private const val ASK_AGAIN_AFTER_MS = 7L * 24L * 60L * 60L * 1000L

    fun isExempt(context: Context): Boolean {
        val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return power.isIgnoringBatteryOptimizations(context.packageName)
    }

    /** True if the app isn't exempt yet and the user hasn't said "Not now" in the last week. */
    fun shouldAsk(context: Context): Boolean {
        if (isExempt(context)) return false
        val declinedAt = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_DECLINED_AT, 0L)
        return System.currentTimeMillis() - declinedAt > ASK_AGAIN_AFTER_MS
    }

    fun rememberDeclined(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_DECLINED_AT, System.currentTimeMillis()).apply()
    }

    /** The system's one-tap "Allow Face to run in the background?" prompt, or the battery settings list as a fallback. */
    @SuppressLint("BatteryLife")
    fun requestIntent(context: Context): Intent {
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
        return if (direct.resolveActivity(context.packageManager) != null) direct
        else Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
    }

    /** Extra advice for phone makers known to kill background apps regardless of the setting above. */
    fun manufacturerHint(): String? = when (android.os.Build.MANUFACTURER.lowercase()) {
        "samsung" -> "On Samsung phones, also open Settings › Apps › Face › Battery and choose Unrestricted."
        "xiaomi", "redmi", "poco" -> "On Xiaomi phones, also open Settings › Apps › Face › Battery saver and choose No restrictions, and turn on Autostart."
        "oneplus", "oppo", "realme" -> "On this phone, also open Settings › Apps › Face › Battery and allow background activity."
        "huawei", "honor" -> "On this phone, also open Settings › Battery › App launch and set Face to Manage manually with everything allowed."
        else -> null
    }
}
