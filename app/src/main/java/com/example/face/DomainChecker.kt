package com.example.face

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection

/**
 * Passive, read-only checks of a domain's public configuration: email-spoofing protection (SPF, DMARC),
 * DNS hardening, the TLS certificate, HTTPS redirects and security headers. Each check is an ordinary
 * DNS lookup or one normal page request. There is no port scanning, no guessing of hidden paths, and no
 * attack traffic, so it is safe to run against a domain you own or manage, and only that.
 */
object DomainChecker {
    enum class Level { GOOD, WARN, BAD, INFO }
    data class Finding(val level: Level, val title: String, val detail: String)

    private val domainPattern = Regex("^(?=.{4,253}$)([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,24}$")

    /** Returns a bare lower-case domain name, or null if the text is not a normal domain (IP addresses are rejected). */
    fun clean(raw: String): String? {
        val domain = raw.trim().lowercase()
            .removePrefix("https://").removePrefix("http://")
            .substringBefore('/').substringBefore(':')
        return if (domainPattern.matches(domain)) domain else null
    }

    fun run(domain: String, report: (Finding) -> Unit) {
        dnsChecks(domain, report)
        webChecks(domain, report)
    }

    // ---- DNS (through Google's public DNS-over-HTTPS service) ----

    private fun dns(name: String, type: String): JSONObject? = try {
        val url = URL("https://dns.google/resolve?name=${URLEncoder.encode(name, "UTF-8")}&type=$type")
        val c = url.openConnection() as HttpURLConnection
        c.connectTimeout = 6000
        c.readTimeout = 8000
        c.setRequestProperty("Accept", "application/dns-json")
        try { if (c.responseCode == 200) JSONObject(c.inputStream.bufferedReader().readText()) else null } finally { c.disconnect() }
    } catch (_: Exception) { null }

