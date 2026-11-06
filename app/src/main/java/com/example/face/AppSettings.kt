package com.example.face

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/** Small user settings plus the checks that decide whether a scan may keep running right now. */
object AppSettings {
    private const val PREFS = "settings"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun lockEnabled(context: Context) = prefs(context).getBoolean("lock", false)
    fun setLockEnabled(context: Context, value: Boolean) = prefs(context).edit().putBoolean("lock", value).apply()

    fun wifiOnly(context: Context) = prefs(context).getBoolean("wifi_only", false)
    fun setWifiOnly(context: Context, value: Boolean) = prefs(context).edit().putBoolean("wifi_only", value).apply()

    fun reminderEnabled(context: Context) = prefs(context).getBoolean("weekly_reminder", false)
    fun setReminderEnabled(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean("weekly_reminder", value).apply()

    /** Null when a scan may run, otherwise a short reason it is waiting. */
    fun scanBlockedReason(context: Context): String? {
        if (wifiOnly(context) && !onUnmeteredNetwork(context)) return "Waiting for Wi-Fi…"
        return null
    }

    private fun onUnmeteredNetwork(context: Context): Boolean {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
        val caps = manager.getNetworkCapabilities(manager.activeNetwork ?: return false) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }
}
