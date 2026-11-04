package com.example.face

import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/** Converts density-independent pixels to pixels for this screen. */
fun AppCompatActivity.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

/**
 * Shows [page] inside a scrolling container that stays clear of the status bar,
 * navigation bar and display cutout. Android 15+ draws apps edge to edge, so
 * screens built in code need this or their content sits under the system bars.
 */
fun AppCompatActivity.setScrollablePage(page: LinearLayout) {
    val scroll = ScrollView(this).apply {
        isFillViewport = true
        clipToPadding = false
        addView(
            page,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
    }
    setContentView(scroll)
    ViewCompat.setOnApplyWindowInsetsListener(scroll) { view, insets ->
        val bars = insets.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
        )
        view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
        insets
    }
}

/** Adds [view] to this column, full width, with a little space below it. */
fun LinearLayout.addSpaced(view: View, bottomDp: Int = 8) {
    val params = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    )
    params.bottomMargin = (bottomDp * resources.displayMetrics.density).toInt()
    addView(view, params)
}
