package com.example.face

import android.content.Context

/**
 * Remembers an unfinished full search, so it can be continued later instead of starting over. A site
 * counts as done for the session once it was scanned after the session began, however long ago that was
 * (before, a site was only skipped if it was scanned in the last 24 hours, so a search stopped overnight
 * effectively restarted from the beginning).
 */
object SearchSession {
    private const val PREFS = "scan_state"
    private const val KEY_STARTED = "session_started_at"
    private const val KEY_COMPLETE = "session_complete"

    /** When the unfinished search began, or null if there's nothing to continue. */
    fun unfinishedSince(context: Context): Long? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val started = prefs.getLong(KEY_STARTED, 0L)
        return if (started > 0L && !prefs.getBoolean(KEY_COMPLETE, true)) started else null
    }

    /** Starts a new full search session and returns its start time. */
    fun startFresh(context: Context): Long {
        val now = System.currentTimeMillis()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_STARTED, now).putBoolean(KEY_COMPLETE, false).apply()
        return now
    }

    fun markComplete(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_COMPLETE, true).apply()
    }
}
