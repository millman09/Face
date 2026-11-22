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
    private lateinit var cityInput: EditText
    private lateinit var stateInput: EditText
    private lateinit var searxInput: EditText
    private lateinit var status: TextView
    private lateinit var referencePreview: LinearLayout
    private lateinit var referenceCount: TextView
    private lateinit var startSearchButton: MaterialButton
    private lateinit var continueSearchButton: MaterialButton
    private lateinit var keywordResultsContainer: LinearLayout
    private var groupButtons: List<MaterialButton> = emptyList()
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
            text = "Photos are optional. Search imageboards with keywords alone, or add reference photos for on-device face matching."
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
            text = "OPTIONAL REFERENCE PHOTOS"
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

        page.addSpaced(button("Find matching imageboard links · no photo needed") { searchImageboardsOnly() })
        page.addSpaced(button("Search Yandex, SearXNG and DuckDuckGo") { searchAll() })
        keywordResultsContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        page.addView(keywordResultsContainer, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(12); bottomMargin = dp(16) })

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
        continueSearchButton = button("Continue current search") { openRunningScan() }.apply {
            textSize = 16f
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setBackgroundColor(android.graphics.Color.rgb(105, 83, 230))
            setTextColor(android.graphics.Color.WHITE)
            visibility = android.view.View.GONE
        }
        page.addSpaced(continueSearchButton, 8)
        page.addSpaced(startSearchButton, 8)
        // Narrower, faster searches over one group of sources.
        groupButtons = SourceGroup.values().map { group ->
            button(group.buttonLabel) { launchAutomaticScan(group) }.apply {
                isEnabled = false
            }
        }
        groupButtons.forEach { page.addSpaced(it, 6) }

        status = TextView(this).apply {
            text = "Preparing the public-source catalogue…"
            textSize = 14f
            setPadding(0, dp(14), 0, dp(8))
        }
        page.addView(status)
        renderReferencePhotos()
        // The first SQLite open may create and seed the full catalogue. Do it off the UI thread.
        scanExecutor.execute {
            val count = database.sources().size
            runOnUiThread {
                if (!isFinishing && !isDestroyed) {
                    catalogueReady = true
                    status.text = "READY  ·  $count public sources available. All enabled, eligible sources are included automatically."
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

    private fun yandexUrl(query: String): String =
        Uri.parse("https://yandex.com/images/search").buildUpon()
            .appendQueryParameter("text", query).build().toString()

    private fun duckDuckGoUrl(query: String): String =
        Uri.parse("https://duckduckgo.com/").buildUpon()
            .appendQueryParameter("q", query).appendQueryParameter("ia", "images")
            .appendQueryParameter("iax", "images").build().toString()

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

    private fun imageboardSearchUrls(query: String): List<String> = listOf(
        fourChanArchiveUrl(query),
        anonibxArchiveUrl(query),
        motherlessArchiveUrl(query)
    )

    private data class KeywordLink(val title: String, val url: String, val source: String)

    /** Keyword-only search expands location terms so a city is not lost inside one overly narrow query. */
    private fun searchImageboardsOnly() {
        val rawKeywords = queryInput.text.toString().trim()
        val city = cityInput.text.toString().trim()
        val state = stateInput.text.toString().trim()
        if (rawKeywords.isBlank() && city.isBlank() && state.isBlank()) {
            Toast.makeText(this, "Enter a name, keywords, or a city/state first.", Toast.LENGTH_SHORT).show()
            return
        }
        val locationVariants = locationQueryVariants(city, state)
        val nameVariants = if (rawKeywords.isBlank()) listOf("") else nameSearchVariants(rawKeywords)
        val queryLabel = listOf(rawKeywords, city, state).filter { it.isNotBlank() }.joinToString(" · ")
        keywordResultsContainer.removeAllViews()
        status.text = "Expanding name spellings, abbreviations and location variations: $queryLabel"
        scanExecutor.execute {
            val siteQueries = listOf(
                "(site:archive.4plebs.org OR site:warosu.org)",
                "site:anonibx.com",
                "site:motherless.com",
                "(site:4chan.org OR site:4channel.org)"
            )
            val searches = linkedSetOf<String>()
            // Try each plausible spelling independently so a location or other keyword
            // cannot hide a post whose title uses only a shortened version of the name.
            nameVariants.forEach { name ->
                siteQueries.forEach { site -> searches += "($name) $site" }
            }
            // Also run the exact entered name with each location form, plus location-only
            // searches to catch posts that omit the name entirely.
            if (rawKeywords.isNotBlank()) {
                locationVariants.filter { it.isNotBlank() }.forEach { location ->
                    siteQueries.forEach { site -> searches += "($rawKeywords $location) $site" }
                }
            } else {
                locationVariants.filter { it.isNotBlank() }.forEach { location ->
                    siteQueries.forEach { site -> searches += "($location) $site" }
                }
            }
            val found = linkedMapOf<String, KeywordLink>()
            searches.forEachIndexed { index, search ->
                try {
                    fetchKeywordLinks(search).forEach { found.putIfAbsent(it.url, it) }
                } catch (_: Exception) { }
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        status.text = "Checking public indexes… ${index + 1} of ${searches.size} searches · ${found.size} links found"
                    }
                }
            }
            runOnUiThread {
                if (!isFinishing && !isDestroyed) renderKeywordLinks(found.values.toList(), queryLabel)
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

    /** Reads ordinary HTML search results and only keeps links on the targeted public hosts. */
    private fun fetchKeywordLinks(searchQuery: String): List<KeywordLink> {
        val encoded = java.net.URLEncoder.encode(searchQuery, "UTF-8")
        val connection = (java.net.URL("https://html.duckduckgo.com/html/?q=${encoded}").openConnection() as java.net.HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10000
            readTimeout = 10000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124.0 Mobile Safari/537.36")
            setRequestProperty("Accept", "text/html")
        }
        try {
            val html = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val anchorRegex = Regex("""<a(?=[^>]*class=["'][^"']*result__a)[^>]*>(.*?)</a>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            val results = mutableListOf<KeywordLink>()
            anchorRegex.findAll(html).forEach { match ->
                val tagStart = html.lastIndexOf("<a", match.range.first)
                val tagEnd = html.indexOf('>', tagStart).takeIf { it >= 0 } ?: return@forEach
                val attributes = html.substring(tagStart, tagEnd + 1)
                val rawHref = Regex("""href=["']([^"']+)""", RegexOption.IGNORE_CASE).find(attributes)?.groupValues?.get(1) ?: return@forEach
                val href = try {
                    val parsed = Uri.parse(rawHref)
                    parsed.getQueryParameter("uddg") ?: rawHref
                } catch (_: Exception) { rawHref }
                val uri = try { Uri.parse(href) } catch (_: Exception) { return@forEach }
                val host = uri.host?.lowercase()?.removePrefix("www.") ?: return@forEach
                val allowed = listOf("4plebs.org", "warosu.org", "anonibx.com", "motherless.com", "4chan.org", "4channel.org")
                if (allowed.none { host == it || host.endsWith(".$it") }) return@forEach
                if (uri.scheme != "https" && uri.scheme != "http") return@forEach
                val title = android.text.Html.fromHtml(match.groupValues[1], android.text.Html.FROM_HTML_MODE_LEGACY)
                    .toString().replace(Regex("""\s+"""), " ").trim().ifBlank { host }
                results += KeywordLink(title, uri.toString(), host)
            }
            return results
        } finally {
            connection.disconnect()
        }
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
                text = "\${index + 1}. \${item.title}"
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

    private fun searchAll() {
        val query = currentQuery() ?: return
        pendingSearches.clear()
        pendingSearches.addAll(listOfNotNull(
            yandexUrl(query),
            searxUrl(query),
            duckDuckGoUrl(query),
            fourChanArchiveUrl(query),
            anonibxArchiveUrl(query),
            motherlessArchiveUrl(query)
        ))
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
        refreshRunningScanButtons()
    }

    private fun setSearchEnabled(enabled: Boolean) {
        startSearchButton.isEnabled = enabled
        groupButtons.forEach { it.isEnabled = enabled }
    }

    /** While a search is running in the background, offer to go back to it instead of only starting over. */
    private fun refreshRunningScanButtons() {
        if (!::startSearchButton.isInitialized) return
        val running = IdentityMatchActivity.isScanRunning()
        continueSearchButton.visibility = if (running) android.view.View.VISIBLE else android.view.View.GONE
        startSearchButton.text = if (running) "Start new search" else "Search for matches"
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

    private fun startNewScan(group: SourceGroup? = null) {
        if (!catalogueReady) { Toast.makeText(this, "The source catalogue is still loading. Try again in a moment.", Toast.LENGTH_LONG).show(); return }
        if (referencePhotos.isEmpty()) {
            Toast.makeText(this, "Choose at least one reference photo first.", Toast.LENGTH_LONG).show()
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
