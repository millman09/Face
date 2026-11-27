package com.example.face

import android.app.Dialog
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * A photo grid of your saved matches and past searches. It only reads saved results, so it can be opened
 * at any time, including while a search runs in the background, without affecting that search.
 */
class SavedImagesActivity : AppCompatActivity() {
    private lateinit var page: LinearLayout
    private lateinit var status: TextView
    private lateinit var content: LinearLayout
    private lateinit var currentTab: MaterialButton
    private lateinit var pastTab: MaterialButton

    private val loader = Executors.newFixedThreadPool(4)
    private val pending = mutableListOf<Future<*>>()
    private val thumbs = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    /** Bumped whenever the grid is replaced, so images still loading for the old grid are dropped. */
    @Volatile private var generation = 0
    private var showingPast = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(32))
        }
        setToolPage("Saved images", page)

        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        currentTab = tabButton("Current matches") { showCurrent() }
        pastTab = tabButton("Past searches") { showPastList() }
        tabs.addView(currentTab, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(4) })
        tabs.addView(pastTab, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(4) })
        page.addView(tabs)

        status = TextView(this).apply {
            textSize = 13f
            alpha = 0.75f
            setPadding(dp(2), dp(10), dp(2), dp(10))
        }
        page.addView(status)
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        page.addView(content)

        if (intent.getBooleanExtra(EXTRA_OPEN_PAST, false)) showPastList() else showCurrent()
    }

    override fun onDestroy() {
        generation++
        loader.shutdownNow()
        super.onDestroy()
    }

    private fun tabButton(label: String, action: () -> Unit) =
        MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = label
            isAllCaps = false
            setOnClickListener { action() }
        }

    private fun selectTab(past: Boolean) {
        showingPast = past
        val on = Color.rgb(105, 83, 230)
        currentTab.setBackgroundColor(if (past) Color.TRANSPARENT else on)
        currentTab.setTextColor(if (past) Color.rgb(173, 160, 255) else Color.WHITE)
        pastTab.setBackgroundColor(if (past) on else Color.TRANSPARENT)
        pastTab.setTextColor(if (past) Color.WHITE else Color.rgb(173, 160, 255))
    }

    private fun resetContent() {
        generation++
        synchronized(pending) {
            pending.forEach { it.cancel(true) }
            pending.clear()
        }
        content.removeAllViews()
    }

    // ---- Current matches -------------------------------------------------------------------------------

    private fun showCurrent() {
        selectTab(past = false)
        resetContent()
        status.text = "Loading…"
        loadCurrentPage(0)
    }

    private fun loadCurrentPage(offset: Int) {
        val gen = generation
        Thread {
            val rows = try {
                val db = PublicSourceDatabase(this)
                try { db.possibleMatches(offset + PAGE + 1, bestFirst = true).drop(offset) } finally { db.close() }
            } catch (_: Exception) { emptyList() }
            runOnUiThread {
                if (gen != generation || isDestroyed) return@runOnUiThread
                val running = IdentityMatchActivity.isScanRunning()
                if (offset == 0) {
                    status.text = when {
                        rows.isEmpty() && running -> "A search is running. Matches show up here as it finds them."
                        rows.isEmpty() -> "No saved matches yet. Start a search from Find my face."
                        running -> "A search is running in the background. Tap Refresh to see new matches."
                        else -> "Best matches first. Tap a photo to see it larger."
                    }
                    if (running) content.addView(tabButton("Refresh") { showCurrent() })
                }
                addGrid(rows.take(PAGE), readOnly = false)
                if (rows.size > PAGE) addMoreButton { loadCurrentPage(offset + PAGE) }
            }
        }.start()
    }

    // ---- Past searches ----------------------------------------------------------------------------------

    private fun showPastList() {
        selectTab(past = true)
        resetContent()
        status.text = "Loading…"
        val gen = generation
        Thread {
            val searches = try {
                val db = PublicSourceDatabase(this)
                try { db.pastSearches() } finally { db.close() }
            } catch (_: Exception) { emptyList() }
            runOnUiThread {
                if (gen != generation || isDestroyed) return@runOnUiThread
                if (searches.isEmpty()) {
                    status.text = "No past searches yet. Starting a new search saves the previous one here."
                    return@runOnUiThread
                }
                status.text = "Tap a search to see its photos."
                val format = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT)
                for (search in searches) {
                    content.addView(row(format.format(java.util.Date(search.savedAt)), "${search.matchCount} match(es)") {
                        showPastSearch(search, format.format(java.util.Date(search.savedAt)))
                    })
                }
            }
        }.start()
    }

    private fun showPastSearch(search: SearchInfo, label: String) {
        resetContent()
        status.text = "Search saved $label"
        content.addView(TextView(this).apply {
            text = "‹  All past searches"
            textSize = 15f
            setTextColor(Color.rgb(173, 160, 255))
            setPadding(dp(2), dp(4), dp(2), dp(10))
            setOnClickListener { showPastList() }
        })
        val gen = generation
        Thread {
            val matches = try {
                val db = PublicSourceDatabase(this)
                try { db.pastSearchMatches(search.id) } finally { db.close() }
            } catch (_: Exception) { emptyList() }
            runOnUiThread {
                if (gen != generation || isDestroyed) return@runOnUiThread
                if (matches.isEmpty()) status.text = "This past search has no photos."
                addGrid(matches, readOnly = true)
                content.addView(tabButton("Delete this past search") { confirmDeletePast(search) })
            }
        }.start()
    }

    private fun confirmDeletePast(search: SearchInfo) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Delete this past search?")
            .setMessage("Its saved matches will be removed. Your current search isn't affected.")
            .setPositiveButton("Delete") { _, _ ->
                Thread {
                    try {
                        val db = PublicSourceDatabase(this)
                        try { db.deletePastSearch(search.id) } finally { db.close() }
                    } catch (_: Exception) {}
                    runOnUiThread { if (!isDestroyed) showPastList() }
                }.start()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---- Grid -------------------------------------------------------------------------------------------

    private fun addGrid(matches: List<MatchResult>, readOnly: Boolean) {
        if (matches.isEmpty()) return
        val columns = 3
        val gap = dp(4)
        val available = resources.displayMetrics.widthPixels - dp(32)
        val cell = (available - gap * (columns - 1)) / columns
        val grid = GridLayout(this).apply { columnCount = columns }
        matches.forEachIndexed { index, match ->
            val image = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(Color.rgb(32, 36, 50))
                contentDescription = "Saved match from ${hostOf(match)}"
            }
            val badge = TextView(this).apply {
                text = "${(match.similarity * 100).toInt()}%" + if (match.confirmed) " ✓" else ""
                textSize = 11f
                setTextColor(Color.WHITE)
                setPadding(dp(5), dp(2), dp(5), dp(2))
                background = GradientDrawable().apply {
                    setColor(Color.argb(170, 0, 0, 0))
                    cornerRadius = dp(6).toFloat()
                }
            }
            val frame = FrameLayout(this).apply {
                addView(image, FrameLayout.LayoutParams(cell, cell))
                addView(badge, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START
                ).apply { setMargins(dp(4), 0, 0, dp(4)) })
                setOnClickListener { showDetail(match) }
            }
            grid.addView(frame, GridLayout.LayoutParams().apply {
                width = cell
                height = cell
                setMargins(0, 0, if (index % columns == columns - 1) 0 else gap, gap)
            })
            loadThumb(match, image, cell)
        }
        content.addView(grid)
    }

    private fun loadThumb(match: MatchResult, into: ImageView, size: Int) {
        thumbs.get(match.imageUrl)?.let { into.setImageBitmap(it); return }
        val gen = generation
        val task = loader.submit {
            val thumb = try {
                val full = PublicImageLoader.loadForDisplay(this, match.imageUrl, match.pageUrl.ifBlank { null })
                val scale = size.toFloat() / minOf(full.width, full.height)
                val scaled = if (scale < 1f) {
                    Bitmap.createScaledBitmap(full, (full.width * scale).toInt().coerceAtLeast(1), (full.height * scale).toInt().coerceAtLeast(1), true)
                } else full
                if (scaled !== full) full.recycle()
                scaled
            } catch (_: Exception) { null }
            runOnUiThread {
                if (gen != generation || isDestroyed) return@runOnUiThread
                if (thumb != null) {
                    thumbs.put(match.imageUrl, thumb)
                    into.setImageBitmap(thumb)
                } else {
                    into.setImageDrawable(null)
                    into.contentDescription = "Image no longer available"
                }
            }
        }
        synchronized(pending) { pending += task }
    }

    private fun showDetail(match: MatchResult) {
        val dialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val image = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
        }
        val info = TextView(this).apply {
            text = "${matchLabel(match.similarity)} · ${(match.similarity * 100).toInt()}%" +
                (if (match.confirmed) " · You confirmed this is you" else "") + "\n" + hostOf(match)
            textSize = 14f
            setTextColor(Color.WHITE)
            setPadding(dp(16), dp(12), dp(16), dp(8))
        }
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(16), dp(24))
        }
        fun action(label: String, block: () -> Unit) = MaterialButton(this).apply {
            text = label
            isAllCaps = false
            setOnClickListener { block() }
        }
        buttons.addView(action("Get it removed…") { RemovalActions.show(this, match.pageUrl, match.imageUrl) })
        buttons.addView(action("Open the page privately") { PrivateViewerActivity.open(this, match.pageUrl.ifBlank { match.imageUrl }) })
        buttons.addView(action("Close") { dialog.dismiss() })
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            addView(image, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(info)
            addView(buttons)
        }
        dialog.setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(column)
        })
        thumbs.get(match.imageUrl)?.let { image.setImageBitmap(it) }
        dialog.show()
        loader.submit {
            val full = try { PublicImageLoader.loadForDisplay(this, match.imageUrl, match.pageUrl.ifBlank { null }) } catch (_: Exception) { null }
            runOnUiThread {
                if (!dialog.isShowing) return@runOnUiThread
                if (full != null) image.setImageBitmap(full)
                else if (thumbs.get(match.imageUrl) == null) {
                    Toast.makeText(this, "This image is no longer available. It may have been removed.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // ---- Helpers ----------------------------------------------------------------------------------------

    private fun hostOf(match: MatchResult): String =
        (Uri.parse(match.pageUrl.ifBlank { match.imageUrl }).host ?: match.imageUrl).removePrefix("www.")

    private fun addMoreButton(action: () -> Unit) {
        val button = tabButton("Show more") {}
        button.setOnClickListener {
            content.removeView(button)
            action()
        }
        content.addView(button)
    }

    private fun row(title: String, subtitle: String, onClick: () -> Unit): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(12), dp(14), dp(12))
        background = GradientDrawable().apply {
            setColor(Color.rgb(25, 29, 43))
            cornerRadius = dp(12).toFloat()
        }
        addView(TextView(this@SavedImagesActivity).apply { text = title; textSize = 16f; setTextColor(Color.WHITE) })
        addView(TextView(this@SavedImagesActivity).apply { text = subtitle; textSize = 13f; alpha = 0.7f })
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(8)
        }
    }

    companion object {
        const val EXTRA_OPEN_PAST = "com.example.face.EXTRA_OPEN_PAST"
        private const val PAGE = 60
    }
}
