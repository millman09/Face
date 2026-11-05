package com.example.face

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import org.json.JSONArray
import org.json.JSONObject

/**
 * A private log of pages where you found an image of yourself and how far the
 * removal has got. It is stored only on this phone and nothing is uploaded.
 */
class RemovalLogActivity : AppCompatActivity() {

    private val statuses = listOf("Found", "Reported", "Removed")
    private val prefs by lazy { getSharedPreferences("removal_log", MODE_PRIVATE) }

    private lateinit var input: EditText
    private lateinit var list: LinearLayout
    private lateinit var summary: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(12))
        }
        setToolPage("Removal log", page)

        page.addView(TextView(this).apply {
            text = "Keep track of pages you want removed. Stored only on this phone."
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
            setPadding(0, 0, 0, dp(8))
        })
        input = EditText(this).apply {
            hint = "Page address"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        page.addView(input)
        page.addSpaced(MaterialButton(this).apply {
            text = "Add to log"
            isAllCaps = false
            setOnClickListener { add() }
        }, 8)
        summary = TextView(this).apply {
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleSmall)
            setPadding(0, dp(4), 0, dp(6))
        }
        page.addView(summary)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        page.addView(list)
        render()
    }

    private fun load(): MutableList<Pair<String, Int>> {
        val out = mutableListOf<Pair<String, Int>>()
        try {
            val array = JSONArray(prefs.getString("items", "[]"))
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                out.add(item.getString("url") to item.optInt("status", 0).coerceIn(0, statuses.size - 1))
            }
        } catch (_: Exception) { }
        return out
    }

    private fun save(items: List<Pair<String, Int>>) {
        val array = JSONArray()
        items.forEach { (url, status) -> array.put(JSONObject().put("url", url).put("status", status)) }
        prefs.edit().putString("items", array.toString()).apply()
    }

    private fun add() {
        var text = input.text.toString().trim()
        if (text.isEmpty() || !text.contains('.') || text.contains(' ')) {
            Toast.makeText(this, "Enter a page address.", Toast.LENGTH_SHORT).show()
            return
        }
        if (!text.startsWith("http://") && !text.startsWith("https://")) text = "https://$text"
        val items = load()
        if (items.any { it.first == text }) {
            Toast.makeText(this, "That page is already in the log.", Toast.LENGTH_SHORT).show()
            return
        }
        items.add(0, text to 0)
        save(items)
        input.text.clear()
        render()
    }

    private fun render() {
        val items = load()
        list.removeAllViews()
        val removed = items.count { it.second == statuses.size - 1 }
        summary.text = if (items.isEmpty()) "No pages logged yet." else "$removed of ${items.size} removed"
        items.forEachIndexed { index, (url, status) ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(10), dp(16), dp(6))
            }
            row.addView(TextView(this).apply {
                text = url
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
                setOnClickListener { open(url) }
            })
            val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            buttons.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = statuses[status]
                isAllCaps = false
                setOnClickListener {
                    val current = load()
                    if (index < current.size) {
                        current[index] = current[index].first to (current[index].second + 1) % statuses.size
                        save(current)
                        render()
                    }
                }
            })
            buttons.addView(MaterialButton(this, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
                text = "Delete"
                isAllCaps = false
                setOnClickListener {
                    val current = load()
                    if (index < current.size) {
                        current.removeAt(index)
                        save(current)
                        render()
                    }
                }
            })
            row.addView(buttons)
            val card = MaterialCardView(this, null, com.google.android.material.R.attr.materialCardViewFilledStyle)
            card.addView(row)
            list.addSpaced(card, 6)
        }
    }

    private fun open(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, url, Toast.LENGTH_LONG).show()
        }
    }
}
