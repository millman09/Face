package com.example.face

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Which sites actually work on this phone: the ones that turned up images, the ones that came back empty
 * or blocked the scanner, the ones not checked yet, and the ones you've turned off. Sites that don't work
 * can be turned off in one go (and turned back on individually).
 */
class SiteStatusActivity : AppCompatActivity() {
    private lateinit var content: LinearLayout
    private lateinit var summary: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(32))
        }
        setToolPage("Site status", page)
        summary = TextView(this).apply {
            textSize = 14f
            alpha = 0.85f
            setPadding(dp(2), dp(4), dp(2), dp(12))
        }
        page.addView(summary)
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        page.addView(content)
        load()
    }

    private enum class State(val title: String, val note: String) {
        WORKING("Working", "Turned up images last time."),
        NOT_WORKING("Not working", "Came back empty or blocked the scanner. Two in a row and a site sits out for a week."),
        UNCHECKED("Not checked yet", "Haven't been scanned on this phone yet."),
        OFF("Turned off", "Skipped by every search. Tap one to turn it back on."),
    }

    private fun stateOf(source: WebsiteSource): State = when {
        !source.enabled -> State.OFF
        source.emptyRuns > 0 || source.lastStatus.startsWith("Could not scan", ignoreCase = true) ||
            source.lastStatus.contains(" 0 public image", ignoreCase = true) -> State.NOT_WORKING
        source.lastScan == 0L && source.lastAttempt == 0L -> State.UNCHECKED
        else -> State.WORKING
    }

    private fun load() {
        summary.text = "Loading…"
        Thread {
            val sources = try {
                val db = PublicSourceDatabase(this)
                try { db.sources() } finally { db.close() }
            } catch (_: Exception) { emptyList() }
            runOnUiThread { if (!isDestroyed) render(sources) }
        }.start()
    }

    private fun render(sources: List<WebsiteSource>) {
        content.removeAllViews()
        val byState = sources.groupBy { stateOf(it) }
        val working = byState[State.WORKING].orEmpty().size
        val broken = byState[State.NOT_WORKING].orEmpty()
        summary.text = "$working working · ${broken.size} not working · ${byState[State.UNCHECKED].orEmpty().size} not checked yet" +
            (byState[State.OFF]?.let { " · ${it.size} turned off" } ?: "")

        if (broken.isNotEmpty()) {
            content.addView(MaterialButton(this).apply {
                text = "Turn off the ${broken.size} sites that aren't working"
                isAllCaps = false
                setOnClickListener { confirmTurnOff(broken) }
            })
        }
        for (state in State.values()) {
            val list = byState[state].orEmpty().sortedBy { it.name.lowercase() }
            if (list.isEmpty()) continue
            content.addView(TextView(this).apply {
                text = "${state.title.uppercase()}  ·  ${list.size}"
                textSize = 12f
                letterSpacing = 0.08f
                setTextColor(Color.rgb(173, 160, 255))
                setPadding(dp(2), dp(18), dp(2), dp(2))
            })
            content.addView(TextView(this).apply {
                text = state.note
                textSize = 12f
                alpha = 0.7f
                setPadding(dp(2), 0, dp(2), dp(8))
            })
            list.forEach { content.addView(row(it, state)) }
        }
    }

    private fun row(source: WebsiteSource, state: State) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(10), dp(12), dp(10))
        background = GradientDrawable().apply {
            setColor(Color.rgb(25, 29, 43))
            cornerRadius = dp(10).toFloat()
        }
        val host = (Uri.parse(source.baseUrl).host ?: source.baseUrl).removePrefix("www.")
        addView(TextView(this@SiteStatusActivity).apply {
            text = "${source.name}  ·  $host"
            textSize = 15f
            setTextColor(Color.WHITE)
        })
        val details = buildList {
            if (source.lastStatus.isNotBlank() && source.lastStatus != "Not scanned") add(source.lastStatus)
            if (source.lastDurationMs > 0) add("took ${source.lastDurationMs / 1000}s")
            if (source.isResting()) add("sitting out until next week")
        }
        if (details.isNotEmpty()) addView(TextView(this@SiteStatusActivity).apply {
            text = details.joinToString(" · ")
            textSize = 12f
            alpha = 0.7f
        })
        if (state == State.OFF) setOnClickListener { setEnabled(listOf(source), true) }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(6)
        }
    }

    private fun confirmTurnOff(broken: List<WebsiteSource>) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Turn off ${broken.size} sites?")
            .setMessage("Searches will skip them. You can turn any of them back on from this screen.")
            .setPositiveButton("Turn off") { _, _ -> setEnabled(broken, false) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun setEnabled(sources: List<WebsiteSource>, enabled: Boolean) {
        Thread {
            try {
                val db = PublicSourceDatabase(this)
                try { sources.forEach { db.setEnabled(it.id, enabled) } } finally { db.close() }
            } catch (_: Exception) {}
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                Toast.makeText(this, if (enabled) "${sources.first().name} turned back on." else "${sources.size} sites turned off.", Toast.LENGTH_SHORT).show()
                load()
            }
        }.start()
    }
}