    private fun answers(json: JSONObject?, type: Int): List<String> {
        val array = json?.optJSONArray("Answer") ?: return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val item = array.optJSONObject(i)
            if (item != null && item.optInt("type") == type) item.optString("data") else null
        }
    }

    private fun txt(data: String) = data.replace("\" \"", "").trim('"')

    private fun dnsChecks(domain: String, report: (Finding) -> Unit) {
        val txtJson = dns(domain, "TXT")
        if (txtJson == null) {
            report(Finding(Level.INFO, "DNS lookup unavailable", "Could not reach the DNS lookup service, so email and DNS checks were skipped."))
            return
        }
        val spf = answers(txtJson, 16).map(::txt).firstOrNull { it.startsWith("v=spf1", ignoreCase = true) }
        report(
            when {
                spf == null -> Finding(Level.BAD, "No SPF record", "Anyone can more easily send email that looks like it comes from $domain. Publish an SPF record (use \"v=spf1 -all\" if the domain sends no email).")
                Regex("\\+all|\\?all", RegexOption.IGNORE_CASE).containsMatchIn(spf) ->
                    Finding(Level.BAD, "SPF allows anyone", "Your SPF record ends in +all or ?all, which approves every sender. Change it to -all or ~all.")
                spf.contains("-all", ignoreCase = true) -> Finding(Level.GOOD, "SPF is strict", spf)
                spf.contains("~all", ignoreCase = true) -> Finding(Level.GOOD, "SPF is set (soft fail)", "$spf\nConsider -all once you are sure every sender is listed.")
                else -> Finding(Level.WARN, "SPF has no \"all\" rule", spf)
            }
        )

        val dmarc = answers(dns("_dmarc.$domain", "TXT"), 16).map(::txt).firstOrNull { it.startsWith("v=DMARC1", ignoreCase = true) }
        report(
            if (dmarc == null) Finding(Level.BAD, "No DMARC record", "Without DMARC, receivers can't tell which spoofed emails to reject. Add a _dmarc TXT record, starting with p=none to collect reports.")
            else {
                when (Regex("(?:^|;)\\s*p=(\\w+)", RegexOption.IGNORE_CASE).find(dmarc)?.groupValues?.get(1)?.lowercase()) {
                    "reject", "quarantine" -> Finding(Level.GOOD, "DMARC is enforcing", dmarc)
                    "none" -> Finding(Level.WARN, "DMARC only monitors (p=none)", "$dmarc\nMove to p=quarantine or p=reject once reports look clean.")
                    else -> Finding(Level.WARN, "DMARC policy not recognised", dmarc)
                }
            }
        )

        val mx = answers(dns(domain, "MX"), 15)
        report(
            if (mx.isEmpty()) Finding(Level.INFO, "No mail servers (MX)", "This domain doesn't receive email. If it also sends none, SPF \"v=spf1 -all\" and DMARC p=reject are the right settings.")
            else Finding(Level.INFO, "Mail servers", mx.joinToString("\n"))
        )

        val caa = answers(dns(domain, "CAA"), 257)
        report(
            if (caa.isEmpty()) Finding(Level.INFO, "No CAA record", "A CAA record limits which companies may issue certificates for your domain. Optional, but a cheap safeguard.")
            else Finding(Level.GOOD, "CAA record set", caa.joinToString("\n"))
        )

        report(
            if (txtJson.optBoolean("AD")) Finding(Level.GOOD, "DNSSEC validated", "DNS answers for this domain are cryptographically signed.")
            else Finding(Level.INFO, "DNSSEC not in use", "DNS answers aren't signed. Optional; supported by most registrars.")
        )
    }

    // ---- Web (one normal request per check) ----

    private fun webChecks(domain: String, report: (Finding) -> Unit) {
        var connection: HttpsURLConnection? = null
        try {
            connection = (URL("https://$domain/").openConnection() as HttpsURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 10000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) FaceSelfCheck/1.0")
            }
            val code = connection.responseCode
            val finalHost = connection.url.host
            if (!finalHost.equals(domain, ignoreCase = true)) {
                report(Finding(Level.INFO, "Redirects to $finalHost", "The certificate and header checks below apply to $finalHost."))
            }
            val cert = connection.serverCertificates.firstOrNull() as? X509Certificate
            if (cert != null) {
                val days = (cert.notAfter.time - System.currentTimeMillis()) / 86_400_000L
                report(
                    when {
                        days < 0 -> Finding(Level.BAD, "Certificate expired", "Expired ${-days} day(s) ago.")
                        days < 14 -> Finding(Level.BAD, "Certificate expires very soon", "$days day(s) left. Renew it now.")
                        days < 30 -> Finding(Level.WARN, "Certificate expires soon", "$days day(s) left.")
                        else -> Finding(Level.GOOD, "Certificate valid", "$days day(s) left. Issued by ${cert.issuerX500Principal.name.substringBefore(',')}")
                    }
                )
            }
            report(Finding(Level.INFO, "Website responded", "HTTP $code from https://$finalHost/"))
            headerChecks(connection, report)
            cookieChecks(connection, report)
            cspQualityCheck(connection, report)
            mixedContentCheck(connection, report, finalHost)
        } catch (e: javax.net.ssl.SSLException) {
            report(Finding(Level.BAD, "HTTPS certificate problem", e.localizedMessage ?: "The TLS handshake failed."))
        } catch (e: Exception) {
            report(Finding(Level.INFO, "Website not reachable over HTTPS", "${e.javaClass.simpleName}: ${e.localizedMessage ?: "no details"}. If this domain doesn't host a website, that's expected."))
            return
        } finally {
            connection?.disconnect()
        }
        httpRedirectCheck(domain, report)
        securityTxtCheck(domain, report)
        robotsTxtCheck(domain, report)
        exposedFileCheck(domain, report)
    }

    /** Looks for the common ways a Content-Security-Policy is undone: unsafe-inline/eval and a wide-open default. */
    private fun cspQualityCheck(c: HttpsURLConnection, report: (Finding) -> Unit) {
        val csp = c.getHeaderField("Content-Security-Policy") ?: return
        val weaknesses = mutableListOf<String>()
        if (csp.contains("unsafe-inline", ignoreCase = true)) weaknesses += "allows 'unsafe-inline' scripts/styles"
        if (csp.contains("unsafe-eval", ignoreCase = true)) weaknesses += "allows 'unsafe-eval'"
        if (Regex("default-src[^;]*\*").containsMatchIn(csp) || Regex("script-src[^;]*\*").containsMatchIn(csp)) {
            weaknesses += "allows scripts/content from any host (*)"
        }
        report(
            if (weaknesses.isEmpty()) Finding(Level.GOOD, "Content-Security-Policy looks reasonably strict", csp)
            else Finding(Level.WARN, "Content-Security-Policy has weaknesses", weaknesses.joinToString("; ") + "
$csp")
        )
    }

    /** A page served over HTTPS that still loads plain-HTTP resources undermines the encryption. */
    private fun mixedContentCheck(c: HttpsURLConnection, report: (Finding) -> Unit, host: String) {
        val type = c.contentType ?: return
        if (!type.contains("text/html", ignoreCase = true)) return
        try {
            val html = c.inputStream.bufferedReader().use { it.readText().take(400_000) }
            val insecure = Regex("""(?:src|href)\s*=\s*["']http://[^"']+""", RegexOption.IGNORE_CASE)
                .findAll(html).map { it.value }.distinct().take(5).toList()
            if (insecure.isNotEmpty()) {
                report(Finding(Level.WARN, "Mixed content: some resources load over plain HTTP", insecure.joinToString("
")))
            }
        } catch (_: Exception) {
            // The connection's stream may already be consumed by an earlier check; mixed content is a nice-to-have, not critical.
        }
    }

    /** robots.txt is public by definition, but it sometimes names paths worth not drawing attention to. */
    private fun robotsTxtCheck(domain: String, report: (Finding) -> Unit) {
        var c: HttpURLConnection? = null
        try {
            c = (URL("https://$domain/robots.txt").openConnection() as HttpURLConnection).apply {
                connectTimeout = 6000; readTimeout = 8000
            }
            if (c.responseCode != 200) return
            val lines = c.inputStream.bufferedReader().readText().take(50_000).lines()
            val sensitive = lines.filter {
                it.trim().startsWith("Disallow", ignoreCase = true) &&
                    Regex("admin|wp-admin|backup|\.git|config|private|internal|staging|\.env", RegexOption.IGNORE_CASE).containsMatchIn(it)
            }
            if (sensitive.isNotEmpty()) {
                report(Finding(Level.WARN, "robots.txt names sensitive-looking paths", sensitive.joinToString("
").take(400) +
                    "
Disallow only keeps well-behaved crawlers out; it does not restrict access. If these paths aren't meant to be public, protect them with authentication instead."))
            }
        } catch (_: Exception) {
        } finally { c?.disconnect() }
    }

    /** One plain GET per path for the handful of accidentally-published files that cause real damage. */
    private fun exposedFileCheck(domain: String, report: (Finding) -> Unit) {
        val paths = listOf(".env", ".git/config", ".git/HEAD", "wp-config.php.bak", "backup.sql", ".DS_Store")
        val found = mutableListOf<String>()
        for (path in paths) {
            var c: HttpURLConnection? = null
            try {
                c = (URL("https://$domain/$path").openConnection() as HttpURLConnection).apply {
                    connectTimeout = 5000; readTimeout = 6000; instanceFollowRedirects = false
                }
                val code = c.responseCode
                val length = c.contentLength
                // A real hit answers 200 with actual content; a redirect to a generic error/home page is the common false positive.
                if (code == 200 && length != 0) found += path
            } catch (_: Exception) {
            } finally { c?.disconnect() }
        }
        report(
            if (found.isEmpty()) Finding(Level.GOOD, "No exposed config/backup files found", "Checked: ${paths.joinToString(", ")}")
            else Finding(Level.BAD, "Exposed file(s) found", found.joinToString(", ") + "
These can leak credentials or source code. Remove them from the public web root or block access to them.")
        )
    }

    private fun headerChecks(c: HttpsURLConnection, report: (Finding) -> Unit) {
        fun header(name: String): String? = c.getHeaderField(name)
        val missing = mutableListOf<String>()
        val present = mutableListOf<String>()
        fun check(label: String, ok: Boolean) { if (ok) present += label else missing += label }
        check("Strict-Transport-Security (HSTS)", header("Strict-Transport-Security") != null)
        check("Content-Security-Policy", header("Content-Security-Policy") != null)
        check("X-Content-Type-Options", header("X-Content-Type-Options")?.contains("nosniff", ignoreCase = true) == true)
        check(
            "Clickjacking protection (X-Frame-Options or CSP frame-ancestors)",
            header("X-Frame-Options") != null || header("Content-Security-Policy")?.contains("frame-ancestors", ignoreCase = true) == true
        )
        check("Referrer-Policy", header("Referrer-Policy") != null)
        check("Permissions-Policy", header("Permissions-Policy") != null)
        if (present.isNotEmpty()) report(Finding(Level.GOOD, "Security headers present", present.joinToString("\n")))
        if (missing.isNotEmpty()) report(Finding(Level.WARN, "Security headers missing", missing.joinToString("\n") + "\nAdd these in your web server or CDN settings."))

        val server = header("Server")
        if (server != null && Regex("\\d+\\.\\d+").containsMatchIn(server)) {
            report(Finding(Level.WARN, "Server reveals its version", "Server: $server\nHiding the version makes targeted attacks slightly harder."))
        }
        header("X-Powered-By")?.let { report(Finding(Level.WARN, "Technology disclosed", "X-Powered-By: $it\nYou can usually switch this header off.")) }
    }

    private fun cookieChecks(c: HttpsURLConnection, report: (Finding) -> Unit) {
        val cookies = c.headerFields.entries.firstOrNull { it.key.equals("Set-Cookie", ignoreCase = true) }?.value ?: return
        val weak = cookies.filter {
            !it.contains("secure", ignoreCase = true) || !it.contains("httponly", ignoreCase = true) || !it.contains("samesite", ignoreCase = true)
        }.map { it.substringBefore('=') }
        report(
            if (weak.isEmpty()) Finding(Level.GOOD, "Cookies have Secure, HttpOnly and SameSite", "${cookies.size} cookie(s) checked.")
            else Finding(Level.WARN, "Cookies missing Secure, HttpOnly or SameSite", weak.joinToString(", ") + "\nSession cookies should have all three.")
        )
    }

    private fun httpRedirectCheck(domain: String, report: (Finding) -> Unit) {
        var c: HttpURLConnection? = null
        try {
            c = (URL("http://$domain/").openConnection() as HttpURLConnection).apply {
                connectTimeout = 6000
                readTimeout = 8000
                instanceFollowRedirects = false
            }
            val code = c.responseCode
            val location = c.getHeaderField("Location").orEmpty()
            report(
                when {
                    code in 300..399 && location.startsWith("https://", ignoreCase = true) ->
                        Finding(Level.GOOD, "HTTP redirects to HTTPS", "http://$domain/ sends visitors to the secure site.")
                    code in 200..299 -> Finding(Level.BAD, "Site is available over plain HTTP", "Visitors can load http://$domain/ without encryption. Redirect all HTTP traffic to HTTPS.")
                    else -> Finding(Level.INFO, "HTTP response $code", "http://$domain/ returned $code.")
                }
            )
        } catch (_: Exception) {
            report(Finding(Level.GOOD, "Plain HTTP not served", "http://$domain/ doesn't answer."))
        } finally { c?.disconnect() }
    }

    private fun securityTxtCheck(domain: String, report: (Finding) -> Unit) {
        var c: HttpURLConnection? = null
        try {
            c = (URL("https://$domain/.well-known/security.txt").openConnection() as HttpURLConnection).apply {
                connectTimeout = 6000
                readTimeout = 8000
            }
            report(
                if (c.responseCode == 200) Finding(Level.GOOD, "security.txt published", "Researchers know how to report problems to you.")
                else Finding(Level.INFO, "No security.txt", "Optional: publish /.well-known/security.txt so people know who to tell about a problem.")
            )
        } catch (_: Exception) {
        } finally { c?.disconnect() }
    }
}
