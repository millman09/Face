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
import com.google.android.material.button.MaterialButton

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
            setPadding(dp(20), dp(16), dp(20), dp(24))
        }
        setScrollablePage(page)

        page.addView(TextView(this).apply {
            text = "Free public image search"
            textSize = 24f
            setPadding(0, 0, 0, dp(8))
        })
        page.addView(TextView(this).apply {
            text = "Run the same text search through Yandex Images, a SearXNG instance, and DuckDuckGo Images. These are public-web searches, not face recognition. They may miss pages and images, and results need to be checked manually."
            textSize = 15f
            setPadding(0, 0, 0, dp(16))
        })

        queryInput = EditText(this).apply {
            hint = "Search terms, usernames, or site names"
            minLines = 2
            // Keep the keyboard from suggesting or learning what you type here.
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
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
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_VARIATION_URI or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        }
        page.addView(searxInput)

        page.addView(TextView(this).apply {
            text = "SearXNG is community-hosted. Public instances can change or disappear, and their operators may see your searches. Use an instance you trust. You can change the address above."
            textSize = 13f
            setPadding(0, dp(4), 0, dp(16))
        })

        page.addSpaced(button("Search all three") { searchAll() })
        page.addSpaced(button("Yandex Images only") { currentQuery()?.let { openLink(yandexUrl(it)) } })
        page.addSpaced(button("SearXNG only") {
            val query = currentQuery() ?: return@button
            searxUrl(query)?.let { openLink(it) }
        })
        page.addSpaced(button("DuckDuckGo Images only") { currentQuery()?.let { openLink(duckDuckGoUrl(it)) } })

        status = TextView(this).apply {
            text = "No searches opened yet."
            textSize = 14f
            setPadding(0, dp(12), 0, dp(8))
        }
        page.addView(status)
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
        Uri.parse("https://yandex.com/images/search")
            .buildUpon()
            .appendQueryParameter("text", query)
            .build()
            .toString()

    private fun duckDuckGoUrl(query: String): String =
        Uri.parse("https://duckduckgo.com/")
            .buildUpon()
            .appendQueryParameter("q", query)
            .appendQueryParameter("ia", "images")
            .appendQueryParameter("iax", "images")
            .build()
            .toString()

    private fun searxUrl(query: String): String? {
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
        return Uri.parse(raw.trimEnd('/') + "/search")
            .buildUpon()
            .appendQueryParameter("q", query)
            .appendQueryParameter("categories", "images")
            .appendQueryParameter("language", "en")
            .build()
            .toString()
    }

    private fun searchAll() {
        val query = currentQuery() ?: return
        // If the SearXNG address is invalid, still open the other two.
        val urls = listOfNotNull(yandexUrl(query), searxUrl(query), duckDuckGoUrl(query))

        var opened = 0
        for (url in urls) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                opened++
            } catch (_: Exception) {
                // Keep going so the remaining search providers still open.
            }
        }
        status.text = "Opened $opened of ${urls.size} search pages. Check each result page in your browser."
        if (opened == 0) {
            Toast.makeText(this, "No browser could open the search pages.", Toast.LENGTH_LONG).show()
        }
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
