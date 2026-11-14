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
import com.google.android.material.materialswitch.MaterialSwitch
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
    private lateinit var passwordInput: EditText
    private lateinit var passwordResult: TextView
    private lateinit var domainInput: EditText
    private lateinit var domainResults: LinearLayout
    private lateinit var domainButton: MaterialButton
    @Volatile private var usernameRun = 0
    private lateinit var adultSwitch: MaterialSwitch
    private var usernamePool: java.util.concurrent.ExecutorService? = null

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
        page.addView(body("Your email address is sent to the free XposedOrNot service, which reports the known breaches that include it, adult sites included. A site's own member list can't be searched without signing up or resetting passwords there, which this app never does, so breach records are how exposure shows up. Use your own address only."))
        emailInput = input("Your email address", InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        page.addSpaced(emailInput, 8)
        page.addSpaced(button("Check this email for breaches") { checkEmail() }, 8)
        page.addSpaced(button("Guess usernames from this email") { showGuessedUsernames() }, 8)
        page.addSpaced(button("Second opinion: Have I Been Pwned") { open("https://haveibeenpwned.com/") }, 8)
        emailResults = column()
        page.addView(emailResults)

        // 2. Username
        // Password check
        page.addView(heading("1b. Check a password"))
        page.addView(body("Checks a password against Have I Been Pwned's Pwned Passwords list of billions of passwords seen in breach dumps. Only the first 5 characters of its hash are sent, never the password itself or its full hash, so it can't be reconstructed from what leaves your phone."))
        passwordInput = input("Password to check", InputType.TYPE_TEXT_VARIATION_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS).apply {
            transformationMethod = android.text.method.PasswordTransformationMethod.getInstance()
        }
        page.addSpaced(passwordInput, 8)
        page.addSpaced(button("Check this password") { checkPassword() }, 8)
        passwordResult = body("")
        page.addView(passwordResult)

        page.addView(heading("2. Username profiles"))
        page.addView(body("Looks for a public profile page with your username on about 650 sites, adult sites included. It takes a minute or two. Sites that block automated checks show as \"couldn't check\", and you can open those yourself."))
        usernameInput = input("Your username", InputType.TYPE_CLASS_TEXT)
        page.addSpaced(usernameInput, 8)
        adultSwitch = MaterialSwitch(this).apply { text = "Include adult sites"; isChecked = true; setPadding(dp(8), dp(8), dp(8), dp(8)) }
        page.addSpaced(adultSwitch, 4)
        page.addSpaced(button("Check this username") { checkUsername() }, 8)
        page.addSpaced(button("Guess emails from this username") { showGuessedEmails() }, 8)
        usernameStatus = body("")
        page.addView(usernameStatus)
        usernameResults = column()
        page.addView(usernameResults)
        page.addView(body("Site list and detection rules: WhatsMyName project by Micah Hoffman (github.com/WebBreacher/WhatsMyName), CC BY-SA 4.0."))

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
        usernamePool?.shutdownNow()
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
                        val adult = result.breaches.filter { BreachChecker.isAdult(it) }
                        addFinding(
                            emailResults, DomainChecker.Level.BAD, "Found in ${result.breaches.size} breach(es)",
                            result.breaches.joinToString(", ") + "\nChange the password on each of these sites (and anywhere you reused it), and turn on two-step login."
                        )
                        if (adult.isNotEmpty()) addFinding(
                            emailResults, DomainChecker.Level.BAD, "Adult-site breaches: ${adult.size}",
                            adult.joinToString(", ") + "\nThese lists can link your email to adult accounts. Remove or close those accounts if you can, and don't reuse that password or email for anything else."
                        )
                        emailResults.addView(button("What was exposed in each breach?") { showBreachDetail(email) })
                    }
                }
            }
        }
    }

    /** A second, separate lookup: per breach, which kinds of data it exposed (not just its name). */
    private fun showBreachDetail(email: String) {
        emailResults.addView(body("Looking up details…").also { it.tag = "detail-status" })
        pool.execute {
            val details = BreachChecker.analyticsDetail(email)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                emailResults.findViewWithTag<TextView>("detail-status")?.let { emailResults.removeView(it) }
                if (details.isEmpty()) {
                    addFinding(emailResults, DomainChecker.Level.INFO, "No further detail available", "The breach-detail lookup didn't return anything right now.")
                    return@runOnUiThread
                }
                for (d in details) {
                    val label = if (d.year.isNotBlank()) "${d.name} (${d.year})" else d.name
                    val adultTag = if (BreachChecker.isAdult(d.name)) "  ·  adult site" else ""
                    addFinding(
                        emailResults, DomainChecker.Level.BAD, "$label$adultTag",
                        if (d.exposedData.isEmpty()) "Exposed data types not listed." else "Exposed: ${d.exposedData.joinToString(", ")}"
                    )
                }
            }
        }
    }

    // ---- guessed usernames / emails ----

    /** Not a lookup: shows likely usernames derived from the email, each tappable to check it. */
    private fun showGuessedUsernames() {
        val email = emailInput.text.toString().trim()
        val guesses = IdentityGuesser.usernamesFromEmail(email)
        if (guesses.isEmpty()) {
            Toast.makeText(this, "Enter an email address first.", Toast.LENGTH_SHORT).show()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Guessed usernames")
            .setMessage("These are common patterns people derive from an email address, not a confirmed link. Nothing is checked until you pick one.")
            .setItems(guesses.toTypedArray()) { _, which ->
                usernameInput.setText(guesses[which])
                checkUsername()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    /** Not a lookup: shows likely email addresses derived from the username, each tappable to run the breach check. */
    private fun showGuessedEmails() {
        val username = usernameInput.text.toString().trim()
        val guesses = IdentityGuesser.emailsFromUsername(username)
        if (guesses.isEmpty()) {
            Toast.makeText(this, "Enter a username first.", Toast.LENGTH_SHORT).show()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Guessed email addresses")
            .setMessage("These are common patterns at major providers, not confirmed to exist. This app never checks whether an email account exists; picking one here only runs the breach check against it.")
            .setItems(guesses.toTypedArray()) { _, which ->
                emailInput.setText(guesses[which])
                checkEmail()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    // ---- password ----

    private fun checkPassword() {
        val password = passwordInput.text.toString()
        if (password.isEmpty()) {
            Toast.makeText(this, "Enter a password to check.", Toast.LENGTH_SHORT).show()
            return
        }
        passwordResult.setTextColor(Color.GRAY)
        passwordResult.text = "Checking…"
        pool.execute {
            val count = BreachChecker.checkPassword(password)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                when {
                    count < 0 -> { passwordResult.setTextColor(Color.GRAY); passwordResult.text = "Could not reach the password-check service." }
                    count == 0 -> { passwordResult.setTextColor(Color.rgb(120, 200, 140)); passwordResult.text = "✓ Not found in known breach dumps." }
                    else -> { passwordResult.setTextColor(Color.rgb(255, 120, 120)); passwordResult.text = "✗ Seen ${"%,d".format(count)} time(s) in breach dumps. Stop using this password and change it anywhere you have." }
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
        val includeAdult = adultSwitch.isChecked
        val run = ++usernameRun
        usernamePool?.shutdownNow()
        val checkPool = Executors.newFixedThreadPool(16)
        usernamePool = checkPool
        usernameResults.removeAllViews()
        usernameStatus.text = "Loading the site list…"
        pool.execute {
            val sites = try {
                UsernameChecker.load(this).filter { includeAdult || !it.adult }
            } catch (e: Exception) {
                runOnUiThread { usernameStatus.text = "Could not load the site list: ${e.localizedMessage ?: "unknown error"}" }
                return@execute
            }
            var done = 0
            var found = 0
            val unsure = mutableListOf<UsernameChecker.Site>()
            runOnUiThread { if (run == usernameRun) usernameStatus.text = "Checking 0 of ${sites.size}…" }
            for (site in sites) {
                checkPool.execute {
                    val outcome = UsernameChecker.check(site, username)
                    runOnUiThread {
                        if (isFinishing || isDestroyed || run != usernameRun) return@runOnUiThread
                        done++
                        when (outcome) {
                            UsernameChecker.Outcome.FOUND -> { found++; addProfileRow(site, username) }
                            UsernameChecker.Outcome.UNSURE -> unsure += site
                            UsernameChecker.Outcome.NOT_FOUND -> {}
                        }
                        usernameStatus.text = if (done < sites.size) "Checking $done of ${sites.size}… $found found"
                        else "Done: $found profile(s) found on ${sites.size} sites. ${unsure.size} couldn't be checked."
                        if (done == sites.size && unsure.isNotEmpty()) {
                            usernameResults.addView(button("Show the ${unsure.size} sites that couldn't be checked") {
                                showUncheckable(unsure, username)
                            })
                        }
                    }
                }
            }
        }
    }

    private fun showUncheckable(sites: List<UsernameChecker.Site>, username: String) {
        val sorted = sites.sortedBy { it.name.lowercase() }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Check these yourself")
            .setItems(sorted.map { it.name + if (it.adult) "  ·  adult site" else "" }.toTypedArray()) { _, which ->
                open(UsernameChecker.profileUrl(sorted[which], username))
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun addProfileRow(site: UsernameChecker.Site, username: String) {
        val url = UsernameChecker.profileUrl(site, username)
        usernameResults.addView(TextView(this).apply {
            text = "${site.name}${if (site.adult) "  ·  adult site" else ""}\n$url"
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
