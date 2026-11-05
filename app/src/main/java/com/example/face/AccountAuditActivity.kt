package com.example.face

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton

/**
 * A local-only helper for checking the account owner's own identifiers against
 * publicly indexed pages. Searches are handed to the user's browser; this app
 * does not scrape private areas or collect/store the entered identifiers.
 */
class AccountAuditActivity : AppCompatActivity() {
    private lateinit var username: EditText
    private lateinit var email: EditText
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.rgb(13, 16, 24)
        window.navigationBarColor = android.graphics.Color.rgb(13, 16, 24)

        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(28))
            setBackgroundColor(android.graphics.Color.rgb(13, 16, 24))
        }
        setContentView(android.widget.ScrollView(this).apply { addView(page) })

        page.addView(label("ACCOUNT EXPOSURE CHECK", 12, true, android.graphics.Color.rgb(173, 160, 255)))
        page.addView(label("Check your accounts online", 28, true, android.graphics.Color.WHITE, dp(10)))
        page.addView(label(
            "Check your own usernames and email addresses for publicly indexed profile pages and known breach exposure. Results open in your browser.",
            15, false, android.graphics.Color.rgb(195, 199, 211), dp(20)
        ))

        page.addView(label("USERNAME", 12, true, android.graphics.Color.rgb(173, 160, 255), dp(8)))
        username = EditText(this).apply {
            hint = "Enter a username you use"
            singleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
            setTextColor(android.graphics.Color.WHITE)
            setHintTextColor(android.graphics.Color.GRAY)
        }
        page.addView(username, matchWidth())

        page.addView(label("EMAIL ADDRESS", 12, true, android.graphics.Color.rgb(173, 160, 255), dp(16)))
        email = EditText(this).apply {
            hint = "Enter your own email address"
            singleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            setTextColor(android.graphics.Color.WHITE)
            setHintTextColor(android.graphics.Color.GRAY)
        }
        page.addView(email, matchWidth())

        page.addView(button("Search public profile pages") { searchPublicPages() }, matchWidth(dp(18)))
        page.addView(button("Check email breach exposure") { checkBreaches() }, matchWidth(dp(8)))

        status = label("Ready. Nothing is searched until you tap a button.", 13, false,
            android.graphics.Color.rgb(190, 195, 210), dp(14))
        page.addView(status)

        val note = label(
            "PRIVACY: Your entries are not saved by this screen. When you start a search, the selected search provider receives the query. Use only identifiers belonging to you or that you are authorized to audit. Public search results are incomplete and do not prove that an account exists or that a person uses a site. This check does not access private accounts, leaked-password databases, or restricted pages.",
            13, false, android.graphics.Color.rgb(165, 171, 188), dp(22)
        )
        page.addView(note)
    }

    private fun searchPublicPages() {
        val handle = username.text.toString().trim()
        val address = email.text.toString().trim()
        if (handle.isBlank() && address.isBlank()) {
            Toast.makeText(this, "Enter your username or email first.", Toast.LENGTH_SHORT).show()
            return
        }
        val identifiers = listOf(handle, address).filter { it.isNotBlank() }
        val adultDomains = listOf(
            "onlyfans.com", "fansly.com", "fanvue.com", "manyvids.com",
            "justfor.fans", "loyalfans.com", "fancentro.com", "chaturbate.com",
            "stripchat.com", "myfreecams.com", "aturbate.com", "fapello.com",
            "erome.com", "motherless.com", "imagefap.com", "pornpics.com",
            "socialmediagirls.com", "simpcity.su", "gfy.com", "reddit.com"
        )
        val terms = identifiers.joinToString(" OR ") { "\"$it\"" }
        val siteFilter = adultDomains.joinToString(" OR ") { "site:$it" }
        val query = "($terms) ($siteFilter)"
        val url = Uri.parse("https://www.google.com/search").buildUpon()
            .appendQueryParameter("q", query).build().toString()
        openSearch(url, "Public search opened. Review each result and verify the profile yourself.")
    }

    private fun checkBreaches() {
        val address = email.text.toString().trim()
        if (address.isBlank() || !android.util.Patterns.EMAIL_ADDRESS.matcher(address).matches()) {
            Toast.makeText(this, "Enter a valid email address that belongs to you.", Toast.LENGTH_SHORT).show()
            return
        }
        val url = "https://haveibeenpwned.com/account/" + Uri.encode(address)
        openSearch(url, "Have I Been Pwned opened for the email address you entered.")
    }

    private fun openSearch(url: String, message: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            status.text = message
        } catch (_: Exception) {
            status.text = "No browser is available to open this search."
        }
    }

    private fun label(
        value: String, size: Int, bold: Boolean, color: Int, top: Int = 0
    ) = TextView(this).apply {
        text = value
        textSize = size.toFloat()
        setTextColor(color)
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, top, 0, dp(8))
        setLineSpacing(dp(2).toFloat(), 1.04f)
    }

    private fun button(value: String, action: () -> Unit) = MaterialButton(this).apply {
        text = value
        isAllCaps = false
        textSize = 15f
        setOnClickListener { action() }
    }

    private fun matchWidth(top: Int = 0) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { if (top > 0) topMargin = top }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
