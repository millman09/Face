package com.example.face

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.tabs.TabLayout

class MainActivity : AppCompatActivity() {

    private data class Resource(
        val titleRes: Int,
        val bodyRes: Int,
        val buttonRes: Int,
        val url: String
    )

    private val helpLinks = listOf(
        Resource(
            R.string.hibp_title, R.string.hibp_body, R.string.hibp_button,
            "https://haveibeenpwned.com"
        ),
        Resource(
            R.string.stopncii_title, R.string.stopncii_body, R.string.stopncii_button,
            "https://stopncii.org"
        ),
        Resource(
            R.string.takeitdown_title, R.string.takeitdown_body, R.string.takeitdown_button,
            "https://takeitdown.ncmec.org"
        ),
        Resource(
            R.string.google_title, R.string.google_body, R.string.google_button,
            "https://support.google.com/websearch/answer/13650142"
        )
    )

    private val checklistItems = listOf(
        R.string.check_1, R.string.check_2, R.string.check_3,
        R.string.check_4, R.string.check_5, R.string.check_6,
        R.string.check_7, R.string.check_8, R.string.check_9
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val tabs = findViewById<TabLayout>(R.id.tabs)
        val panelProtect = findViewById<View>(R.id.panelProtect)
        val panelChecklist = findViewById<View>(R.id.panelChecklist)

        buildProtect(findViewById(R.id.protectContainer))
        buildChecklist(findViewById(R.id.checklistContainer))

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
        container.addView(body(getString(R.string.protect_intro)))
        for (r in helpLinks) {
            container.addView(heading(getString(r.titleRes)))
            container.addView(body(getString(r.bodyRes)))
            container.addView(MaterialButton(this).apply {
                setText(r.buttonRes)
                setOnClickListener { openUrl(r.url) }
            })
        }
        container.addView(body(getString(R.string.safety_note)).apply { setPadding(0, dp(24), 0, dp(8)) })
    }

    private fun buildChecklist(container: LinearLayout) {
        val prefs = getSharedPreferences("checklist", MODE_PRIVATE)
        container.addView(body(getString(R.string.checklist_intro)))
        checklistItems.forEachIndexed { index, textRes ->
            container.addView(CheckBox(this).apply {
                setText(textRes)
                isChecked = prefs.getBoolean("item_$index", false)
                setOnCheckedChangeListener { _, checked ->
                    prefs.edit().putBoolean("item_$index", checked).apply()
                }
            })
        }
    }

    private fun heading(text: String) = TextView(this).apply {
        this.text = text
        textSize = 18f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, dp(24), 0, dp(4))
    }

    private fun body(text: String) = TextView(this).apply {
        this.text = text
        textSize = 15f
        setPadding(0, dp(4), 0, dp(8))
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, url, Toast.LENGTH_LONG).show()
        }
    }
}
