package com.example.face

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.widget.EditText
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
            status.text = "${referencePhotos.size} reference photo(s) selected. Start the website scan when ready."
        }
    }
    @Volatile private var scanning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        database = PublicSourceDatabase(this)

        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(24))
        }
        setToolPage("Image search", page)

        page.addView(TextView(this).apply {
            text = "Search providers open in your browser. The batch scanner below checks public sitemap and feed endpoints for image links. It does not bypass logins, CAPTCHAs, robots restrictions, or site protections."
            textSize = 15f
            setPadding(0, 0, 0, dp(16))
        })

        queryInput = EditText(this).apply {
            hint = "Search terms, usernames, or site names"
            minLines = 2
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        }
        page.addSpaced(queryInput, 12)

        page.addView(TextView(this).apply {
            text = "SearXNG instance address"
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
            text = "Supported public website database"
            textSize = 19f
            setPadding(0, dp(24), 0, dp(4))
        })
        page.addView(TextView(this).apply {
            text = "Add the public website or forum domain you want included. The scanner checks its robots.txt sitemap declarations, standard sitemap files, and common public feeds. A domain is not marked as searchable unless it actually exposes one of these endpoints."
            textSize = 13f
            setPadding(0, 0, 0, dp(10))
        })

        sourceNameInput = EditText(this).apply {
            hint = "Site or forum name (optional)"
            setSingleLine(true)
        }
        page.addView(sourceNameInput)
        sourceUrlInput = EditText(this).apply {
            hint = "example.com"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        page.addView(sourceUrlInput)
        page.addSpaced(button("Add website to database") { addSource() }, 8)
        bulkSourcesInput = EditText(this).apply {
            hint = "Bulk import: one domain per line, or Name | domain"
            minLines = 4
            gravity = android.view.Gravity.TOP
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        page.addView(bulkSourcesInput)
        page.addSpaced(button("Import website list") { importSources() }, 8)
        page.addSpaced(button("Choose photo(s) to search for") { referencePicker.launch(arrayOf("image/*")) }, 8)
        page.addSpaced(button("Scan all websites") { scanAllSources() }, 8)
        page.addSpaced(button("Scan websites and match my photo(s)") {
            if (referencePhotos.isEmpty()) {
                Toast.makeText(this, "Choose at least one reference photo first.", Toast.LENGTH_LONG).show()
            } else {
                scanAllSources(matchSelectedPhotos = true)
            }
        }, 8)
        page.addSpaced(button("Browse collected image links") { browseCollectedLinks() }, 8)
        page.addSpaced(button("Open face matcher") { startActivity(Intent(this, IdentityMatchActivity::class.java)) }, 8)

        sourceList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        page.addView(sourceList)

        status = TextView(this).apply {
            text = "No scan has been run yet."
            textSize = 14f
            setPadding(0, dp(14), 0, dp(8))
        }
        page.addView(status)
        refreshSources()
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

    private fun scanAllSources(matchSelectedPhotos: Boolean = false) {
        if (scanning) {
            Toast.makeText(this, "A scan is already running.", Toast.LENGTH_SHORT).show()
            return
        }
        val selected = database.sources()
        if (selected.isEmpty()) {
            Toast.makeText(this, "There are no websites in the database yet.", Toast.LENGTH_LONG).show()
            return
        }
        scanning = true
        status.text = "Starting batch scan of ${selected.size} public website(s)…"
        scanExecutor.execute {
            var addedTotal = 0
            var completed = 0
            for (source in selected) {
                if (Thread.currentThread().isInterrupted) break
                runOnUiThread { status.text = "Checking ${source.name} (${completed + 1}/${selected.size})…" }
                try {
                    val images = PublicSourceScanner.scan(source)
                    val added = database.saveCandidates(images)
                    addedTotal += added
                    completed++
                    database.updateSourceStatus(source.id, "Checked; ${images.size} link(s), ${added} new")
                } catch (e: Exception) {
                    completed++
                    database.updateSourceStatus(source.id, "Could not scan: ${e.localizedMessage ?: "network error"}")
                }
                // Keep requests sequential and avoid hammering one host after another.
                try { Thread.sleep(1200) } catch (_: InterruptedException) { break }
            }
            val done = completed
            val added = addedTotal
            runOnUiThread {
                scanning = false
                status.text = "Batch finished: checked $done of ${selected.size} websites; found $added new image link(s). Total stored: ${database.candidateCount()}."
                refreshSourceRowsOnly()
                if (matchSelectedPhotos && referencePhotos.isNotEmpty()) {
                    val intent = Intent(this, IdentityMatchActivity::class.java).apply {
                        putParcelableArrayListExtra(IdentityMatchActivity.EXTRA_REFERENCE_URIS, ArrayList(referencePhotos))
                        putExtra(IdentityMatchActivity.EXTRA_WEB_SCAN_MODE, true)
                    }
                    startActivity(intent)
                }
            }
        }
    }

    private fun refreshSourceRowsOnly() {
        val items = database.sources()
        sourceList.removeAllViews()
        for (source in items) {
            sourceList.addView(TextView(this).apply {
                text = "${source.name}  •  ${source.baseUrl}"
                textSize = 13f
                setPadding(dp(4), dp(7), dp(4), dp(7))
            })
        }
    }
}
