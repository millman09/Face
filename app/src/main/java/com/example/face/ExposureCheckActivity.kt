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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
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
    private lateinit var focusedSwitch: MaterialSwitch
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

        page.addView(body("Tap a section to open it. Everything here only looks things up; nothing is changed or signed into."))

        // Email
        val email = section(page, "Email breaches", "Was your email in a data breach?")
        email.addView(note("Sends your email to XposedOrNot, which lists known breaches (adult sites included). Use your own address."))
        emailInput = input("Your email address", InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        email.addSpaced(emailInput, 8)
        email.addSpaced(button("Check this email") { checkEmail() }, 8)
        email.addSpaced(secondary("Guess my usernames from it") { showGuessedUsernames() }, 4)
        email.addSpaced(secondary("Second opinion: Have I Been Pwned") { open("https://haveibeenpwned.com/") }, 4)
        emailResults = column()
        email.addView(emailResults)

        // Password
        val password = section(page, "Password", "Has a password of yours leaked?")
        password.addView(note("Only the first 5 characters of the password's hash leave your phone, never the password."))
        passwordInput = input("Password to check", InputType.TYPE_TEXT_VARIATION_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS).apply {
            transformationMethod = android.text.method.PasswordTransformationMethod.getInstance()
        }
        password.addSpaced(passwordInput, 8)
        password.addSpaced(button("Check this password") { checkPassword() }, 8)
        passwordResult = body("")
        password.addView(passwordResult)

        // Usernames
        val username = section(page, "Usernames", "Where does your username have a profile?")
        username.addView(note("Takes a minute or two. Sites that block checks show as \"couldn't check\" so you can open them yourself."))
        usernameInput = input("Your username or email address", InputType.TYPE_CLASS_TEXT)
        username.addSpaced(usernameInput, 8)
        adultSwitch = MaterialSwitch(this).apply { text = "Include adult sites"; isChecked = true; setPadding(dp(8), dp(4), dp(8), dp(4)) }
        username.addSpaced(adultSwitch, 2)
        focusedSwitch = MaterialSwitch(this).apply {
            text = "Only adult sites and major popular sites"
            isChecked = getSharedPreferences("username_check", MODE_PRIVATE).getBoolean("focused", true)
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setOnCheckedChangeListener { _, checked ->
                getSharedPreferences("username_check", MODE_PRIVATE).edit().putBoolean("focused", checked).apply()
            }
        }
        username.addSpaced(focusedSwitch, 2)
        username.addSpaced(button("Check this username") { checkUsername() }, 8)
        username.addSpaced(secondary("Guess my emails from it") { showGuessedEmails() }, 4)
        usernameStatus = body("")
        username.addView(usernameStatus)
        usernameResults = column()
        username.addView(usernameResults)
        username.addView(note("Site lists: WhatsMyName (CC BY-SA 4.0) and the Sherlock Project (MIT)."))

        // Domain
        val domain = section(page, "Your domain", "Is a website you own set up safely?")
        domain.addView(note("Checks email-spoofing protection, DNS, HTTPS and security headers with ordinary lookups. Never scans or breaks in."))
        domainInput = input("example.com", InputType.TYPE_TEXT_VARIATION_URI)
        domain.addSpaced(domainInput, 8)
        val owns = MaterialCheckBox(this).apply { text = "I own or manage this domain" }
        domain.addSpaced(owns, 4)
        domainButton = button("Check this domain") { checkDomain() }.apply { isEnabled = false }
        owns.setOnCheckedChangeListener { _, checked -> domainButton.isEnabled = checked }
        domain.addSpaced(domainButton, 8)
        domainResults = column()
        domain.addView(domainResults)

        // Data brokers
        val brokersSection = section(page, "People-search sites", "Remove your name and address listings")
        brokersSection.addView(note("Search for yourself on each site, then use its opt-out page. If a link fails, search \"<site> opt out\"."))
        brokers.forEach { (name, url) -> brokersSection.addSpaced(secondary("$name opt-out") { open(url) }, 4) }
    }

    private val sectionBodies = mutableListOf<LinearLayout>()

    /** A tappable header with a hidden body; opening one section closes the others. Returns the body. */
    private fun section(page: LinearLayout, title: String, subtitle: String): LinearLayout {
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(12))
            visibility = android.view.View.GONE
        }
        val arrow = TextView(this).apply { text = "›"; textSize = 22f; alpha = 0.6f }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.rgb(25, 29, 43))
                cornerRadius = dp(14).toFloat()
            }
            val texts = LinearLayout(this@ExposureCheckActivity).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(TextView(this@ExposureCheckActivity).apply { text = title; textSize = 17f; setTextColor(Color.WHITE) })
            texts.addView(TextView(this@ExposureCheckActivity).apply { text = subtitle; textSize = 13f; alpha = 0.7f })
            addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(arrow)
            setOnClickListener {
                val open = body.visibility != android.view.View.VISIBLE
                sectionBodies.forEach { it.visibility = android.view.View.GONE }
                body.visibility = if (open) android.view.View.VISIBLE else android.view.View.GONE
            }
        }
        sectionBodies += body
        page.addView(header, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
        page.addView(body)
        return body
    }

    private fun note(text: String) = TextView(this).apply {
        this.text = text
        textSize = 13f
        alpha = 0.72f
        setPadding(dp(2), dp(4), dp(2), dp(2))
    }

    private fun secondary(label: String, action: () -> Unit) =
        com.google.android.material.button.MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = label
            isAllCaps = false
            setOnClickListener { action() }
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
        val raw = usernameInput.text.toString()
        val username = UsernameChecker.clean(raw)
        if (username == null) {
            Toast.makeText(this, "Enter a username of 2–40 letters, numbers, dots, dashes or underscores, or an email address.", Toast.LENGTH_LONG).show()
            return
        }
        // When an email was entered, also try it literally as a username (some sites' profile pages use the
        // full address) in addition to the part before the @, which was already checked as a plain username.
        val email = UsernameChecker.cleanEmail(raw)
        val candidates = if (email != null && email != username) listOf(username, email) else listOf(username)

        val includeAdult = adultSwitch.isChecked
        val focused = focusedSwitch.isChecked
        val run = ++usernameRun
        usernamePool?.shutdownNow()
        val checkPool = Executors.newFixedThreadPool(16)
        usernamePool = checkPool
        usernameResults.removeAllViews()
        usernameStatus.text = "Loading the site list…"
        pool.execute {
            val sites = try {
                UsernameChecker.load(this)
                    .filter { includeAdult || !it.adult }
                    .filter { !focused || it.adult || it.popular }
            } catch (e: Exception) {
                runOnUiThread { usernameStatus.text = "Could not load the site list: ${e.localizedMessage ?: "unknown error"}" }
                return@execute
            }
            val total = sites.size * candidates.size
            var done = 0
            var found = 0
            val unsure = mutableListOf<Pair<UsernameChecker.Site, String>>()
            runOnUiThread { if (run == usernameRun) usernameStatus.text = "Checking 0 of $total…" }
            for (candidate in candidates) {
                for (site in sites) {
                    checkPool.execute {
                        val outcome = UsernameChecker.check(site, candidate)
                        runOnUiThread {
                            if (isFinishing || isDestroyed || run != usernameRun) return@runOnUiThread
                            done++
                            when (outcome) {
                                UsernameChecker.Outcome.FOUND -> { found++; addProfileRow(site, candidate, candidates.size > 1) }
                                UsernameChecker.Outcome.UNSURE -> unsure += site to candidate
                                UsernameChecker.Outcome.NOT_FOUND -> {}
                            }
                            usernameStatus.text = if (done < total) "Checking $done of $total… $found found"
                            else "Done: $found profile(s) found. ${unsure.size} couldn't be checked."
                            if (done == total && unsure.isNotEmpty()) {
                                usernameResults.addView(button("Show the ${unsure.size} sites that couldn't be checked") {
                                    showUncheckable(unsure)
                                })
                            }
                        }
                    }
                }
            }
        }
    }

    private fun showUncheckable(entries: List<Pair<UsernameChecker.Site, String>>) {
        val sorted = entries.sortedBy { it.first.name.lowercase() }
        MaterialAlertDialogBuilder(this)
            .setTitle("Check these yourself")
            .setItems(sorted.map { (site, candidate) -> site.name + (if (site.adult) "  ·  adult site" else "") + "  (as $candidate)" }.toTypedArray()) { _, which ->
                val (site, candidate) = sorted[which]
                open(UsernameChecker.profileUrl(site, candidate))
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun addProfileRow(site: UsernameChecker.Site, candidate: String, showWhichForm: Boolean) {
        val url = UsernameChecker.profileUrl(site, candidate)
        usernameResults.addView(TextView(this).apply {
            text = "${site.name}${if (site.adult) "  ·  adult site" else ""}${if (showWhichForm) "  (as $candidate)" else ""}\n$url"
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
