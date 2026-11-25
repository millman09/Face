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
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.concurrent.Executors

/**
 * Browser search plus a local registry of public websites whose published
 * sitemap/feed endpoints can be checked in one batch.
 */
class WebScanActivity : AppCompatActivity() {

    private lateinit var queryInput: EditText
    private lateinit var cityInput: EditText
    private lateinit var stateInput: EditText
    private lateinit var status: TextView
    private lateinit var referencePreview: LinearLayout
    private lateinit var referenceCount: TextView
    private lateinit var startSearchButton: MaterialButton
    private lateinit var continueSearchButton: MaterialButton
    private lateinit var keywordResultsContainer: LinearLayout
    private lateinit var keywordProgressBar: ProgressBar
    private lateinit var keywordProgressText: TextView
    private var groupButtons: List<MaterialButton> = emptyList()
    private lateinit var database: PublicSourceDatabase
    private val scanExecutor = Executors.newSingleThreadExecutor()
    private val keywordExecutor = Executors.newSingleThreadExecutor()
    private var referencePhotos: List<Uri> = emptyList()
    // The search waiting on the "run in the background" prompt; it starts once the prompt closes, whatever the answer.
    private var groupAwaitingBatteryPrompt: SourceGroup? = null
    private var searchAwaitingBatteryPrompt = false
    private val batteryPrompt = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (!BackgroundRunHelper.isExempt(this)) BackgroundRunHelper.rememberDeclined(this)
        if (searchAwaitingBatteryPrompt) {
            searchAwaitingBatteryPrompt = false
            startNewScan(groupAwaitingBatteryPrompt, batteryChecked = true)
        }
    }

    private val referencePicker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            referencePhotos = uris.distinct()
            referencePhotos.forEach { uri ->
                try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
            }
            renderReferencePhotos()
            status.text = "${referencePhotos.size} reference photo(s) selected. Ready to search."
            setSearchEnabled(true)
        }
    }
    @Volatile private var scanning = false
    @Volatile private var catalogueReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        database = PublicSourceDatabase(applicationContext)

        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(28))
            setBackgroundColor(android.graphics.Color.rgb(13, 16, 24))
        }
        setToolPage("Deep image search", page)

        page.addView(TextView(this).apply {
            text = "Find your face online"
            textSize = 26f
            setTextColor(android.graphics.Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(4), 0, dp(4))
        })
        page.addView(TextView(this).apply {
            text = "Add photos of yourself, then search. Matching happens on this phone."
            textSize = 14f
            setTextColor(android.graphics.Color.rgb(170, 176, 194))
            setPadding(0, 0, 0, dp(16))
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
            text = "YOUR PHOTOS"
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

        // Face search: one clear main action, the wide reverse search, and the rest tucked under "More options".
        val searchCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(12))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.rgb(25, 29, 43))
                cornerRadius = dp(18).toFloat()
            }
        }
        searchCard.addView(TextView(this).apply {
            text = "SEARCH FOR YOUR FACE"
            textSize = 11f
            letterSpacing = 0.10f
            setTextColor(android.graphics.Color.rgb(173, 160, 255))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, dp(8))
        })
        fun primary(button: MaterialButton) = button.apply {
            textSize = 16f
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundColor(android.graphics.Color.rgb(105, 83, 230))
            setTextColor(android.graphics.Color.WHITE)
        }
        fun outlined(label: String, action: () -> Unit) =
            MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = label
                isAllCaps = false
                setOnClickListener { action() }
            }
        fun hint(text: String) = TextView(this).apply {
            this.text = text
            textSize = 12f
            setTextColor(android.graphics.Color.rgb(150, 157, 178))
            setPadding(dp(2), dp(2), dp(2), dp(10))
        }
        continueSearchButton = primary(button("Continue current search") { openRunningScan() }).apply {
            visibility = android.view.View.GONE
        }
        searchCard.addView(continueSearchButton)
        startSearchButton = primary(button("Scan sites for my face") { launchAutomaticScan() }).apply { isEnabled = false }
        searchCard.addView(startSearchButton)
        searchCard.addView(hint("Checks the built-in list of sites and compares every face it finds with your photos."))
        searchCard.addView(outlined("Reverse image search my photos") { chooseReverseSearch() })
        searchCard.addView(hint("Yandex, Google Lens or Bing search their whole index for your photo. Finds the most."))

        val moreOptions = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = android.view.View.GONE
        }
        val moreToggle = TextView(this).apply {
            text = "More options  ▾"
            textSize = 14f
            setTextColor(android.graphics.Color.rgb(173, 160, 255))
            setPadding(dp(2), dp(6), dp(2), dp(6))
            setOnClickListener {
                val open = moreOptions.visibility != android.view.View.VISIBLE
                moreOptions.visibility = if (open) android.view.View.VISIBLE else android.view.View.GONE
                text = if (open) "More options  ▴" else "More options  ▾"
            }
        }
        searchCard.addView(moreToggle)
        groupButtons = SourceGroup.values().map { group ->
            outlined(group.buttonLabel) { launchAutomaticScan(group) }.apply { isEnabled = false }
        }
        groupButtons.forEach { moreOptions.addView(it) }
        moreOptions.addView(hint("Faster scans of one kind of site."))
        moreOptions.addView(outlined("Recheck all saved images") { confirmRecheckAll() })
        moreOptions.addView(hint("Compare everything found before with your current photos again."))
        searchCard.addView(moreOptions)

        status = TextView(this).apply {
            text = "Preparing the site list…"
            textSize = 13f
            setTextColor(android.graphics.Color.rgb(190, 195, 210))
            setPadding(dp(2), dp(8), dp(2), 0)
        }
        searchCard.addView(status)
        page.addView(searchCard, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(24)
        })

        queryInput = EditText(this).apply {
            hint = "Keywords, names, usernames, or other terms"
            minLines = 2
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        }
        page.addSpaced(queryInput, 12)

        page.addView(TextView(this).apply {
            text = "OPTIONAL LOCATION KEYWORDS"
            textSize = 12f
            letterSpacing = 0.08f
            setTextColor(android.graphics.Color.rgb(173, 160, 255))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(4), 0, dp(6))
        })
        val locationRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        cityInput = EditText(this).apply {
            hint = "City"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_NEXT
        }
        stateInput = EditText(this).apply {
            hint = "State"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_DONE
        }
        locationRow.addView(cityInput, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) })
        locationRow.addView(stateInput, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(6) })
        page.addView(locationRow)

        page.addView(TextView(this).apply {
            text = "These terms are optional. They are added to browser searches and help prioritize public results whose post text or page URL mentions the location."
            textSize = 12f
            setTextColor(android.graphics.Color.rgb(165, 172, 190))
            setPadding(0, dp(2), 0, dp(12))
        })


        // Dedicated text-only self-search section for the three requested imageboards.
        page.addView(TextView(this).apply {
            text = "IMAGEBOARD SELF-SEARCH"
            textSize = 12f
            letterSpacing = 0.10f
            setTextColor(android.graphics.Color.rgb(173, 160, 255))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(2), dp(12), dp(2), dp(4))
        })
        page.addView(TextView(this).apply {
            text = "Search public indexed pages on Anonibx, SnapWins, and Xpics using your own name, usernames, and optional location terms. No photo is required."
            textSize = 12f
            setTextColor(android.graphics.Color.rgb(165, 172, 190))
            setPadding(dp(2), 0, dp(2), dp(8))
        })
        page.addSpaced(button("Search Anonibx + SnapWins + Xpics") { searchRequestedImageboardsSelfOnly() })
        page.addSpaced(button("Find matching imageboard links · no photo needed") { searchImageboardsOnly() })
        keywordResultsContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        page.addView(keywordResultsContainer, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(12); bottomMargin = dp(16) })

        keywordProgressText = TextView(this).apply {
            text = "Search progress: waiting"
            textSize = 13f
            setTextColor(android.graphics.Color.rgb(190, 195, 210))
            setPadding(0, dp(4), 0, dp(4))
            visibility = android.view.View.GONE
        }
        page.addView(keywordProgressText)
        keywordProgressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            progressTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.rgb(125, 105, 255))
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.rgb(54, 59, 77))
            visibility = android.view.View.GONE
        }
        page.addView(keywordProgressBar, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(10)
        ).apply { bottomMargin = dp(12) })

        renderReferencePhotos()
        // The first SQLite open may create and seed the full catalogue. Do it off the UI thread.
        scanExecutor.execute {
            val count = database.sources().size
            runOnUiThread {
                if (!isFinishing && !isDestroyed) {
                    catalogueReady = true
                    status.text = if (referencePhotos.isEmpty()) "Ready · $count sites. Add a photo to start."
                        else "Ready · $count sites."
                    setSearchEnabled(referencePhotos.isNotEmpty())
                    refreshRunningScanButtons()
                }
            }
        }
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
                            setSearchEnabled(referencePhotos.isNotEmpty())
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
        keywordExecutor.shutdownNow()
        database.close()
        super.onDestroy()
    }

    private fun button(label: String, action: () -> Unit) = MaterialButton(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { action() }
    }

    private fun locationKeywords(): String =
        listOf(cityInput.text.toString().trim(), stateInput.text.toString().trim())
            .filter { it.isNotBlank() }.joinToString(" ")

    private fun currentQuery(): String? {
        val query = listOf(queryInput.text.toString().trim(), locationKeywords())
            .filter { it.isNotBlank() }.joinToString(" ")
        if (query.isBlank()) {
            Toast.makeText(this, "Enter search terms, a city, or a state.", Toast.LENGTH_SHORT).show()
            return null
        }
        return query
    }

    /** Search public 4chan archive indexes directly, without navigating ad-heavy board pages. */
    private fun fourChanArchiveUrl(query: String): String =
        Uri.parse("https://duckduckgo.com/").buildUpon()
            .appendQueryParameter(
                "q",
                "($query) (site:archive.4plebs.org OR site:warosu.org) -site:archive.4plebs.org/mlpol -site:warosu.org/jp -site:warosu.org/vt"
            )
            .appendQueryParameter("ia", "images")
            .appendQueryParameter("iax", "images")
            .build().toString()

    /** Find indexed public Anonibx threads/posts without loading ad-heavy board indexes. */
    private fun anonibxArchiveUrl(query: String): String =
        Uri.parse("https://duckduckgo.com/").buildUpon()
            .appendQueryParameter(
                "q",
                "($query) site:anonibx.com (thread OR archive OR post OR image OR gallery)"
            )
            .appendQueryParameter("ia", "images")
            .appendQueryParameter("iax", "images")
            .build().toString()

    /** Open one of the user-requested public state catalog pages in the browser. */
    private fun openAnonibxCatalog(stateCode: String) {
        val url = when (stateCode.lowercase()) {
            "illi", "il" -> "https://anonibx.com/illi/catalog"
            "tx" -> "https://anonibx.com/tx/catalog"
            else -> return
        }
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
            Toast.makeText(this, "No browser could open the Anonibx catalog.", Toast.LENGTH_LONG).show()
        }
    }

    /** Find indexed public Motherless galleries, posts and older media pages. */
    private fun motherlessArchiveUrl(query: String): String =
        Uri.parse("https://duckduckgo.com/").buildUpon()
            .appendQueryParameter(
                "q",
                "($query) site:motherless.com (gallery OR group OR user OR post OR image OR video)"
            )
            .appendQueryParameter("ia", "images")
            .appendQueryParameter("iax", "images")
            .build().toString()

    /** Search only public search-engine indexes for SnapWins pages; never fetch or download its media. */
    private fun snapwinsArchiveUrl(query: String): String =
        Uri.parse("https://duckduckgo.com/").buildUpon()
            .appendQueryParameter("q", "($query) site:snapwins.net")
            .appendQueryParameter("ia", "images")
            .appendQueryParameter("iax", "images")
            .build().toString()

    /** Search only public search-engine indexes for Xpics pages; never fetch or download its media. */
    private fun xpicsArchiveUrl(query: String): String =
        Uri.parse("https://duckduckgo.com/").buildUpon()
            .appendQueryParameter("q", "($query) site:xpics.me")
            .appendQueryParameter("ia", "images")
            .appendQueryParameter("iax", "images")
            .build().toString()

    // Android only lets an app open one browser page at a time while it is in the
    // background, so the three searches open in turn: one now, the next each time
    // you come back to this screen.
    private val pendingSearches = ArrayDeque<String>()

    private fun imageboardSearchUrls(query: String): List<String> = listOf(
        fourChanArchiveUrl(query),
        anonibxArchiveUrl(query),
        motherlessArchiveUrl(query),
        snapwinsArchiveUrl(query),
        xpicsArchiveUrl(query)
    )

    private data class KeywordLink(val title: String, val url: String, val source: String)

    /** Keyword-only search expands location terms so a city is not lost inside one overly narrow query. */
    private fun searchImageboardsOnly() {
        val rawKeywords = queryInput.text.toString().trim()
        val city = cityInput.text.toString().trim()
        val state = stateInput.text.toString().trim()
        if (rawKeywords.isBlank()) {
            Toast.makeText(this, "Enter your own name, username, or identifying keyword first.", Toast.LENGTH_LONG).show()
            return
        }
        val locationVariants = locationQueryVariants(city, state)
        val nameVariants = nameSearchVariants(rawKeywords)
        val queryLabel = listOf(rawKeywords, city, state).filter { it.isNotBlank() }.joinToString(" · ")
        keywordResultsContainer.removeAllViews()
        keywordProgressBar.progress = 0
        keywordProgressBar.visibility = android.view.View.VISIBLE
        keywordProgressText.visibility = android.view.View.VISIBLE
        keywordProgressText.text = "Search progress: 0% · preparing searches…"
        status.text = "Expanding your identifying terms and optional location variations: $queryLabel"
        scanExecutor.execute {
            val siteQueries = listOf(
                "(site:archive.4plebs.org OR site:warosu.org)",
                "site:anonibx.com",
                "site:motherless.com",
                "(site:4chan.org OR site:4channel.org)",
                "site:snapwins.net",
                "site:xpics.me"
            )
            val searches = linkedSetOf<String>()
            // Search only for the user's own identifying terms. Expand spelling
            // variants and combine them with optional location terms; never run
            // broad gender/state-only searches that could target unrelated people.
            nameVariants.forEach { name ->
                siteQueries.forEach { site -> searches += "($name) $site" }
                locationVariants.filter { it.isNotBlank() }.forEach { location ->
                    siteQueries.forEach { site -> searches += "($name $location) $site" }
                }
            }
            val found = linkedMapOf<String, KeywordLink>()
            val providerOutcomes = linkedMapOf<String, Int>()
            runOnUiThread {
                if (!isFinishing && !isDestroyed) {
                    keywordProgressText.text = "Search progress: 0% · 0 of ${searches.size} searches completed"
                    status.text = "Searching ${searches.size} public-index queries…"
                }
            }
            searches.forEachIndexed { index, search ->
                val result = fetchKeywordLinks(search)
                result.links.forEach { found.putIfAbsent(it.url, it) }
                result.status.split(" · ").forEach { outcome ->
                    val provider = outcome.substringBefore(":")
                    providerOutcomes[provider] = (providerOutcomes[provider] ?: 0) + 1
                }
                val completed = index + 1
                val percent = (completed * 100 / searches.size.coerceAtLeast(1))
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        keywordProgressBar.progress = percent
                        keywordProgressText.text = "Search progress: $percent% · $completed of ${searches.size} searches completed"
                        status.text = "Checking public indexes… $percent% · $completed of ${searches.size} searches · ${found.size} links found · " +
                            providerOutcomes.entries.joinToString(", ") { "${it.key}: ${it.value} checks" }
                    }
                }
            }
            runOnUiThread {
                if (!isFinishing && !isDestroyed) {
                    keywordProgressBar.progress = 100
                    keywordProgressText.text = "Search progress: 100% · finished ${searches.size} searches · ${found.size} links found"
                    renderKeywordLinks(found.values.toList(), queryLabel)
                    if (found.isEmpty()) status.text = "Search finished (100%). No indexed links were extracted. Provider checks: ${providerOutcomes.entries.joinToString(", ") { "${it.key}: ${it.value} checks" }}. Try the browser-search button to compare live results."
                }
            }
        }
    }

    /**
     * Walk publicly linked catalog, pagination, and thread pages under one board.
     * Searches only the user's entered identifying terms; never fetches media.
     * Respects robots.txt and caps work to 250 public HTML pages per board.
     */
    private fun fetchAnonibxCatalogMatches(
        board: String,
        names: List<String>,
        onProgress: (checked: Int, limit: Int, matches: Int) -> Unit = { _, _, _ -> }
    ): Pair<List<KeywordLink>, String> {
        val base = "https://anonibx.com"
        val root = "/$board"
        val terms = names.map { normalizeNameForMatch(it) }.filter { it.length >= 3 }.distinct()
        if (terms.isEmpty()) return emptyList<KeywordLink>() to "no usable name variants"

        fun readPage(url: java.net.URL, limit: Int): Pair<Int, String> {
            val c = url.openConnection() as java.net.HttpURLConnection
            c.requestMethod = "GET"
            c.connectTimeout = 7000
            c.readTimeout = 9000
            c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", "FacePersonalSearch/1.1 (public page search)")
            c.setRequestProperty("Accept", "text/html,application/xhtml+xml")
            try {
                val code = c.responseCode
                // Never consume a page after the server redirects outside the requested host.
                val finalUrl = c.url
                if (!finalUrl.protocol.equals("https", true) ||
                    !finalUrl.host.equals("anonibx.com", true)) return code to ""
                if (code !in 200..299) return code to ""
                val type = c.contentType.orEmpty().lowercase()
                if (type.isNotBlank() && !type.contains("html") && !type.contains("text")) return code to ""
                val body = c.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                    val out = StringBuilder()
                    val buffer = CharArray(8192)
                    while (out.length < limit) {
                        val n = reader.read(buffer, 0, minOf(buffer.size, limit - out.length))
                        if (n < 0) break
                        out.append(buffer, 0, n)
                    }
                    out.toString()
                }
                return code to body
            } finally { c.disconnect() }
        }

        try {
            val (robotsCode, robots) = readPage(java.net.URL("$base/robots.txt"), 128 * 1024)
            if (robotsCode !in 200..299 && robotsCode != 404) return emptyList<KeywordLink>() to "robots.txt HTTP $robotsCode"
            val disallowed = robots.lineSequence().map { it.substringBefore("#").trim() }
                .filter { it.startsWith("Disallow:", true) }
                .map { it.substringAfter(":").trim() }.filter { it.isNotBlank() }.toList()
            fun allowed(path: String) = disallowed.none { it == "/" || path.startsWith(it.removeSuffix("*")) }
            if (!allowed("$root/catalog")) return emptyList<KeywordLink>() to "$board catalog disallowed by robots.txt"

            val pending = ArrayDeque<String>()
            val visited = linkedSetOf<String>()
            val matches = linkedMapOf<String, KeywordLink>()
            pending.add("$base$root/catalog")
            var loaded = 0
            var failed = 0
            var discovered = 0
            val pageLimit = 250
            val anchor = Regex("""<a\b([^>]*)>(.*?)</a>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                    val href = Regex("""href\s*=\s*["']([^"'#]+)["']""", RegexOption.IGNORE_CASE)
            val title = Regex("""(?is)<title[^>]*>(.*?)</title>""")
            val skip = setOf("jpg", "jpeg", "png", "gif", "webp", "mp4", "webm", "pdf", "css", "js", "zip")

            while (pending.isNotEmpty() && loaded < pageLimit) {
                val current = pending.removeFirst()
                if (!visited.add(current)) continue
                val path = Uri.parse(current).path.orEmpty()
                if (!path.startsWith("$root/") || !allowed(path)) continue
                val (code, html) = readPage(java.net.URL(current), 1536 * 1024)
                if (code !in 200..299 || html.isBlank()) { failed++; onProgress(loaded + failed, pageLimit, matches.size); continue }
                loaded++
                onProgress(loaded + failed, pageLimit, matches.size)

                val cleanHtml = html.replace(Regex("""(?is)<(script|style|noscript)[^>]*>.*?</\1>"""), " ")
                val visible = android.text.Html.fromHtml(
                    cleanHtml.replace(Regex("""(?i)<br\s*/?>|</p>|</div>|</li>"""), " "),
                    android.text.Html.FROM_HTML_MODE_LEGACY
                ).toString()
                val pageTitle = title.find(cleanHtml)?.groupValues?.get(1).orEmpty()
                val normalizedPage = normalizeNameForMatch(pageTitle + " " + visible)
                val hits = terms.filter { normalizedPage.contains(it) }

                anchor.findAll(html).forEach { a ->
                    val raw = href.find(a.groupValues[1])?.groupValues?.get(1) ?: return@forEach
                    val targetUrl = try {
                        java.net.URL(java.net.URL(current), android.text.Html.fromHtml(raw, android.text.Html.FROM_HTML_MODE_LEGACY).toString())
                    } catch (_: Exception) { return@forEach }
                    if (targetUrl.protocol != "https" || !targetUrl.host.equals("anonibx.com", true)) return@forEach
                    val target = Uri.parse(targetUrl.toString())
                    val targetPath = target.path.orEmpty()
                    if (!targetPath.startsWith("$root/") || !allowed(targetPath)) return@forEach
                    if (targetPath.substringAfterLast('.', "").lowercase() in skip) return@forEach
                    if (targetPath.contains("/login", true) || targetPath.contains("/report", true)) return@forEach
                    discovered++
                    if (visited.size + pending.size < pageLimit && targetPath.length < 300) {
                        val next = target.buildUpon().fragment(null).build().toString()
                        if (next !in visited && next !in pending) pending.add(next)
                    }
                }

                if (hits.isNotEmpty()) {
                    val label = (pageTitle + " · " + hits.joinToString(", ")).trim().take(180)
                        .ifBlank { "Anonibx " + board + " public page match" }
                    matches.putIfAbsent(current, KeywordLink(label, current, "Anonibx " + board + " public page"))
                }
                if (pending.isNotEmpty()) Thread.sleep(350)
            }
            val coverage = if (pending.isNotEmpty()) "stopped at " + pageLimit + "-page safety limit" else "all reachable public links checked"
            val summary = board + " crawl · " + loaded + " pages checked · " + discovered +
                " same-board links discovered · " + matches.size + " name matches · " + failed +
                " unavailable · " + coverage
            return matches.values.toList() to summary
        } catch (e: java.net.SocketTimeoutException) {
            return emptyList<KeywordLink>() to "$board crawl timed out"
        } catch (e: Exception) {
            return emptyList<KeywordLink>() to "$board crawl unavailable (" + e.javaClass.simpleName + ")"
        }
    }

    private fun normalizeNameForMatch(value: String): String =
        java.text.Normalizer.normalize(value.lowercase(), java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace(Regex("[^\\p{L}\\p{N}]+"), "")

    /**
     * Discover catalog trees linked publicly from the homepage.
     * Does not follow redirects or access gated content.
     */
    private fun discoverAnonibxBoardPaths(): List<String> {
        val boards = linkedSetOf("illi", "tx")
        val base = "https://anonibx.com/"
        val sitemapUrls = linkedSetOf(
            "https://anonibx.com/sitemap.xml",
            "https://anonibx.com/sitemap_index.xml",
            "https://anonibx.com/sitemap-index.xml"
        )
        try {
            // Homepage and robots.txt are public discovery sources. Do not follow
            // redirects, leave anonibx.com, or attempt challenge/login pages.
            val connection = java.net.URL(base).openConnection() as java.net.HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 5000
            connection.readTimeout = 6000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("User-Agent", "FacePersonalSearch/1.2 (public catalog discovery)")
            connection.setRequestProperty("Accept", "text/html,application/xhtml+xml")
            try {
                val code = connection.responseCode
                if (code in 200..299 && connection.contentType.orEmpty().contains("html", true)) {
                    val html = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText().take(2 * 1024 * 1024) }
                    val href = Regex("""href\s*=\s*["']([^"'#]+)["']""", RegexOption.IGNORE_CASE)
                    href.findAll(html).forEach { match ->
                        val link = try { java.net.URL(java.net.URL(base), match.groupValues[1]) } catch (_: Exception) { return@forEach }
                        if (link.protocol != "https" || !link.host.equals("anonibx.com", true)) return@forEach
                        val path = link.path.trim('/').lowercase()
                        if (path.matches(Regex("[a-z]{2,5}/catalog"))) boards.add(path.substringBefore("/"))
                    }
                }
            } finally { connection.disconnect() }

            val robotsConnection = java.net.URL("https://anonibx.com/robots.txt").openConnection() as java.net.HttpURLConnection
            robotsConnection.connectTimeout = 5000
            robotsConnection.readTimeout = 6000
            robotsConnection.instanceFollowRedirects = false
            robotsConnection.setRequestProperty("User-Agent", "FacePersonalSearch/1.2")
            try {
                if (robotsConnection.responseCode in 200..299) {
                    val robots = robotsConnection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText().take(256 * 1024) }
                    Regex("""(?im)^\s*Sitemap:\s*(\S+)""").findAll(robots).forEach { match ->
                        val sitemap = try { java.net.URL(java.net.URL(base), match.groupValues[1]) } catch (_: Exception) { return@forEach }
                        if (sitemap.protocol == "https" && sitemap.host.equals("anonibx.com", true)) {
                            sitemapUrls.add(sitemap.toString())
                        }
                    }
                }
            } finally { robotsConnection.disconnect() }

            // Inspect a small, bounded set of public sitemap endpoints. Only
            // catalog paths on the same host are used to seed the board crawl.
            sitemapUrls.take(6).forEach { sitemapUrl ->
                var sitemapConnection: java.net.HttpURLConnection? = null
                try {
                    sitemapConnection = java.net.URL(sitemapUrl).openConnection() as java.net.HttpURLConnection
                    sitemapConnection.connectTimeout = 5000
                    sitemapConnection.readTimeout = 7000
                    sitemapConnection.instanceFollowRedirects = false
                    sitemapConnection.setRequestProperty("User-Agent", "FacePersonalSearch/1.2 (public sitemap discovery)")
                    val code = sitemapConnection.responseCode
                    if (code !in 200..299 || !sitemapConnection.contentType.orEmpty().let { it.isBlank() || it.contains("xml", true) || it.contains("text", true) }) return@forEach
                    val xml = sitemapConnection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText().take(2 * 1024 * 1024) }
                    val loc = Regex("""(?is)<loc>\s*(.*?)\s*</loc>""")
                    loc.findAll(xml).forEach { match ->
                        val raw = android.text.Html.fromHtml(match.groupValues[1], android.text.Html.FROM_HTML_MODE_LEGACY).toString().trim()
                        val url = try { java.net.URL(raw) } catch (_: Exception) { return@forEach }
                        if (url.protocol != "https" || !url.host.equals("anonibx.com", true)) return@forEach
                        val path = url.path.trim('/').lowercase()
                        if (path.matches(Regex("[a-z]{2,5}/catalog"))) boards.add(path.substringBefore("/"))
                    }
                } catch (_: Exception) {
                    // A missing or blocked sitemap does not stop the known public catalogs.
                } finally { sitemapConnection?.disconnect() }
            }
        } catch (_: Exception) {
            // Known paths remain as fallback.
        }
        return boards.toList().take(30)
    }

    private fun searchAnonibxOnly() {
        val raw = queryInput.text.toString().trim()
        val city = cityInput.text.toString().trim()
        val state = stateInput.text.toString().trim()
        if (raw.isBlank()) {
            Toast.makeText(this, "Enter your own name, username, or identifying keyword to search the Illinois board.", Toast.LENGTH_LONG).show()
            return
        }
        val names = nameSearchVariants(raw)
        val locations = locationQueryVariants(city, state).filter { it.isNotBlank() }
        val searches = linkedSetOf<String>()
        names.forEach { name -> searches += name }
        if (locations.isNotEmpty()) {
            names.forEach { name -> locations.forEach { location -> searches += "$name $location" } }
        }
        // Keep known paths first, then discover other public catalog trees from the homepage.
        val queryLabel = listOf(raw, city, state).filter { it.isNotBlank() }.joinToString(" · ")
        keywordResultsContainer.removeAllViews()
        keywordProgressBar.progress = 0
        keywordProgressBar.visibility = android.view.View.VISIBLE
        keywordProgressText.visibility = android.view.View.VISIBLE
        keywordProgressText.text = "Anonibx search: 0% · preparing…"
        status.text = "Searching Anonibx public indexes only: $queryLabel"
        keywordExecutor.execute {
            try {
            runOnUiThread {
                if (!isFinishing && !isDestroyed) {
                    keywordProgressBar.progress = 1
                    keywordProgressText.text = "Anonibx search: 1% · discovering public catalog trees…"
                    status.text = "Checking which public catalog trees are available…"
                }
            }
            val boardPaths = discoverAnonibxBoardPaths()
            val found = linkedMapOf<String, KeywordLink>()
            val outcomes = mutableListOf<String>()
            // First inspect the actual public catalog pages, not only search-engine indexes.
            boardPaths.forEachIndexed { boardIndex, board ->
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        keywordProgressBar.progress = maxOf(keywordProgressBar.progress, 1 + boardIndex * 2)
                        keywordProgressText.text = "Anonibx search: ${keywordProgressBar.progress}% · starting $board catalog…"
                        status.text = "Connecting to the public $board catalog…"
                    }
                }
                val (catalogMatches, catalogStatus) = fetchAnonibxCatalogMatches(board, names) { checked, limit, matchCount ->
                    val boardFraction = (boardIndex + checked.toFloat() / limit.coerceAtLeast(1)) / boardPaths.size.coerceAtLeast(1)
                    val progress = (3 + boardFraction * 50).toInt().coerceIn(3, 53)
                    runOnUiThread {
                        if (!isFinishing && !isDestroyed) {
                            keywordProgressBar.progress = maxOf(keywordProgressBar.progress, progress)
                            keywordProgressText.text = "Anonibx search: $progress% · $board catalog · $checked pages checked · $matchCount matches"
                            status.text = "Reading reachable public $board pages…"
                        }
                    }
                }
                catalogMatches.forEach { found.putIfAbsent(it.url, it) }
                outcomes += "Anonibx $board direct: $catalogStatus"
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        keywordProgressText.text = "Checking Anonibx public catalogs · ${found.size} name matches so far"
                        status.text = "Direct catalog check: $catalogStatus"
                    }
                }
            }
            val total = (searches.size * boardPaths.size).coerceAtLeast(1)
            val allBoardSearches = boardPaths.flatMap { board -> searches.map { terms -> board to terms } }
            allBoardSearches.forEachIndexed { index, (board, terms) ->
                val result = fetchKeywordLinks("site:anonibx.com/$board ($terms)", setOf("anonibx.com"))
                result.links.forEach { found.putIfAbsent(it.url, it) }
                outcomes += result.status
                val completed = index + 1
                val percent = 55 + completed * 45 / total
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        keywordProgressBar.progress = percent
                        keywordProgressText.text = "Anonibx $board search: $percent% · $completed of $total queries · ${found.size} links · $result.status"
                        status.text = "Checking public search indexes only… $percent% · latest: $result.status"
                    }
                }
            }
            runOnUiThread {
                if (!isFinishing && !isDestroyed) {
                    keywordProgressBar.progress = 100
                    keywordProgressText.text = "Anonibx search: 100% · finished $total queries · ${found.size} links"
                    renderKeywordLinks(found.values.toList(), "Anonibx: $queryLabel")
                    if (found.isEmpty()) {
                        status.text = "No Anonibx links were returned by public search indexes. This search cannot see every post or private/restricted content. Provider results: ${outcomes.distinct().joinToString(" · ")}"
                    }
                }
            }
            } catch (e: Exception) {
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        keywordProgressText.text = "Anonibx search stopped · ${e.javaClass.simpleName}"
                        status.text = "Search error: ${e.message ?: e.javaClass.simpleName}"
                    }
                }
            }
        }
    }

    /**
     * Search only public search-engine indexes for the user's own terms on SnapWins and Xpics.
     * Does not crawl either site, download media, or access restricted pages.
     */
    private fun searchSnapwinsXpicsOnly() {
        val raw = queryInput.text.toString().trim()
        val city = cityInput.text.toString().trim()
        val state = stateInput.text.toString().trim()
        if (raw.isBlank()) {
            Toast.makeText(this, "Enter your own name, username, or identifying keyword first.", Toast.LENGTH_LONG).show()
            return
        }
        val names = nameSearchVariants(raw)
        val locations = locationQueryVariants(city, state).filter { it.isNotBlank() }
        val sites = listOf("snapwins.net", "xpics.me")
        val searches = linkedSetOf<String>()
        names.forEach { name ->
            sites.forEach { site -> searches += "site:$site ($name)" }
            locations.forEach { location ->
                sites.forEach { site -> searches += "site:$site ($name $location)" }
            }
        }
        val queryLabel = listOf(raw, city, state).filter { it.isNotBlank() }.joinToString(" · ")
        keywordResultsContainer.removeAllViews()
        keywordProgressBar.progress = 0
        keywordProgressBar.visibility = android.view.View.VISIBLE
        keywordProgressText.visibility = android.view.View.VISIBLE
        keywordProgressText.text = "SnapWins + Xpics search: 0% · preparing…"
        status.text = "Searching public SnapWins and Xpics indexes only: $queryLabel"
        keywordExecutor.execute {
            val found = linkedMapOf<String, KeywordLink>()
            val outcomes = mutableListOf<String>()
            searches.forEachIndexed { index, search ->
                val result = fetchKeywordLinks(search, setOf("snapwins.net", "xpics.me"))
                result.links.forEach { found.putIfAbsent(it.url, it) }
                outcomes += "$search: ${result.status}"
                val completed = index + 1
                val percent = completed * 100 / searches.size.coerceAtLeast(1)
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        keywordProgressBar.progress = percent
                        keywordProgressText.text = "SnapWins + Xpics search: $percent% · $completed of ${searches.size} queries · ${found.size} links"
                        status.text = "Checking public indexes… $percent% · latest: ${result.status}"
                    }
                }
            }
            runOnUiThread {
                if (!isFinishing && !isDestroyed) {
                    keywordProgressBar.progress = 100
                    keywordProgressText.text = "SnapWins + Xpics search: 100% · finished ${searches.size} queries · ${found.size} links"
                    renderKeywordLinks(found.values.toList(), "SnapWins + Xpics: $queryLabel")
                    if (found.isEmpty()) {
                        status.text = "No matching links were returned by public search indexes. This cannot see every post or private/restricted content. Provider checks: ${outcomes.distinct().joinToString(" · ")}"
                    }
                }
            }
        }
    }

    /**
     * Dedicated public-index self-search for the requested imageboards.
     * Searches only the user's entered identifying terms and optional location
     * variants; it does not crawl boards, download media, or perform face matching.
     */
    private fun searchRequestedImageboardsSelfOnly() {
        val raw = queryInput.text.toString().trim()
        val city = cityInput.text.toString().trim()
        val state = stateInput.text.toString().trim()
        if (raw.isBlank()) {
            Toast.makeText(this, "Enter your own name, username, or identifying keyword first.", Toast.LENGTH_LONG).show()
            return
        }

        val sites = listOf("anonibx.com", "snapwins.net", "xpics.me")
        val names = nameSearchVariants(raw)
        val locations = locationQueryVariants(city, state).filter { it.isNotBlank() }
        val searches = linkedSetOf<String>()
        names.forEach { name ->
            sites.forEach { site -> searches += "site:$site ($name)" }
            locations.forEach { location ->
                sites.forEach { site -> searches += "site:$site ($name $location)" }
            }
        }
        val queryLabel = listOf(raw, city, state).filter { it.isNotBlank() }.joinToString(" · ")
        keywordResultsContainer.removeAllViews()
        keywordProgressBar.progress = 0
        keywordProgressBar.visibility = android.view.View.VISIBLE
        keywordProgressText.visibility = android.view.View.VISIBLE
        keywordProgressText.text = "Imageboard self-search: 0% · preparing…"
        status.text = "Preparing public-index searches for Anonibx, SnapWins, and Xpics…"

        keywordExecutor.execute {
            val found = linkedMapOf<String, KeywordLink>()
            val outcomes = linkedMapOf<String, Int>()
            searches.forEachIndexed { index, search ->
                val site = sites.firstOrNull { search.contains("site:$it") } ?: "imageboard"
                val result = fetchKeywordLinks(search, setOf(site))
                result.links.forEach { found.putIfAbsent(it.url, it) }
                result.status.split(" · ").filter { it.isNotBlank() }.forEach { outcome ->
                    val provider = outcome.substringBefore(":")
                    outcomes[provider] = (outcomes[provider] ?: 0) + 1
                }
                val completed = index + 1
                val percent = completed * 100 / searches.size.coerceAtLeast(1)
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        keywordProgressBar.progress = percent
                        keywordProgressText.text = "Imageboard self-search: $percent% · $completed/${searches.size} queries · ${found.size} links"
                        status.text = "Checking public search indexes for $site… $percent% · ${found.size} links found"
                    }
                }
            }
            runOnUiThread {
                if (!isFinishing && !isDestroyed) {
                    keywordProgressBar.progress = 100
                    keywordProgressText.text = "Imageboard self-search: 100% · ${searches.size} queries · ${found.size} links found"
                    renderKeywordLinks(found.values.toList(), "Anonibx + SnapWins + Xpics: $queryLabel")
                    if (found.isEmpty()) {
                        status.text = "No matching links were returned by public search indexes. Coverage varies; this cannot see unindexed, private, or restricted pages. Provider checks: " +
                            outcomes.entries.joinToString(", ") { "${it.key}: ${it.value} checks" }
                    }
                }
            }
        }
    }

    /**
     * Build conservative name variants: punctuation-free and joined spellings,
     * first/last initials, and initials. No guessed nicknames or unrelated names.
     */
    private fun nameSearchVariants(input: String): List<String> {
        val original = input.trim().replace(Regex("\\s+"), " ")
        val ascii = java.text.Normalizer.normalize(original, java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
        val tokens = ascii.split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.isNotBlank() }
        val variants = linkedSetOf<String>()
        if (original.isNotBlank()) variants += original
        if (ascii.isNotBlank()) variants += ascii
        if (tokens.isNotEmpty()) {
            variants += tokens.joinToString("")
            variants += tokens.joinToString(" ")
        }
        if (tokens.size >= 2) {
            val first = tokens.first()
            val last = tokens.last()
            variants += "${first.first()} $last"
            variants += "$first ${last.first()}"
            variants += "${first.first()}$last"
            variants += "$first${last.first()}"
            variants += tokens.joinToString("") { it.first().toString() }
        }
        return variants.filter { it.isNotBlank() }.take(8)
    }

    /** Include state-name and abbreviation variants when the user enters either form. */
    private fun locationQueryVariants(city: String, state: String): List<String> {
        if (city.isBlank() && state.isBlank()) return listOf("")
        val stateNames = mapOf(
            "AL" to "Alabama", "AK" to "Alaska", "AZ" to "Arizona", "AR" to "Arkansas",
            "CA" to "California", "CO" to "Colorado", "CT" to "Connecticut", "DE" to "Delaware",
            "FL" to "Florida", "GA" to "Georgia", "HI" to "Hawaii", "ID" to "Idaho",
            "IL" to "Illinois", "IN" to "Indiana", "IA" to "Iowa", "KS" to "Kansas",
            "KY" to "Kentucky", "LA" to "Louisiana", "ME" to "Maine", "MD" to "Maryland",
            "MA" to "Massachusetts", "MI" to "Michigan", "MN" to "Minnesota", "MS" to "Mississippi",
            "MO" to "Missouri", "MT" to "Montana", "NE" to "Nebraska", "NV" to "Nevada",
            "NH" to "New Hampshire", "NJ" to "New Jersey", "NM" to "New Mexico", "NY" to "New York",
            "NC" to "North Carolina", "ND" to "North Dakota", "OH" to "Ohio", "OK" to "Oklahoma",
            "OR" to "Oregon", "PA" to "Pennsylvania", "RI" to "Rhode Island", "SC" to "South Carolina",
            "SD" to "South Dakota", "TN" to "Tennessee", "TX" to "Texas", "UT" to "Utah",
            "VT" to "Vermont", "VA" to "Virginia", "WA" to "Washington", "WV" to "West Virginia",
            "WI" to "Wisconsin", "WY" to "Wyoming", "DC" to "District of Columbia"
        )
        val normalized = state.uppercase().replace(".", "").trim()
        val fullState = stateNames[normalized]
        val abbreviation = stateNames.entries.firstOrNull { it.value.equals(state, ignoreCase = true) }?.key
        val stateForms = listOfNotNull(state, fullState, abbreviation)
            .map { it.trim() }.filter { it.isNotBlank() }.distinct()
        val variants = linkedSetOf<String>()
        if (city.isNotBlank() && stateForms.isNotEmpty()) stateForms.forEach { variants += "\"$city\" $it" }
        if (city.isNotBlank()) variants += "\"$city\""
        if (state.isNotBlank() && city.isBlank()) stateForms.forEach { variants += it }
        return variants.toList()
    }

    private data class ProviderSearchResult(
        val links: List<KeywordLink>,
        val status: String
    )

    /**
     * Query two ordinary public web-search result pages. This does not crawl
     * protected pages or evade provider challenges; it reports those outcomes.
     */
    private fun fetchKeywordLinks(searchQuery: String, allowedHosts: Set<String>? = null): ProviderSearchResult {
        val links = linkedMapOf<String, KeywordLink>()
        val outcomes = mutableListOf<String>()
        val encoded = java.net.URLEncoder.encode(searchQuery, "UTF-8")

        fun request(provider: String, url: String, parse: (String) -> List<KeywordLink>) {
            var connection: java.net.HttpURLConnection? = null
            try {
                connection = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 8000
                    readTimeout = 8000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124.0 Mobile Safari/537.36")
                    setRequestProperty("Accept", "text/html,application/xhtml+xml")
                    setRequestProperty("Accept-Language", "en-US,en;q=0.9")
                }
                val code = connection.responseCode
                if (code !in 200..299) {
                    outcomes += "$provider: HTTP $code"
                    return
                }
                val html = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                val lower = html.lowercase()
                if (listOf("captcha", "unusual traffic", "verify you are human", "automated requests").any { lower.contains(it) }) {
                    outcomes += "$provider: challenge/block page"
                    return
                }
                val parsed = parse(html)
                parsed.forEach { links.putIfAbsent(it.url, it) }
                outcomes += if (parsed.isEmpty()) "$provider: no indexed links" else "$provider: ${parsed.size} links"
            } catch (e: java.net.SocketTimeoutException) {
                outcomes += "$provider: timed out"
            } catch (e: Exception) {
                outcomes += "$provider: unavailable (${e.javaClass.simpleName})"
            } finally {
                connection?.disconnect()
            }
        }

        fun allowedLink(raw: String, title: String): KeywordLink? {
            val uri = try { Uri.parse(raw) } catch (_: Exception) { return null }
            val host = uri.host?.lowercase()?.removePrefix("www.") ?: return null
            val allowed = allowedHosts ?: setOf("4plebs.org", "warosu.org", "anonibx.com", "motherless.com", "snapwins.net", "xpics.me", "4chan.org", "4channel.org")
            if (allowed.none { host == it || host.endsWith(".$it") }) return null
            if (uri.scheme != "https" && uri.scheme != "http") return null
            val cleanTitle = android.text.Html.fromHtml(title, android.text.Html.FROM_HTML_MODE_LEGACY)
                .toString().replace(Regex("""\s+"""), " ").trim().ifBlank { host }
            return KeywordLink(cleanTitle, uri.toString(), host)
        }

        request("DuckDuckGo", "https://html.duckduckgo.com/html/?q=$encoded") { html ->
            val regex = Regex("""<a(?=[^>]*class=["'][^"']*result__a)[^>]*>(.*?)</a>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            regex.findAll(html).mapNotNull { match ->
                val tagStart = html.lastIndexOf("<a", match.range.first)
                val tagEnd = html.indexOf('>', tagStart).takeIf { it >= 0 } ?: return@mapNotNull null
                val attrs = html.substring(tagStart, tagEnd + 1)
                val href = Regex("""href\s*=\s*["']([^"'#]+)["']""", RegexOption.IGNORE_CASE)
                    .find(attrs)?.groupValues?.get(1) ?: return@mapNotNull null
                val decoded = try { Uri.parse(href).getQueryParameter("uddg") ?: href } catch (_: Exception) { href }
                allowedLink(decoded, match.groupValues[1])
            }.toList()
        }

        request("Bing", "https://www.bing.com/search?q=$encoded") { html ->
            val regex = Regex("""<li[^>]*class=["'][^"']*b_algo[^"']*["'][^>]*>.*?<h2[^>]*>\s*<a[^>]*href=["']([^"']+)["'][^>]*>(.*?)</a>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            regex.findAll(html).mapNotNull { match ->
                val title = match.groupValues[2].replace(Regex("""<[^>]+>"""), "")
                allowedLink(match.groupValues[1], title)
            }.toList()
        }

        return ProviderSearchResult(links.values.toList(), outcomes.joinToString(" · "))
    }

    private fun renderKeywordLinks(links: List<KeywordLink>, query: String) {
        keywordResultsContainer.removeAllViews()
        keywordResultsContainer.addView(TextView(this).apply {
            text = if (links.isEmpty()) "No matching public links were returned for “$query”. Try a broader keyword or add a city/state."
            else "${links.size} matching public links found for “$query”"
            textSize = 17f
            setTextColor(android.graphics.Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(8), 0, dp(10))
        })
        links.forEachIndexed { index, item ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(android.graphics.Color.rgb(25, 29, 43))
                    setStroke(dp(1), android.graphics.Color.rgb(57, 63, 83))
                    cornerRadius = dp(12).toFloat()
                }
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(item.url))) }
                    catch (_: Exception) { Toast.makeText(this@WebScanActivity, "No browser could open this link.", Toast.LENGTH_SHORT).show() }
                }
            }
            card.addView(TextView(this).apply {
                text = "${index + 1}. ${item.title}"
                textSize = 15f
                setTextColor(android.graphics.Color.WHITE)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            card.addView(TextView(this).apply {
                text = item.source
                textSize = 12f
                setTextColor(android.graphics.Color.rgb(173, 160, 255))
                setPadding(0, dp(4), 0, dp(3))
            })
            card.addView(TextView(this).apply {
                text = item.url
                textSize = 11f
                setTextColor(android.graphics.Color.rgb(160, 190, 235))
                maxLines = 3
            })
            keywordResultsContainer.addView(card, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) })
        }
        status.text = if (links.isEmpty()) "Search finished. No public indexed links matched those terms."
            else "Search finished. Tap any result to open it. Results are from public search indexes, so coverage varies."
    }

    /** Pick an engine, then (with several reference photos) which photo to send. */
    private fun chooseReverseSearch() {
        if (referencePhotos.isEmpty()) {
            Toast.makeText(this, "Add a reference photo first.", Toast.LENGTH_LONG).show()
            return
        }
        val engines = ReverseImageSearch.Engine.values()
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Reverse image search")
            .setItems(engines.map { it.label }.toTypedArray()) { _, which ->
                val engine = engines[which]
                if (referencePhotos.size == 1) {
                    ReverseImageSearch.search(this, engine, referencePhotos[0])
                } else {
                    com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                        .setTitle("Which photo?")
                        .setItems(referencePhotos.indices.map { "Photo ${it + 1}" }.toTypedArray()) { _, photo ->
                            ReverseImageSearch.search(this, engine, referencePhotos[photo])
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
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
        refreshRunningScanButtons()
    }

    private fun setSearchEnabled(enabled: Boolean) {
        startSearchButton.isEnabled = enabled
        groupButtons.forEach { it.isEnabled = enabled }
    }

    /** While a search is running in the background, offer to go back to it instead of only starting over. */
    /** Before a search, offers to let Face keep running in the background so the phone doesn't stop the search. */
    private fun askToRunInBackground(group: SourceGroup?) {
        val hint = BackgroundRunHelper.manufacturerHint()?.let { "\n\n$it" }.orEmpty()
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Keep searches running?")
            .setMessage("Long searches can be stopped by your phone's battery saver when you leave the app. Allow Face to run in the background so searches finish.$hint")
            .setPositiveButton("Allow") { _, _ ->
                groupAwaitingBatteryPrompt = group
                searchAwaitingBatteryPrompt = true
                try {
                    batteryPrompt.launch(BackgroundRunHelper.requestIntent(this))
                } catch (_: Exception) {
                    searchAwaitingBatteryPrompt = false
                    startNewScan(group, batteryChecked = true)
                }
            }
            .setNegativeButton("Not now") { _, _ ->
                BackgroundRunHelper.rememberDeclined(this)
                startNewScan(group, batteryChecked = true)
            }
            .setCancelable(false)
            .show()
    }

    /** Clears earlier comparisons so the next search compares every saved image with the current photos. */
    private fun confirmRecheckAll() {
        if (IdentityMatchActivity.isScanRunning()) {
            Toast.makeText(this, "Wait for the current search to finish, or stop it first.", Toast.LENGTH_LONG).show()
            return
        }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Recheck all saved images?")
            .setMessage("Your next search will compare every saved image with your current reference photos again. Confirmed matches and ones you marked \"Not a match\" are kept. This makes the next search take longer.")
            .setPositiveButton("Recheck") { _, _ ->
                database.resetCheckedResults()
                Toast.makeText(this, "Done. Start a search to recheck everything.", Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun refreshRunningScanButtons() {
        if (!::startSearchButton.isInitialized) return
        val running = IdentityMatchActivity.isScanRunning()
        continueSearchButton.visibility = if (running) android.view.View.VISIBLE else android.view.View.GONE
        startSearchButton.text = if (running) "Start new search" else "Scan sites for my face"
        if (running) status.text = "A deep search is still running. Continue it, or start a new one (this stops the current search)."
    }

    /** Opens the running search's live screen without restarting it. */
    private fun openRunningScan() {
        if (!IdentityMatchActivity.isScanRunning()) {
            refreshRunningScanButtons()
            Toast.makeText(this, "That search already finished.", Toast.LENGTH_SHORT).show()
            return
        }
        startActivity(Intent(this, IdentityMatchActivity::class.java).apply {
            putParcelableArrayListExtra(IdentityMatchActivity.EXTRA_REFERENCE_URIS, ArrayList(referencePhotos))
            putExtra(IdentityMatchActivity.EXTRA_WEB_SCAN_MODE, true)
        })
    }

    /** Opens the live results page immediately. Source discovery and face matching run there. */
    private fun launchAutomaticScan(group: SourceGroup? = null) {
        if (IdentityMatchActivity.isScanRunning()) {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("Start a new search?")
                .setMessage("A deep search is already running. Starting a new one stops it. Saved matches are kept.")
                .setPositiveButton("Start new search") { _, _ -> startNewScan(group) }
                .setNeutralButton("Continue current") { _, _ -> openRunningScan() }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }
        startNewScan(group)
    }

    private fun startNewScan(group: SourceGroup? = null, batteryChecked: Boolean = false) {
        if (!catalogueReady) { Toast.makeText(this, "The source catalogue is still loading. Try again in a moment.", Toast.LENGTH_LONG).show(); return }
        if (referencePhotos.isEmpty()) {
            Toast.makeText(this, "Choose at least one reference photo first.", Toast.LENGTH_LONG).show()
            return
        }
        if (!batteryChecked && BackgroundRunHelper.shouldAsk(this)) {
            askToRunInBackground(group)
            return
        }
        val sourceCount = database.sources().count { it.enabled && (group == null || group.contains(it)) }
        if (sourceCount == 0) {
            Toast.makeText(this, "There are no enabled public sources to scan.", Toast.LENGTH_LONG).show()
            return
        }
        val intent = Intent(this, IdentityMatchActivity::class.java).apply {
            putParcelableArrayListExtra(IdentityMatchActivity.EXTRA_REFERENCE_URIS, ArrayList(referencePhotos))
            putExtra(IdentityMatchActivity.EXTRA_WEB_SCAN_MODE, true)
            putExtra(IdentityMatchActivity.EXTRA_AUTO_START, true)
            group?.let { putExtra(IdentityMatchActivity.EXTRA_SOURCE_GROUP, it.name) }
            putExtra(IdentityMatchActivity.EXTRA_LOCATION_KEYWORDS, locationKeywords())
        }
        startActivity(intent)
    }

}