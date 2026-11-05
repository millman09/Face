package com.example.face

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.material.button.MaterialButton
import java.util.concurrent.Executors

/**
 * Browser search plus a local registry of public websites whose published
 * sitemap/feed endpoints can be checked in one batch.
 */
class WebScanActivity : AppCompatActivity() {

    private lateinit var queryInput: EditText
    private lateinit var searxInput: EditText
    private lateinit var status: TextView
    private lateinit var referencePreview: LinearLayout
    private lateinit var referenceCount: TextView
    private lateinit var startSearchButton: MaterialButton
    private lateinit var sourceNameInput: EditText
    private lateinit var sourceUrlInput: EditText
    private lateinit var bulkSourcesInput: EditText
    private lateinit var sourceList: LinearLayout
    private lateinit var database: PublicSourceDatabase
    private val scanExecutor = Executors.newSingleThreadExecutor()
    private var referencePhotos: List<Uri> = emptyList()
    private val referencePicker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            referencePhotos = uris.distinct()
            referencePhotos.forEach { uri ->
                try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
            }
            renderReferencePhotos()
            status.text = "${referencePhotos.size} reference photo(s) selected. Ready to search."
            startSearchButton.isEnabled = true
        }
    }
    @Volatile private var scanning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        database = PublicSourceDatabase(this)

        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(28))
            setBackgroundColor(android.graphics.Color.rgb(13, 16, 24))
        }
        setToolPage("Deep image search", page)

        page.addView(TextView(this).apply {
            text = "FACE SEARCH  /  PRIVATE WORKSPACE"
            textSize = 11f
            letterSpacing = 0.12f
            setTextColor(android.graphics.Color.rgb(155, 139, 255))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(8), 0, dp(10))
        })
        page.addView(TextView(this).apply {
            text = "Search the web\nfor your face."
            textSize = 31f
            setTextColor(android.graphics.Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setLineSpacing(dp(2).toFloat(), 1f)
            setPadding(0, 0, 0, dp(12))
        })
        page.addView(TextView(this).apply {
            text = "Select your reference photos and scan supported public image sources. Matches are compared on this device."
            textSize = 15f
            setTextColor(android.graphics.Color.rgb(190, 195, 210))
            setLineSpacing(dp(3).toFloat(), 1.04f)
            setPadding(0, 0, 0, dp(20))
        })
        val uploadCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.rgb(25, 29, 43))
                setStroke(dp(1), android.graphics.Color.rgb(91, 78, 153))
                cornerRadius = dp(18).toFloat()
            }
        }
        uploadCard.addView(TextView(this).apply {
            text = "YOUR REFERENCE PHOTOS"
            textSize = 11f
            letterSpacing = 0.10f
            setTextColor(android.graphics.Color.rgb(173, 160, 255))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        referenceCount = TextView(this).apply {
            text = "No photos selected"
            textSize = 17f
            setTextColor(android.graphics.Color.WHITE)
            setPadding(0, dp(8), 0, dp(10))
        }
        uploadCard.addView(referenceCount)
        referencePreview = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val previewScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(referencePreview)
        }
        uploadCard.addView(previewScroll)
        uploadCard.addView(button("＋  Add reference photos") {
            referencePicker.launch(arrayOf("image/*"))
        }.apply {
            setBackgroundColor(android.graphics.Color.rgb(105, 83, 230))
            setTextColor(android.graphics.Color.WHITE)
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(12)
        })
        page.addView(uploadCard, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(20)
        })

        queryInput = EditText(this).apply {
            hint = "Optional search terms or usernames"
            minLines = 2
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        }
        page.addSpaced(queryInput, 12)

        page.addView(TextView(this).apply {
            text = "IMAGE SEARCH PROVIDER"
            textSize = 14f
            setPadding(0, dp(4), 0, dp(4))
        })
        searxInput = EditText(this).apply {
            hint = "https://your-trusted-searxng-instance.example"
            setSingleLine(true)
            setText("https://searx.be")
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        }
        page.addView(searxInput)
        page.addView(TextView(this).apply {
            text = "SearXNG is community-hosted. Its operator may see your searches. Use an instance you trust."
            textSize = 13f
            setPadding(0, dp(4), 0, dp(12))
        })

        page.addSpaced(button("Search Yandex, SearXNG and DuckDuckGo") { searchAll() })

        page.addView(TextView(this).apply {
            text = "AUTOMATIC PUBLIC-SOURCE SCAN\nThe built-in source catalogue is checked in one run. Coverage depends on public sitemaps, feeds, and accessible image links. No logins, CAPTCHAs, paywalls, or site protections are bypassed."
            textSize = 14f
            setPadding(0, dp(16), 0, dp(12))
        })

        startSearchButton = button("Search for matches") { launchAutomaticScan() }.apply {
            isEnabled = false
            textSize = 16f
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setBackgroundColor(android.graphics.Color.rgb(105, 83, 230))
            setTextColor(android.graphics.Color.WHITE)
        }
        page.addSpaced(startSearchButton, 8)

        status = TextView(this).apply {
            text = "READY  ·  ${database.sources().size} public sources available"
            textSize = 14f
            setPadding(0, dp(14), 0, dp(8))
        }
        page.addView(status)
        status.text = "READY  ·  ${database.sources().size} public sources available. All enabled sources are included automatically."
        renderReferencePhotos()
    }

    private fun renderReferencePhotos() {
        referencePreview.removeAllViews()
        referenceCount.text = if (referencePhotos.isEmpty()) "No photos selected" else
            "${referencePhotos.size} photo${if (referencePhotos.size == 1) "" else "s"} ready to scan"
        referencePhotos.forEachIndexed { index, uri ->
            val image = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(88), dp(88)).apply { marginEnd = dp(8) }
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(android.graphics.Color.rgb(43, 47, 62))
                    cornerRadius = dp(12).toFloat()
                }
                clipToOutline = true
                try { setImageURI(uri) } catch (_: Exception) { setImageDrawable(null) }
                contentDescription = "Reference photo ${index + 1}"
                setOnClickListener {
                    androidx.appcompat.app.AlertDialog.Builder(this@WebScanActivity)
                        .setTitle("Reference photo ${index + 1}")
                        .setMessage("Remove this photo from the current search?")
                        .setNegativeButton("Keep", null)
                        .setPositiveButton("Remove") { _, _ ->
                            referencePhotos = referencePhotos.filterIndexed { i, _ -> i != index }
                            renderReferencePhotos()
                            startSearchButton.isEnabled = referencePhotos.isNotEmpty()
                            status.text = if (referencePhotos.isEmpty()) "Choose at least one reference photo to begin." else
                                "${referencePhotos.size} reference photo(s) ready."
                        }.show()
                }
            }
            referencePreview.addView(image)
        }
    }
    override fun onDestroy() {
        scanExecutor.shutdownNow()
        database.close()
        super.onDestroy()
    }

    private fun button(label: String, action: () -> Unit) = MaterialButton(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { action() }
    }

    private fun currentQuery(): String? {
        val query = queryInput.text.toString().trim()
        if (query.isBlank()) {
            Toast.makeText(this, "Enter search terms first.", Toast.LENGTH_SHORT).show()
            return null
        }
        return query
    }

    private fun yandexUrl(query: String): String =
        Uri.parse("https://yandex.com/images/search").buildUpon()
            .appendQueryParameter("text", query).build().toString()

    private fun duckDuckGoUrl(query: String): String =
        Uri.parse("https://duckduckgo.com/").buildUpon()
            .appendQueryParameter("q", query).appendQueryParameter("ia", "images")
            .appendQueryParameter("iax", "images").build().toString()

    private fun searxUrl(query: String): String? {
        val raw = searxInput.text.toString().trim()
        val base = try { Uri.parse(raw) } catch (_: Exception) { null }
        if (base == null || base.scheme != "https" || base.host.isNullOrBlank()) {
            Toast.makeText(this, "Enter a valid HTTPS SearXNG instance address.", Toast.LENGTH_LONG).show()
            return null
        }
        return Uri.parse(raw.trimEnd('/') + "/search").buildUpon()
            .appendQueryParameter("q", query)
            .appendQueryParameter("categories", "images")
            .appendQueryParameter("language", "en").build().toString()
    }

    // Android only lets an app open one browser page at a time while it is in the
    // background, so the three searches open in turn: one now, the next each time
    // you come back to this screen.
    private val pendingSearches = ArrayDeque<String>()

    private fun searchAll() {
        val query = currentQuery() ?: return
        pendingSearches.clear()
        pendingSearches.addAll(listOfNotNull(yandexUrl(query), searxUrl(query), duckDuckGoUrl(query)))
        if (pendingSearches.isEmpty()) return
        status.text = "Opening ${pendingSearches.size} searches. Press back after each one to open the next."
        openNextSearch()
    }

    private fun openNextSearch() {
        val url = pendingSearches.removeFirstOrNull() ?: return
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
            Toast.makeText(this, "No browser could open the search page.", Toast.LENGTH_LONG).show()
            pendingSearches.clear()
        }
    }

    override fun onResume() {
        super.onResume()
        if (pendingSearches.isNotEmpty()) openNextSearch()
    }

    private fun openLink(link: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)))
            status.text = "Search page opened in your browser."
        } catch (_: Exception) {
            Toast.makeText(this, "Unable to open this search page.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun addSource() {
        val raw = sourceUrlInput.text.toString()
        val normalized = PublicSourceDatabase.normalizeBaseUrl(raw)
        if (normalized == null) {
            Toast.makeText(this, "Enter a valid public website domain or HTTP(S) address.", Toast.LENGTH_LONG).show()
            return
        }
        val added = database.addSource(sourceNameInput.text.toString(), normalized)
        if (!added) {
            Toast.makeText(this, "That website is already in the database.", Toast.LENGTH_SHORT).show()
            return
        }
        sourceNameInput.text.clear()
        sourceUrlInput.text.clear()
        status.text = "Website added. It will be checked during the next batch scan."
        refreshSources()
    }


    private fun importSources() {
        val lines = bulkSourcesInput.text.toString().lines().map { it.trim() }.filter { it.isNotBlank() }
        if (lines.isEmpty()) {
            Toast.makeText(this, "Paste one domain per line first.", Toast.LENGTH_SHORT).show()
            return
        }
        var added = 0
        var skipped = 0
        for (line in lines) {
            val parts = line.split("|", limit = 2)
            val name = if (parts.size == 2) parts[0].trim() else ""
            val address = if (parts.size == 2) parts[1].trim() else parts[0].trim()
            if (PublicSourceDatabase.normalizeBaseUrl(address) != null && database.addSource(name, address)) added++ else skipped++
        }
        bulkSourcesInput.text.clear()
        status.text = "Bulk import finished: $added added, $skipped invalid or already in the database."
        refreshSources()
    }

    private fun refreshSources() {
        sourceList.removeAllViews()
        val items = database.sources()
        if (items.isEmpty()) {
            sourceList.addView(TextView(this).apply {
                text = "No websites added yet. Add the public domains you want scanned."
                textSize = 14f
                setPadding(0, dp(8), 0, dp(8))
            })
        }
        for (source in items) {
            sourceList.addView(TextView(this).apply {
                text = "${source.name}  •  ${source.baseUrl}"
                textSize = 13f
                setPadding(dp(4), dp(7), dp(4), dp(7))
            })
        }
        status.text = "${items.size} website(s) in database • ${database.candidateCount()} unique image link(s) collected."
    }

    private fun browseCollectedLinks() {
        val candidates = database.allCandidates(100)
        if (candidates.isEmpty()) {
            Toast.makeText(this, "No image links have been collected yet. Run a batch scan first.", Toast.LENGTH_LONG).show()
            return
        }
        val labels = candidates.mapIndexed { index, item ->
            "${index + 1}. ${Uri.parse(item.pageUrl).host ?: item.sourceBaseUrl}  •  ${Uri.parse(item.imageUrl).lastPathSegment ?: "Image link"}"
        }.toTypedArray()
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Collected public image links")
            .setItems(labels) { _, which ->
                val selected = candidates[which]
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(selected.imageUrl)))
                    status.text = "Opened the selected image link in your browser."
                } catch (_: Exception) {
                    Toast.makeText(this, "Unable to open that image link.", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    /** Opens the live results page immediately. Source discovery and face matching run there. */
    private fun launchAutomaticScan() {
        if (referencePhotos.isEmpty()) {
            Toast.makeText(this, "Choose at least one reference photo first.", Toast.LENGTH_LONG).show()
            return
        }
        val sourceCount = database.sources().count { it.enabled }
        if (sourceCount == 0) {
            Toast.makeText(this, "There are no enabled public sources to scan.", Toast.LENGTH_LONG).show()
            return
        }
        val intent = Intent(this, IdentityMatchActivity::class.java).apply {
            putParcelableArrayListExtra(IdentityMatchActivity.EXTRA_REFERENCE_URIS, ArrayList(referencePhotos))
            putExtra(IdentityMatchActivity.EXTRA_WEB_SCAN_MODE, true)
            putExtra(IdentityMatchActivity.EXTRA_AUTO_START, true)
        }
        startActivity(intent)
    }

}
