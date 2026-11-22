package com.example.face

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Checks whether a username has a public profile page on hundreds of sites. The site list and the rules for
 * telling "found" from "not found" are combined from two open-source projects, bundled in assets/wmn-sites.json:
 * WhatsMyName (github.com/WebBreacher/WhatsMyName, CC BY-SA 4.0, by Micah Hoffman) and the Sherlock Project
 * (github.com/sherlock-project/sherlock, MIT License). Each check is one normal page request. It never logs in.
 * Meant for checking your own usernames.
 */
object UsernameChecker {
    enum class Outcome { FOUND, NOT_FOUND, UNSURE }

    /**
     * [url] contains {account} where the username goes.
     *
     * In the ordinary (non-[codeRange]) rule, a page counts as "found" when it answers [foundCode] and contains
     * [foundText] (if given); as "not found" when it answers [missingCode] or contains [missingText].
     *
     * When [codeRange] is set (used for Sherlock-sourced sites whose only signal is the response code), "found"
     * means any 2xx response, and "not found" means anything else — unless [missingCode] is also given, which
     * then overrides a 2xx that actually means "not found" on that particular site. [noRedirects] additionally
     * stops the request from following a redirect, for sites where the redirect itself (rather than the page it
     * leads to) is the "not found" signal.
     */
    data class Site(
        val name: String,
        val url: String,
        val category: String,
        val foundCode: Int,
        val foundText: String?,
        val missingCode: Int?,
        val missingText: String?,
        val prettyUrl: String?,
        val headers: Map<String, String>,
        val stripChars: String?,
        val codeRange: Boolean = false,
        val noRedirects: Boolean = false,
    ) {
        val adult: Boolean get() = category == "xx NSFW xx"
    }

    private var cached: List<Site>? = null

    /** Loads the bundled site list (once). Call from a background thread. */
    @Synchronized fun load(context: Context): List<Site> {
        cached?.let { return it }
        val text = context.assets.open("wmn-sites.json").bufferedReader().use { it.readText() }
        val array = JSONObject(text).getJSONArray("sites")
        val sites = (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            val headers = mutableMapOf<String, String>()
            o.optJSONObject("h")?.let { h -> h.keys().forEach { k -> headers[k] = h.getString(k) } }
            Site(
                name = o.getString("n"),
                url = o.getString("u"),
                category = o.optString("c"),
                foundCode = o.getInt("ec"),
                foundText = o.optString("es").ifEmpty { null },
                missingCode = if (o.has("mc")) o.getInt("mc") else null,
                missingText = o.optString("ms").ifEmpty { null },
                prettyUrl = o.optString("p").ifEmpty { null },
                headers = headers,
                stripChars = o.optString("sb").ifEmpty { null },
                codeRange = o.optBoolean("cr", false),
                noRedirects = o.optBoolean("nr", false),
            )
        }
        cached = sites
        return sites
    }

    /**
     * Returns the cleaned username, or null if it has characters that could change the address.
     * An email address is accepted too: the part before the @ is used as the username.
     */
    fun clean(raw: String): String? {
        val trimmed = raw.trim().removePrefix("@")
        val name = if (trimmed.contains('@')) trimmed.substringBefore('@') else trimmed
        return if (Regex("[A-Za-z0-9._-]{2,40}").matches(name)) name else null
    }

    /** If [raw] is an email address, returns it as-is (lowercased) so it can also be tried as a literal username. */
    fun cleanEmail(raw: String): String? {
        val trimmed = raw.trim().lowercase()
        return if (android.util.Patterns.EMAIL_ADDRESS.matcher(trimmed).matches()) trimmed else null
    }

    private fun accountFor(site: Site, username: String): String {
        val strip = site.stripChars ?: return username
        return username.filterNot { it in strip }
    }

    /** URL-encoded so an "@" or other reserved character (from an email tried as a username) doesn't break the address. */
    private fun encodedAccountFor(site: Site, username: String): String =
        java.net.URLEncoder.encode(accountFor(site, username), "UTF-8").replace("+", "%20")

    /** The address to show and open for a profile: the site's friendly address when it has one. */
    fun profileUrl(site: Site, username: String): String =
        (site.prettyUrl ?: site.url).replace("{account}", encodedAccountFor(site, username))

    private val skippedHeaders = setOf("host", "te", "connection", "content-length", "origin")

    fun check(site: Site, username: String): Outcome {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(site.url.replace("{account}", encodedAccountFor(site, username))).openConnection() as HttpURLConnection).apply {
                connectTimeout = 6000
                readTimeout = 8000
                // A "not found" answer that is itself a redirect must be read as a redirect.
                instanceFollowRedirects = !site.noRedirects && site.missingCode?.let { it in 300..399 } != true
                requestMethod = "GET"
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) FaceSelfCheck/1.0")
                setRequestProperty("Accept", "text/html,application/json;q=0.9,*/*;q=0.8")
                site.headers.forEach { (k, v) -> if (k.lowercase() !in skippedHeaders) try { setRequestProperty(k, v) } catch (_: Exception) {} }
            }
            val code = connection.responseCode
            val needBody = site.foundText != null || site.missingText != null
            val body = if (needBody) readBody(connection, code) else ""

            if (site.codeRange) {
                // The only signal is the response code: any 2xx is "found", unless this site's own missingCode
                // (a code that happens to also fall in the 2xx range) says otherwise.
                val overriddenMissing = site.missingCode != null && code == site.missingCode
                return when {
                    overriddenMissing -> Outcome.NOT_FOUND
                    code in 200..299 -> Outcome.FOUND
                    else -> Outcome.NOT_FOUND
                }
            }

            val found = code == site.foundCode && (site.foundText == null || body.contains(site.foundText))
            val missing = (site.missingCode != null && code == site.missingCode) ||
                (site.missingText != null && body.contains(site.missingText))
            val missingByText = site.missingText != null && body.contains(site.missingText)
            when {
                missingByText && site.foundText == null -> Outcome.NOT_FOUND
                found -> Outcome.FOUND
                missing -> Outcome.NOT_FOUND
                else -> Outcome.UNSURE // blocked, rate-limited, or an answer the rules don't cover
            }
        } catch (_: Exception) {
            Outcome.UNSURE
        } finally {
            connection?.disconnect()
        }
    }

    /** Reads at most 300 KB of the page; the text markers sit near the top. */
    private fun readBody(c: HttpURLConnection, code: Int): String = try {
        val stream = if (code >= 400) c.errorStream else c.inputStream
        stream?.use {
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            while (out.size() < 300 * 1024) {
                val n = it.read(buffer)
                if (n < 0) break
                out.write(buffer, 0, n)
            }
            String(out.toByteArray(), Charsets.UTF_8)
        }.orEmpty()
    } catch (_: Exception) { "" }
}
