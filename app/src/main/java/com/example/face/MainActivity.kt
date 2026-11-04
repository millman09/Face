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
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.tabs.TabLayout

class MainActivity : AppCompatActivity() {

    private data class Link(
        val titleRes: Int,
        val bodyRes: Int,
        val buttonRes: Int,
        val url: String
    )

    private val protectLinks = listOf(
        Link(R.string.hibp_title, R.string.hibp_body, R.string.hibp_button, "https://haveibeenpwned.com"),
        Link(R.string.stopncii_title, R.string.stopncii_body, R.string.stopncii_button, "https://stopncii.org"),
        Link(R.string.takeitdown_title, R.string.takeitdown_body, R.string.takeitdown_button, "https://takeitdown.ncmec.org"),
        Link(R.string.google_title, R.string.google_body, R.string.google_button, "https://support.google.com/websearch/answer/13650142")
    )

    private val toolLinks = listOf(
        Link(R.string.alerts_title, R.string.alerts_body, R.string.alerts_button, "https://www.google.com/alerts"),
        Link(R.string.lens_title, R.string.lens_body, R.string.lens_button, "https://lens.google.com"),
        Link(R.string.tineye_title, R.string.tineye_body, R.string.tineye_button, "https://tineye.com")
    )

    private val checklistItems = listOf(
        R.string.check_1, R.string.check_2, R.string.check_3,
        R.string.check_4, R.string.check_5, R.string.check_6,
        R.string.check_7, R.string.check_8, R.string.check_9
    )

    private val prefs by lazy { getSharedPreferences("checklist", MODE_PRIVATE) }
    private lateinit var progressBar: LinearProgressIndicator
    private lateinit var progressText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val tabs = findViewById<TabLayout>(R.id.tabs)
        val panelProtect = findViewById<View>(R.id.panelProtect)
        val panelChecklist = findViewById<View>(R.id.panelChecklist)

        buildProtect(findViewById(R.id.protectContainer))
        buildChecklist(findViewById(R.id.checklistContainer))
        updateProgress()

        tabs.addTab(tabs.newTab().setText(R.string.tab_protect))
        tabs.addTab(tabs.newTab().setText(R.string.tab_checklist))
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                val showProtect = tab.position == 0
                panelProtect.visibility = if (showProtect) View.VISIBLE else View.GONE
                panelChecklist.visibility = if (showProtect) View.GONE else View.VISIBLE
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
    }

    private fun buildProtect(container: LinearLayout) {
        addText(container, getString(R.string.protect_intro))
        val scanButton = MaterialButton(this).apply {
            text = "Scan photos on this phone"
            isAllCaps = false
            setOnClickListener { startActivity(Intent(this@MainActivity, ScanActivity::class.java)) }
        }
        container.addView(scanButton)

        val webScanButton = MaterialButton(this).apply {
            text = "Search three free image sources"
            isAllCaps = false
            setOnClickListener { startActivity(Intent(this@MainActivity, WebScanActivity::class.java)) }
        }
        container.addView(webScanButton)

        addText(container, "The phone scanner detects faces in selected photos. Web search opens Yandex Images, a SearXNG instance, and DuckDuckGo Images. These searches do not identify people automatically.")
        addSection(container, getString(R.string.section_help))
        protectLinks.forEach { addLinkCard(container, it) }
        addText(container, getString(R.string.safety_note))
    }

    private fun buildChecklist(container: LinearLayout) {
        val header = layoutInflater.inflate(R.layout.item_progress, container, false)
        progressBar = header.findViewById(R.id.progress)
        progressText = header.findViewById(R.id.progressText)
        progressBar.max = checklistItems.size
        container.addView(header)
        addText(container, getString(R.string.checklist_intro))

        checklistItems.forEachIndexed { index, textRes ->
            val card = layoutInflater.inflate(R.layout.item_check, container, false)
            val box = card.findViewById<MaterialCheckBox>(R.id.check)
            box.setText(textRes)
            box.isChecked = prefs.getBoolean("item_$index", false)
            box.setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean("item_$index", checked).apply()
                updateProgress()
            }
            container.addView(card)
        }
        addSection(container, getString(R.string.section_tools))
        toolLinks.forEach { addLinkCard(container, it) }
    }

    private fun updateProgress() {
        val done = checklistItems.indices.count { prefs.getBoolean("item_$it", false) }
        progressBar.setProgressCompat(done, true)
        progressText.text = getString(R.string.progress_format, done, checklistItems.size)
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

    private fun addLinkCard(container: ViewGroup, link: Link) {
        val card = layoutInflater.inflate(R.layout.item_resource, container, false)
        card.findViewById<TextView>(R.id.title).setText(link.titleRes)
        card.findViewById<TextView>(R.id.body).setText(link.bodyRes)
        card.findViewById<MaterialButton>(R.id.button).apply {
            setText(link.buttonRes)
            setOnClickListener { openUrl(link.url) }
        }
        container.addView(card)
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, url, Toast.LENGTH_LONG).show()
        }
    }
}
