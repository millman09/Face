package com.example.face

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.util.Patterns
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import java.util.concurrent.Executors

/**
 * One place to check your own exposure: email breaches, public profiles for a username, the security
 * setup of a domain you own, and data-broker opt-outs. Everything here is read-only; nothing is attacked.
 */
class ExposureCheckActivity : AppCompatActivity() {

    private val pool = Executors.newFixedThreadPool(8)
    private lateinit var emailInput: EditText
    private lateinit var emailResults: LinearLayout
    private lateinit var usernameInput: EditText
    private lateinit var usernameStatus: TextView
    private lateinit var usernameResults: LinearLayout
    private lateinit var domainInput: EditText
    private lateinit var domainResults: LinearLayout
    private lateinit var domainButton: MaterialButton
    @Volatile private var usernameRun = 0

    private val brokers = listOf(
        "Spokeo" to "https://www.spokeo.com/optout",
        "Whitepages" to "https://www.whitepages.com/suppression-requests",
        "BeenVerified" to "https://www.beenverified.com/app/optout/search",
        "Intelius" to "https://www.intelius.com/opt-out",
        "Radaris" to "https://radaris.com/control/privacy",
        "TruePeopleSearch" to "https://www.truepeoplesearch.com/removal",
        "FastPeopleSearch" to "https://www.fastpeoplesearch.com/removal",
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(28))
        }
        setToolPage("Exposure check", page)

        page.addView(body("Check what is publicly exposed about you: breached email addresses, profiles under your usernames, the security of a domain you own, and data brokers that list you. All checks are read-only."))

        // 1. Email breaches
        page.addView(heading("1. Email breaches"))
        page.addView(body("Your email address is sent to the free XposedOrNot service, which reports the known breaches that include it. Use your own address only."))
        emailInput = input("Your email address", InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        page.addSpaced(emailInput, 8)
        page.addSpaced(button("Check this email for breaches") { checkEmail() }, 8)
        page.addSpaced(button("Second opinion: Have I Been Pwned") { open("https://haveibeenpwned.com/") }, 8)
        emailResults = column()
        page.addView(emailResults)

        // 2. Username
        page.addView(heading("2. Username profiles"))
        page.addView(body("Looks for public profile pages with your username on ${UsernameChecker.sites.size} well-known sites. Sites that block automated checks show as \"couldn't check\"."))
        usernameInput = input("Your username", InputType.TYPE_CLASS_TEXT)
        page.addSpaced(usernameInput, 8)
        page.addSpaced(button("Check this username") { checkUsername() }, 8)
        usernameStatus = body("")
        page.addView(usernameStatus)
        usernameResults = column()
        page.addView(usernameResults)
        page.addSpaced(button("Search my accounts in the browser instead") {
            startActivity(Intent(this, AccountAuditActivity::class.java))
        }, 8)

        // 3. Domain
        page.addView(heading("3. Domain security"))
        page.addView(body("Checks email-spoofing protection (SPF, DMARC), DNS, the HTTPS certificate, redirects and security headers. It makes ordinary lookups and one normal page request. It never scans ports or tries to break in."))
        domainInput = input("example.com", InputType.TYPE_TEXT_VARIATION_URI)
        page.addSpaced(domainInput, 8)
        val owns = MaterialCheckBox(this).apply { text = "I own or manage this domain" }
        page.addSpaced(owns, 4)
        domainButton = button("Check this domain") { checkDomain() }.apply { isEnabled = false }
        owns.setOnCheckedChangeListener { _, checked -> domainButton.isEnabled = checked }
        page.addSpaced(domainButton, 8)
        domainResults = column()
        page.addView(domainResults)

        // 4. Data brokers
        page.addView(heading("4. Data brokers"))
        page.addView(body("People-search sites often list your name, address and relatives. Search for yourself on each, then use its opt-out page. Addresses can change; if one fails, search \"<site> opt out\"."))
        brokers.forEach { (name, url) ->
            page.addSpaced(button("$name opt-out") { open(url) }, 4)
        }
        page.addView(body("Automatic removal from 200+ brokers is a paid service (Pentester.com is one). This app can only point you to the forms."))
    }

    override fun onDestroy() {
        pool.shutdownNow()
        super.onDestroy()
    }

    // ---- email ----

    private fun checkEmail() {
        val email = emailInput.text.toString().trim()
        if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            Toast.makeText(this, "Enter a valid email address.", Toast.LENGTH_SHORT).show()
            return
        }
        emailResults.removeAllViews()
        emailResults.addView(body("Checking…"))
        pool.execute {
            val result = BreachChecker.check(email)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                emailResults.removeAllViews()
                when (result) {
                    is BreachChecker.Result.None -> addFinding(emailResults, DomainChecker.Level.GOOD, "No known breaches", "This address wasn't found in the breaches the service tracks. That doesn't guarantee it is safe.")
                    is BreachChecker.Result.Error -> addFinding(emailResults, DomainChecker.Level.INFO, "Couldn't check", result.message)
                    is BreachChecker.Result.Found -> {
                        addFinding(
                            emailResults, DomainChecker.Level.BAD, "Found in ${result.breaches.size} breach(es)",
                            result.breaches.joinToString(", ") + "\nChange the password on each of these sites (and anywhere you reused it), and turn on two-step login."
                        )
                    }
                }
            }
        }
    }

    // ---- username ----

    private fun checkUsername() {
        val username = UsernameChecker.clean(usernameInput.text.toString())
        if (username == null) {
            Toast.makeText(this, "Enter a username of 2–40 letters, numbers, dots, dashes or underscores.", Toast.LENGTH_LONG).show()
            return
        }
        val run = ++usernameRun
        usernameResults.removeAllViews()
        val sites = UsernameChecker.sites
        var done = 0
        var found = 0
        var unsure = 0
        usernameStatus.text = "Checking 0 of ${sites.size}…"
        for (site in sites) {
            pool.execute {
                val outcome = UsernameChecker.check(site, username)
                runOnUiThread {
                    if (isFinishing || isDestroyed || run != usernameRun) return@runOnUiThread
                    done++
                    when (outcome) {
                        UsernameChecker.Outcome.FOUND -> { found++; addProfileRow(site, username) }
                        UsernameChecker.Outcome.UNSURE -> unsure++
                        UsernameChecker.Outcome.NOT_FOUND -> {}
                    }
                    usernameStatus.text = if (done < sites.size) "Checking $done of ${sites.size}… $found found"
                    else "Done: $found profile(s) found on ${sites.size} sites. $unsure couldn't be checked."
                }
            }
        }
    }

    private fun addProfileRow(site: UsernameChecker.Site, username: String) {
        val url = UsernameChecker.profileUrl(site, username)
        usernameResults.addView(TextView(this).apply {
            text = "${site.name}\n$url"
            textSize = 14f
            setPadding(dp(4), dp(10), dp(4), dp(10))
            setTextColor(Color.rgb(155, 139, 255))
            setOnClickListener { open(url) }
        })
    }

    // ---- domain ----

    private fun checkDomain() {
        val domain = DomainChecker.clean(domainInput.text.toString())
        if (domain == null) {
            Toast.makeText(this, "Enter a domain name like example.com.", Toast.LENGTH_LONG).show()
            return
        }
        domainButton.isEnabled = false
        domainResults.removeAllViews()
        domainResults.addView(body("Checking $domain…"))
        pool.execute {
            var first = true
            try {
                DomainChecker.run(domain) { finding ->
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        if (first) { domainResults.removeAllViews(); first = false }
                        addFinding(domainResults, finding.level, finding.title, finding.detail)
                    }
                }
            } finally {
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        domainButton.isEnabled = true
                        if (first) { domainResults.removeAllViews(); domainResults.addView(body("No results.")) }
                    }
                }
            }
        }
    }

    // ---- small UI helpers ----

    private fun addFinding(container: LinearLayout, level: DomainChecker.Level, title: String, detail: String) {
        val (symbol, color) = when (level) {
            DomainChecker.Level.GOOD -> "✓" to Color.rgb(120, 200, 140)
            DomainChecker.Level.WARN -> "!" to Color.rgb(255, 200, 120)
            DomainChecker.Level.BAD -> "✗" to Color.rgb(255, 120, 120)
            DomainChecker.Level.INFO -> "•" to Color.GRAY
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(8), 0, dp(8)) }
        row.addView(TextView(this).apply { text = "$symbol  $title"; textSize = 15f; setTextColor(color) })
        if (detail.isNotBlank()) row.addView(TextView(this).apply { text = detail; textSize = 13f; setPadding(dp(18), dp(2), 0, 0) })
        container.addView(row)
    }

    private fun heading(text: String) = TextView(this).apply {
        this.text = text
        textSize = 17f
        setPadding(0, dp(24), 0, dp(4))
    }

    private fun body(text: String) = TextView(this).apply {
        this.text = text
        textSize = 13f
        setPadding(0, dp(2), 0, dp(8))
    }

    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    private fun input(hint: String, type: Int) = EditText(this).apply {
        this.hint = hint
        setSingleLine(true)
        inputType = InputType.TYPE_CLASS_TEXT or type or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
    }

    private fun button(label: String, action: () -> Unit) = MaterialButton(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { action() }
    }

    private fun open(url: String) {
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        catch (_: Exception) { Toast.makeText(this, "No browser could open this page.", Toast.LENGTH_SHORT).show() }
    }
}
