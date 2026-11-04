package com.example.face

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
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
        buildHelp(findViewById(R.id.helpContainer))
        buildChecklist(findViewById(R.id.checklistContainer))
        updateProgress()
        startModelPrefetch()

        val panels = mapOf(
            R.id.nav_tools to findViewById<View>(R.id.panelTools),
            R.id.nav_help to findViewById<View>(R.id.panelHelp),
            R.id.nav_checklist to findViewById<View>(R.id.panelChecklist)
        )
        val nav = findViewById<BottomNavigationView>(R.id.bottomNav)
        nav.setOnItemSelectedListener { item ->
            panels.forEach { (id, panel) ->
                panel.visibility = if (id == item.itemId) View.VISIBLE else View.GONE
            }
            true
        }
        // Match the visible panel to the selected tab, including after a rotation.
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
    }

    private fun buildHelp(container: LinearLayout) {
        addSection(container, getString(R.string.section_remove))
        removeLinks.forEach { addLinkTile(container, it) }
        addSection(container, getString(R.string.section_exposure))
        exposureLinks.forEach { addLinkTile(container, it) }
        addSection(container, getString(R.string.section_tools))
        handyLinks.forEach { addLinkTile(container, it) }
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
