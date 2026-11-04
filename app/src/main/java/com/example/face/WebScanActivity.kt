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
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.util.concurrent.Executors

/**
 * Public web image discovery using the Brave Image Search API.
 *
 * The API key is entered by the user for each scan and is never persisted.
 * Search terms and the API request are sent to Brave. Reference photos are
 * never uploaded. Results are leads only: this screen does not identify faces.
 */
class WebScanActivity : AppCompatActivity() {

    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var queryInput: EditText
    private lateinit var keyInput: EditText
    private lateinit var status: TextView
    private lateinit var searchButton: Button
    private lateinit var results: LinearLayout

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
            text = "Public web image search"
            textSize = 24f
            setPadding(0, 0, 0, 18)
        })
        page.addView(TextView(this).apply {
            text = "Searches Brave's public image index. It does not crawl every adult site or forum, and it cannot access private, login-only, or paywalled pages. Search results are possible leads, not proof."
            textSize = 15f
            setPadding(0, 0, 0, 18)
        })

        queryInput = EditText(this).apply {
            hint = "Search terms (example: a username or site:example.com)"
            minLines = 2
            setText(" ")
        }
        page.addView(queryInput)

        keyInput = EditText(this).apply {
            hint = "Brave Search API key"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        page.addView(keyInput)

        page.addView(TextView(this).apply {
            text = "Get an API key from api.search.brave.com. The key is used only for this request and is not saved in the app. Brave receives your search query. Your reference photo is not sent."
            textSize = 13f
            setPadding(0, 8, 0, 16)
        })

        searchButton = Button(this).apply {
            text = "Search public image index"
            isAllCaps = false
            setOnClickListener { runSearch() }
        }
        page.addView(searchButton)

        status = TextView(this).apply {
            text = "No web search run yet."
            textSize = 14f
            setPadding(0, 18, 0, 12)
        }
        page.addView(status)

        results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        page.addView(results)
    }

    private fun runSearch() {
        val query = queryInput.text.toString().trim()
        val apiKey = keyInput.text.toString().trim()
        if (query.isBlank()) {
            Toast.makeText(this, "Enter search terms first.", Toast.LENGTH_SHORT).show()
            return
        }
        if (apiKey.isBlank()) {
            Toast.makeText(this, "Enter your Brave API key.", Toast.LENGTH_SHORT).show()
            return
        }

        searchButton.isEnabled = false
        status.text = "Searching Brave's public image index…"
        results.removeAllViews()

        executor.execute {
            try {
                val encoded = URLEncoder.encode(query, "UTF-8")
                val endpoint = URL("https://api.search.brave.com/res/v1/images/search?q=$encoded&country=US&search_lang=en&count=50&safesearch=off")
                val connection = (endpoint.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 15000
                    readTimeout = 20000
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("X-Subscription-Token", apiKey)
                }
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val body = stream.bufferedReader().use { it.readText() }
                connection.disconnect()
                if (code !in 200..299) {
                    throw IllegalStateException("Brave returned HTTP $code. Check the API key, account access, and plan.")
                }

                val json = JSONObject(body)
                val items = json.optJSONArray("results")
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    val count = items?.length() ?: 0
                    status.text = "Found $count indexed image result(s). Open a result to inspect its source page."
                    if (count == 0) {
                        addResultText("No results returned. Try broader terms or another public source.")
                    } else {
                        for (i in 0 until count) {
                            val item = items!!.optJSONObject(i) ?: continue
                            val title = item.optString("title", "Untitled image result")
                            val source = item.optString("source", "Unknown source")
                            val pageUrl = item.optString("url", "")
                            val imageUrl = item.optJSONObject("properties")?.optString("url", "") ?: ""
                            addResult(title, source, pageUrl, imageUrl)
                        }
                    }
                    searchButton.isEnabled = true
                }
            } catch (error: Exception) {
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    status.text = "Search failed: ${error.localizedMessage ?: "unknown error"}"
                    searchButton.isEnabled = true
                }
            }
        }
    }

    private fun addResult(title: String, source: String, pageUrl: String, imageUrl: String) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 14, 0, 14)
        }
        card.addView(TextView(this).apply {
            text = title
            textSize = 16f
        })
        card.addView(TextView(this).apply {
            text = source
            textSize = 13f
        })
        if (pageUrl.startsWith("https://") || pageUrl.startsWith("http://")) {
            card.addView(Button(this).apply {
                text = "Open source page"
                isAllCaps = false
                setOnClickListener { openLink(pageUrl) }
            })
        }
        if (imageUrl.startsWith("https://") || imageUrl.startsWith("http://")) {
            card.addView(Button(this).apply {
                text = "Open image"
                isAllCaps = false
                setOnClickListener { openLink(imageUrl) }
            })
        }
        results.addView(card)
        val divider = android.view.View(this).apply {
            setBackgroundColor(0xFF444444.toInt())
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
        }
        results.addView(divider)
    }

    private fun addResultText(message: String) {
        results.addView(TextView(this).apply { text = message; textSize = 14f })
    }

    private fun openLink(link: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)))
        } catch (_: Exception) {
            Toast.makeText(this, "Unable to open this link.", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        executor.shutdown()
        super.onDestroy()
    }
}
