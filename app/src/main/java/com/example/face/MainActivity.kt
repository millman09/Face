package com.example.face

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.progressindicator.LinearProgressIndicator

class MainActivity : AppCompatActivity() {

    private data class Link(val titleRes: Int, val bodyRes: Int, val url: String)
    private data class Tool(val titleRes: Int, val bodyRes: Int, val screen: Class<*>)

    private val tools = listOf(
        Tool(R.string.tool_search_title, R.string.tool_search_body, WebScanActivity::class.java)
    )

    private val removeLinks = listOf(
        Link(R.string.stopncii_title, R.string.stopncii_body, "https://stopncii.org"),
        Link(R.string.takeitdown_title, R.string.takeitdown_body, "https://takeitdown.ncmec.org"),
        Link(R.string.google_title, R.string.google_body, "https://support.google.com/websearch/answer/13650142")
    )

    private val exposureLinks = listOf(
        Link(R.string.hibp_title, R.string.hibp_body, "https://haveibeenpwned.com")
    )

    private val handyLinks = listOf(
        Link(R.string.alerts_title, R.string.alerts_body, "https://www.google.com/alerts"),
        Link(R.string.lens_title, R.string.lens_body, "https://lens.google.com"),
        Link(R.string.tineye_title, R.string.tineye_body, "https://tineye.com")
    )

    // Each pair is (saved position, text). The saved position keeps existing checkmarks
    // from earlier versions of the app attached to the right step.
    private val lookItems = listOf(
        0 to R.string.check_1, 1 to R.string.check_2, 2 to R.string.check_3, 8 to R.string.check_9
    )
    private val lockItems = listOf(
        3 to R.string.check_4, 4 to R.string.check_5, 5 to R.string.check_6,
        6 to R.string.check_7, 7 to R.string.check_8
    )
    private val totalChecks = 9

    private val prefs by lazy { getSharedPreferences("checklist", MODE_PRIVATE) }

    private lateinit var checklistBar: LinearProgressIndicator
    private lateinit var checklistText: TextView
    private lateinit var heroBar: LinearProgressIndicator
    private lateinit var heroText: TextView
    private lateinit var resultsContainer: LinearLayout
    private lateinit var resultsList: LinearLayout
    private lateinit var resultsSummary: TextView
    private val resultsExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    @Volatile private var resultsRefreshInFlight = false
    private var resultsLoaded = false
    private var lastResultsRevision: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Android 15+ draws apps edge to edge, so keep content clear of the system bars.
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        buildTools(findViewById(R.id.toolsContainer))
        buildSearchPanel(findViewById(R.id.searchContainer))
        buildResultsPanel(findViewById(R.id.resultsContainer))
        buildHelp(findViewById(R.id.helpContainer))
        buildChecklist(findViewById(R.id.checklistContainer))
        updateProgress()
        // Keep first launch lightweight. Model download is deferred until a scan is requested.

