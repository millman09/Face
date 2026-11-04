package com.example.face

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * Free public image-search launchers.
 *
 * Searches are opened in the user's browser. No API key is required and no
 * reference photo is uploaded by this screen. SearXNG requires the user to
 * supply an instance they trust.
 */
class WebScanActivity : AppCompatActivity() {

    private lateinit var queryInput: EditText
    private lateinit var searxInput: EditText
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 28, 36, 36)
        }
        val scroll = ScrollView(this)
        scroll.addView(page)
        setContentView(scroll)

        page.addView(TextView(this).apply {
            text = "Free public image search"
            textSize = 24f
            setPadding(0, 0, 0, 18)
        })
        page.addView(TextView(this).apply {
            text = "Run the same text search through Yandex Images, a SearXNG instance, and DuckDuckGo Images. These are public-web searches, not face recognition. They may miss pages and images, and results need to be checked manually."
            textSize = 15f
            setPadding(0, 0, 0, 18)
        })

        queryInput = EditText(this).apply {
            hint = "Search terms, usernames, or site names"
            minLines = 2
        }
        page.addView(queryInput)

        page.addView(TextView(this).apply {
            text = "SearXNG instance address"
            textSize = 14f
            setPadding(0, 12, 0, 4)
        })
        searxInput = EditText(this).apply {
            hint = "https://your-trusted-searxng-instance.example"
            setSingleLine(true)
            setText("https://searx.be")
        }
        page.addView(searxInput)

        page.addView(TextView(this).apply {
            text = "SearXNG is community-hosted. Public instances can change or disappear, and their operators may see your searches. Use an instance you trust. You can change the address above."
            textSize = 13f
            setPadding(0, 4, 0, 16)
        })

        page.addView(Button(this).apply {
            text = "Search all three"
            isAllCaps = false
            setOnClickListener { searchAll() }
        })
        page.addView(Button(this).apply {
            text = "Yandex Images only"
            isAllCaps = false
            setOnClickListener { openSearch(yandexUrl()) }
        })
        page.addView(Button(this).apply {
            text = "SearXNG only"
            isAllCaps = false
            setOnClickListener { openSearch(searxUrl()) }
        })
        page.addView(Button(this).apply {
            text = "DuckDuckGo Images only"
            isAllCaps = false
            setOnClickListener { openSearch(duckDuckGoUrl()) }
        })

        status = TextView(this).apply {
            text = "No searches opened yet."
            textSize = 14f
            setPadding(0, 18, 0, 8)
        }
        page.addView(status)
    }

    private fun currentQuery(): String? {
        val query = queryInput.text.toString().trim()
        if (query.isBlank()) {
            Toast.makeText(this, "Enter search terms first.", Toast.LENGTH_SHORT).show()
            return null
        }
        return query
    }

    private fun yandexUrl(): String? {
        val query = currentQuery() ?: return null
        return Uri.parse("https://yandex.com/images/search")
            .buildUpon()
            .appendQueryParameter("text", query)
            .build()
            .toString()
    }

    private fun duckDuckGoUrl(): String? {
        val query = currentQuery() ?: return null
        return Uri.parse("https://duckduckgo.com/")
            .buildUpon()
            .appendQueryParameter("q", query)
            .appendQueryParameter("ia", "images")
            .appendQueryParameter("iax", "images")
            .build()
            .toString()
    }

    private fun searxUrl(): String? {
        val query = currentQuery() ?: return null
        val raw = searxInput.text.toString().trim()
        val base = try {
            Uri.parse(raw)
        } catch (_: Exception) {
            null
        }
        if (base == null || base.scheme != "https" || base.host.isNullOrBlank()) {
            Toast.makeText(this, "Enter a valid HTTPS SearXNG instance address.", Toast.LENGTH_LONG).show()
            return null
        }
        val cleanBase = raw.trimEnd('/')
        return Uri.parse("$cleanBase/search")
            .buildUpon()
            .appendQueryParameter("q", query)
            .appendQueryParameter("categories", "images")
            .appendQueryParameter("language", "en")
            .build()
            .toString()
    }

    private fun searchAll() {
        val query = currentQuery() ?: return
        val urls = listOfNotNull(
            Uri.parse("https://yandex.com/images/search").buildUpon()
                .appendQueryParameter("text", query).build().toString(),
            searxUrl(),
            Uri.parse("https://duckduckgo.com/").buildUpon()
                .appendQueryParameter("q", query)
                .appendQueryParameter("ia", "images")
                .appendQueryParameter("iax", "images")
                .build().toString()
        )
        if (urls.size != 3) return

        var opened = 0
        for (url in urls) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                opened++
            } catch (_: Exception) {
                // Continue opening the remaining search providers.
            }
        }
        status.text = "Opened $opened of 3 search pages. Check each result page in your browser."
        if (opened == 0) {
            Toast.makeText(this, "No browser could open the search pages.", Toast.LENGTH_LONG).show()
        }
    }

    private fun openSearch(url: String?) {
        if (url == null) return
        openLink(url)
    }

    private fun openLink(link: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)))
            status.text = "Search page opened in your browser."
        } catch (_: Exception) {
            Toast.makeText(this, "Unable to open this search page.", Toast.LENGTH_SHORT).show()
        }
    }
}
