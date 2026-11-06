package com.example.face

import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

/** Material text styles for screens that are built in code, so they match the rest of the app. */
fun AppCompatActivity.sectionTitle(text: String): TextView = TextView(this).apply {
    this.text = text
    setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium)
    setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface))
}

fun AppCompatActivity.bodyText(text: String): TextView = TextView(this).apply {
    this.text = text
    setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
    setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant))
}

/** A filled button for the main action on a screen. */
fun AppCompatActivity.primaryButton(label: String, action: () -> Unit): MaterialButton =
    MaterialButton(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { action() }
    }

/** An outlined button for secondary actions. */
fun AppCompatActivity.secondaryButton(label: String, action: () -> Unit): MaterialButton =
    MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
        text = label
        isAllCaps = false
        setOnClickListener { action() }
    }

/** An outlined text box with a floating label. Returns the box and the field inside it. */
fun AppCompatActivity.textField(hint: String): Pair<TextInputLayout, TextInputEditText> {
    val box = TextInputLayout(this).apply { this.hint = hint }
    val field = TextInputEditText(box.context)
    box.addView(field)
    return box to field
}

/** A soft filled card that holds [content], used to group status text. */
fun AppCompatActivity.infoCard(content: View): MaterialCardView =
    MaterialCardView(this, null, com.google.android.material.R.attr.materialCardViewFilledStyle).apply {
        addView(content)
    }

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