        val panels = mapOf(
            R.id.nav_tools to findViewById<View>(R.id.panelTools),
            R.id.nav_search to findViewById<View>(R.id.panelSearch),
            R.id.nav_results to findViewById<View>(R.id.panelResults),
            R.id.nav_help to findViewById<View>(R.id.panelHelp),
        )
        val nav = findViewById<BottomNavigationView>(R.id.bottomNav)
        nav.setOnItemSelectedListener { item ->
            if (item.itemId == R.id.nav_accounts) {
                startActivity(Intent(this, AccountAuditActivity::class.java))
                return@setOnItemSelectedListener false
            }
            panels.forEach { (id, panel) ->
                panel.visibility = if (id == item.itemId) View.VISIBLE else View.GONE
            }
            if (item.itemId == R.id.nav_results) refreshResults()
            true
        }
        // Match the visible panel to the selected tab, including after a rotation.
        if (nav.selectedItemId == R.id.nav_tools) {
            nav.selectedItemId = R.id.nav_search
        }
        panels.forEach { (id, panel) ->
            panel.visibility = if (id == nav.selectedItemId) View.VISIBLE else View.GONE
        }
    }

    /**
     * Begin downloading the face model in the background on first launch.
     * Android does not allow a normal app to execute code at install time.
     * A failed download is retried the next time the app is opened.
     */
    private fun startModelPrefetch() {
        Thread({
            try {
                MobileFaceNetModel.ensureDownloaded(applicationContext)
            } catch (error: Exception) {
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        Toast.makeText(
                            this,
                            "Face model download could not finish. It will retry next time you open the app.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }, "MobileFaceNet-first-launch-download").start()
    }

    private fun buildTools(container: LinearLayout) {
        val hero = layoutInflater.inflate(R.layout.item_hero, container, false)
        heroBar = hero.findViewById(R.id.heroProgress)
        heroText = hero.findViewById(R.id.heroProgressText)
        heroBar.max = totalChecks
        container.addView(hero)

        tools.forEach { tool ->
            addTile(container, getString(tool.titleRes), getString(tool.bodyRes)) {
                startActivity(Intent(this, tool.screen))
            }
        }
        addSection(container, getString(R.string.section_search))
        handyLinks.forEach { addLinkTile(container, it) }
        exposureLinks.forEach { addLinkTile(container, it) }
        addTile(container, "Privacy checklist", "Review the steps for checking and protecting your accounts.") {
            val panels = listOf(R.id.panelTools, R.id.panelSearch, R.id.panelResults, R.id.panelHelp, R.id.panelChecklist)
            panels.forEach { id -> findViewById<View>(id).visibility = if (id == R.id.panelChecklist) View.VISIBLE else View.GONE }
        }
    }

    private fun buildSearchPanel(container: LinearLayout) {
        val eyebrow = TextView(this).apply {
            text = "PRIVATE FACE SEARCH"
            textSize = 12f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            letterSpacing = 0.12f
            setPadding(0, dp(12), 0, dp(8))
        }
        container.addView(eyebrow)

        val headline = TextView(this).apply {
            text = "Find where your face appears online"
            textSize = 28f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setLineSpacing(dp(2).toFloat(), 1.0f)
            setPadding(0, 0, 0, dp(10))
        }
        container.addView(headline)

        val intro = TextView(this).apply {
            text = "Search supported public image sources, compare possible matches, and review the pages where they appear."
            textSize = 15f
            setLineSpacing(dp(3).toFloat(), 1.05f)
            setPadding(0, 0, 0, dp(18))
        }
        container.addView(intro)

        addTile(
            container,
            "Start a deep search",
            "Choose one or more reference photos, then scan every supported public source in one run."
        ) {
            startActivity(Intent(this, WebScanActivity::class.java))
        }

        addSection(container, "YOUR SEARCH WORKSPACE")
        addTile(
            container,
            "View possible matches",
            "Review saved results, similarity scores, and original source pages."
        ) {
            findViewById<BottomNavigationView>(R.id.bottomNav).selectedItemId = R.id.nav_results
        }
        addTile(
            container,
            "Private photo processing",
            "Reference photos stay on this phone. Candidate images are compared temporarily and are not saved."
        ) { }
        addTile(
            container,
            "Public-source coverage",
            "Search can only reach images exposed through supported public pages, feeds, and indexes. It cannot search every website on the internet."
        ) { }
    }

    private fun buildResultsPanel(container: LinearLayout) {
        val eyebrow = TextView(this).apply {
            text = "SEARCH HISTORY"
            textSize = 12f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            letterSpacing = 0.12f
            setPadding(0, dp(12), 0, dp(8))
        }
        container.addView(eyebrow)

        val headline = TextView(this).apply {
            text = "Possible matches"
            textSize = 27f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, dp(8))
        }
        container.addView(headline)

        resultsSummary = TextView(this).apply {
            textSize = 14f
            setPadding(0, 0, 0, dp(12))
        }
        container.addView(resultsSummary)
        addTile(container, "Refresh results", "Reload the saved possible matches from your previous scans.") {
            refreshResults(force = true)
        }
        resultsContainer = container
        resultsList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        container.addView(resultsList)
        resultsSummary.text = getString(R.string.results_empty)
    }

    private fun refreshResults(force: Boolean = false) {
        if (!::resultsContainer.isInitialized || !::resultsSummary.isInitialized || resultsRefreshInFlight) return
        resultsRefreshInFlight = true
        resultsSummary.text = "Checking saved matches…"
        resultsExecutor.execute {
            var revision = "0:0"
            var matches: List<MatchResult> = emptyList()
            var changed = false
            var failure: String? = null
            val db = PublicSourceDatabase(applicationContext)
            try {
                revision = db.matchRevision()
                changed = force || !resultsLoaded || revision != lastResultsRevision
                if (changed) matches = db.possibleMatches(50, bestFirst = true)
            } catch (e: Exception) {
                failure = e.localizedMessage ?: "Unable to read saved results."
            } finally { db.close() }
            runOnUiThread {
                resultsRefreshInFlight = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (failure != null) { resultsSummary.text = failure; return@runOnUiThread }
                if (!changed) return@runOnUiThread
                lastResultsRevision = revision
                resultsLoaded = true
                resultsList.removeAllViews()
                resultsSummary.text = if (matches.isEmpty()) getString(R.string.results_empty)
                    else getString(R.string.results_count, matches.size)
                if (matches.isEmpty()) {
                    addText(resultsList, getString(R.string.results_empty_detail))
                } else {
                    matches.forEach { match -> addMatchCard(match) }
                }
            }
        }
    }

    private fun addMatchCard(match: MatchResult) {
        val host = Uri.parse(match.imageUrl).host ?: Uri.parse(match.sourceBaseUrl).host ?: "Website"
        val score = (match.similarity.coerceIn(-1f, 1f) * 100f).toInt().coerceAtLeast(0)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.rgb(25, 29, 43))
                cornerRadius = dp(16).toFloat()
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { openUrl(match.pageUrl.ifBlank { match.imageUrl }) }
        }
        val preview = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(112), dp(112))
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(android.graphics.Color.rgb(43, 47, 62))
            contentDescription = "Possible matching image from $host"
        }
        card.addView(preview)
        val details = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        details.addView(TextView(this).apply {
            text = matchLabel(match.similarity)
            textSize = 16f
            setTextColor(android.graphics.Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        details.addView(TextView(this).apply {
            text = host
            textSize = 13f
            setTextColor(android.graphics.Color.rgb(190, 195, 210))
            setPadding(0, dp(4), 0, dp(3))
        })
        details.addView(TextView(this).apply {
            text = "Similarity indicator: $score"
            textSize = 13f
            setTextColor(android.graphics.Color.rgb(173, 160, 255))
        })
        details.addView(com.google.android.material.button.MaterialButton(this).apply {
            text = "Open source"
            isAllCaps = false
            setOnClickListener { openUrl(match.pageUrl.ifBlank { match.imageUrl }) }
        })
        card.addView(details)
        resultsList.addView(card, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(12) })
        Thread({
            try {
                val uri = Uri.parse(match.imageUrl)
                if (uri.scheme != "https" || uri.host.isNullOrBlank() || uri.userInfo != null) return@Thread
                val connection = (java.net.URL(match.imageUrl).openConnection() as java.net.HttpURLConnection).apply {
                    connectTimeout = 5000
                    readTimeout = 7000
                    instanceFollowRedirects = false
                    setRequestProperty("Accept", "image/*")
                    setRequestProperty("User-Agent", "FacePersonalScanner/1.0")
                }
                val bitmap = try {
                    if (connection.responseCode !in 200..299 || !connection.contentType.orEmpty().startsWith("image/") || connection.contentLengthLong > 2L * 1024L * 1024L) null
                    else connection.inputStream.use { input ->
                        val output = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        var total = 0
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            if (total > 2 * 1024 * 1024) throw IllegalArgumentException("Thumbnail exceeds temporary size limit")
                            output.write(buffer, 0, count)
                        }
                        val bytes = output.toByteArray()
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    }
                } finally { connection.disconnect() }
                if (bitmap != null) runOnUiThread { if (!isFinishing && !isDestroyed) preview.setImageBitmap(bitmap) else bitmap.recycle() }
            } catch (_: Exception) { }
        }, "match-thumbnail").start()
    }
    override fun onResume() {
        super.onResume()
        if (::resultsContainer.isInitialized && findViewById<View>(R.id.panelResults).visibility == View.VISIBLE) {
            refreshResults()
        }
    }

    override fun onDestroy() {
        resultsExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun buildHelp(container: LinearLayout) {
        addTile(container, getString(R.string.guide_title), getString(R.string.guide_body)) {
            startActivity(Intent(this, RemovalGuideActivity::class.java))
        }
        addTile(container, getString(R.string.log_title), getString(R.string.log_body)) {
            startActivity(Intent(this, RemovalLogActivity::class.java))
        }
        removeLinks.forEach { addLinkTile(container, it) }
        addText(container, getString(R.string.safety_note))
    }

    private fun buildChecklist(container: LinearLayout) {
        val header = layoutInflater.inflate(R.layout.item_progress, container, false)
        checklistBar = header.findViewById(R.id.progress)
        checklistText = header.findViewById(R.id.progressText)
        checklistBar.max = totalChecks
        container.addView(header)

        addGroup(container, R.string.group_look, lookItems)
        addGroup(container, R.string.group_lock, lockItems)
    }

    private fun addGroup(container: LinearLayout, titleRes: Int, items: List<Pair<Int, Int>>) {
        val card = layoutInflater.inflate(R.layout.item_group, container, false)
        card.findViewById<TextView>(R.id.groupTitle).setText(titleRes)
        val list = card.findViewById<LinearLayout>(R.id.groupItems)
        items.forEach { (position, textRes) ->
            val box = MaterialCheckBox(this)
            box.minHeight = 0
            box.setPadding(box.paddingLeft, 0, box.paddingRight, 0)
            box.setText(textRes)
            box.isChecked = prefs.getBoolean("item_$position", false)
            box.setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean("item_$position", checked).apply()
                updateProgress()
            }
            list.addView(
                box,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }
        container.addView(card)
    }

    private fun updateProgress() {
        val done = (0 until totalChecks).count { prefs.getBoolean("item_$it", false) }
        checklistBar.setProgressCompat(done, true)
        checklistText.text = getString(R.string.progress_format, done, totalChecks)
        heroBar.setProgressCompat(done, true)
        heroText.text = getString(R.string.hero_progress, done, totalChecks)
    }

    private fun addText(container: ViewGroup, text: String) {
        val view = layoutInflater.inflate(R.layout.item_text, container, false) as TextView
        view.text = text
        container.addView(view)
    }

    private fun addSection(container: ViewGroup, text: String) {
        val view = layoutInflater.inflate(R.layout.item_section, container, false) as TextView
        view.text = text
        container.addView(view)
    }

    private fun addTile(container: ViewGroup, title: String, body: String, onClick: () -> Unit) {
        val tile = layoutInflater.inflate(R.layout.item_tile, container, false)
        tile.findViewById<TextView>(R.id.title).text = title
        tile.findViewById<TextView>(R.id.body).text = body
        tile.setOnClickListener { onClick() }
        container.addView(tile)
    }

    private fun addLinkTile(container: ViewGroup, link: Link) {
        addTile(container, getString(link.titleRes), getString(link.bodyRes)) { openUrl(link.url) }
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, url, Toast.LENGTH_LONG).show()
        }
    }
}
