package com.example.face

import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.appbar.MaterialToolbar

/** Converts density-independent pixels to pixels for this screen. */
fun AppCompatActivity.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

/**
 * Shows [page] under a top bar with a back arrow and [title], inside a scrolling
 * container that stays clear of the status bar, navigation bar and display
 * cutout. Android 15+ draws apps edge to edge, so screens built in code need
 * this or their content sits under the system bars.
 */
fun AppCompatActivity.setToolPage(title: String, page: LinearLayout) {
    val toolbar = MaterialToolbar(this).apply {
        this.title = title
        setNavigationIcon(R.drawable.ic_arrow_back)
        navigationContentDescription = "Back"
        setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
    }
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
    val column = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(
            toolbar,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        addView(
            scroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )
    }
    setContentView(column)
    ViewCompat.setOnApplyWindowInsetsListener(column) { view, insets ->
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

/**
 * Plain-language strength for a face similarity score (cosine, up to 1.0). Anything shown as a
 * match is already above the app's 0.40 cut-off; these bands only rank the matches. They are
 * starting points: try them on photos of yourself and adjust.
 */
fun matchLabel(similarity: Float): String = when {
    similarity >= 0.60f -> "Strong match"
    similarity >= 0.50f -> "Likely match"
    else -> "Possible match"
}
