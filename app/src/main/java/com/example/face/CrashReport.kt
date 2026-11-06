package com.example.face

import android.app.Application
import android.content.Context
import android.util.Log
import java.io.File

/**
 * Saves the error text when the app closes unexpectedly, so the next launch can show it.
 * The text stays on this phone and is only shown to you, with a Copy button.
 */
object CrashReport {
    private const val FILE_NAME = "last_crash.txt"

    fun install(app: Application) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                File(app.filesDir, FILE_NAME).writeText(
                    ("Thread: ${thread.name}\n" + Log.getStackTraceString(error)).take(8000)
                )
            } catch (_: Throwable) {
                // Never let the reporter hide the original crash.
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /** Returns the saved error from the last crash, once, then forgets it. */
    fun take(context: Context): String? {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) return null
        return try { file.readText() } catch (_: Exception) { null } finally { file.delete() }
    }
}
