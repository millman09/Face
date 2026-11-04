package com.example.face

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLConnection
import java.util.Locale

data class WebsiteSource(val id: Long, val name: String, val baseUrl: String, val enabled: Boolean)
data class DiscoveredImage(val sourceId: Long, val pageUrl: String, val imageUrl: String)

/** Local source registry and deduplicated candidate-link store. */
class PublicSourceDatabase(context: Context) : SQLiteOpenHelper(context, "public_sources.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE sources (
            id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL,
            base_url TEXT NOT NULL UNIQUE, enabled INTEGER NOT NULL DEFAULT 1,
            last_scan INTEGER NOT NULL DEFAULT 0, last_status TEXT NOT NULL DEFAULT 'Not scanned'
        )""".trimIndent())
        db.execSQL("""CREATE TABLE candidates (
            id INTEGER PRIMARY KEY AUTOINCREMENT, source_id INTEGER NOT NULL,
            page_url TEXT NOT NULL, image_url TEXT NOT NULL UNIQUE,
            discovered_at INTEGER NOT NULL, scan_status TEXT NOT NULL DEFAULT 'Discovered',
            FOREIGN KEY(source_id) REFERENCES sources(id)
        )""".trimIndent())
        // Starter catalogue of public adult-content platforms and forums.
        // Sitemap/feed availability is checked at scan time; no access controls are bypassed.
        val starterDomains = listOf(
            "Pornhub" to "https://www.pornhub.com",
            "XVideos" to "https://www.xvideos.com",
            "XNXX" to "https://www.xnxx.com",
            "xHamster" to "https://xhamster.com",
            "YouPorn" to "https://www.youporn.com",
            "RedGIFs" to "https://www.redgifs.com",
            "Erome" to "https://www.erome.com",
            "Motherless" to "https://motherless.com",
            "ImageFap" to "https://www.imagefap.com",
            "SpankBang" to "https://spankbang.com",
            "Reddit (public communities)" to "https://www.reddit.com",
            "4chan (public boards)" to "https://www.4chan.org",
            "e621 (public image boards)" to "https://e621.net"
        )
        for ((name, address) in starterDomains) {
            val values = ContentValues().apply {
                put("name", name)
                put("base_url", address)
                put("enabled", 1)
                put("last_status", "Not checked yet")
            }
            db.insertWithOnConflict("sources", null, values, SQLiteDatabase.CONFLICT_IGNORE)
        }

    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    @Synchronized fun addSource(name: String, rawUrl: String): Boolean {
        val normalized = normalizeBaseUrl(rawUrl) ?: return false
        val values = ContentValues().apply {
            put("name", name.trim().ifBlank { URL(normalized).host })
            put("base_url", normalized)
            put("enabled", 1)
        }
        return writableDatabase.insertWithOnConflict("sources", null, values, SQLiteDatabase.CONFLICT_IGNORE) != -1L
    }

    @Synchronized fun sources(): List<WebsiteSource> {
        val result = mutableListOf<WebsiteSource>()
        readableDatabase.rawQuery("SELECT id,name,base_url,enabled FROM sources ORDER BY name COLLATE NOCASE", null).use { c ->
            while (c.moveToNext()) result += WebsiteSource(c.getLong(0), c.getString(1), c.getString(2), c.getInt(3) == 1)
        }
        return result
    }

    @Synchronized fun setEnabled(id: Long, enabled: Boolean) {
        writableDatabase.execSQL("UPDATE sources SET enabled=? WHERE id=?", arrayOf(if (enabled) 1 else 0, id))
    }

    @Synchronized fun saveCandidates(items: List<DiscoveredImage>): Int {
        var inserted = 0
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (item in items) {
                val values = ContentValues().apply {
                    put("source_id", item.sourceId); put("page_url", item.pageUrl)
                    put("image_url", item.imageUrl); put("discovered_at", System.currentTimeMillis())
                }
                if (db.insertWithOnConflict("candidates", null, values, SQLiteDatabase.CONFLICT_IGNORE) != -1L) inserted++
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return inserted
    }

    @Synchronized fun updateSourceStatus(id: Long, status: String) {
        val values = ContentValues().apply { put("last_scan", System.currentTimeMillis()); put("last_status", status.take(300)) }
        writableDatabase.update("sources", values, "id=?", arrayOf(id.toString()))
    }

    @Synchronized fun candidateCount(): Int {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM candidates", null).use { return if (it.moveToFirst()) it.getInt(0) else 0 }
    }

    companion object {
        fun normalizeBaseUrl(raw: String): String? {
            val candidate = raw.trim().let {
                if (it.startsWith("https://", true) || it.startsWith("http://", true)) it else "https://$it"
            }
            return try {
                val u = URL(candidate)
                if ((u.protocol != "https" && u.protocol != "http") || u.host.isNullOrBlank() ||
                    u.userInfo != null || u.query != null || u.ref != null) null
                else URL(u.protocol, u.host.lowercase(Locale.US), u.port, "").toString().trimEnd('/')
            } catch (_: Exception) { null }
        }
    }
}

/**
 * Reads only public robots.txt, sitemap and feed endpoints. It does not fetch
 * arbitrary result pages or image binaries. Disallowed sitemap paths are skipped.
 */
object PublicSourceScanner {
    private const val MAX_BYTES = 2 * 1024 * 1024
    private const val MAX_SITEMAPS = 12
    private const val MAX_IMAGES = 500

    fun scan(source: WebsiteSource): List<DiscoveredImage> {
        val base = source.baseUrl
        val robotsText = readText("$base/robots.txt")
        val rules = parseRobots(robotsText.orEmpty())
        val sitemapUrls = linkedSetOf<String>()
        robotsText.orEmpty().lineSequence().map { it.trim() }
            .filter { it.startsWith("Sitemap:", true) }
            .map { it.substringAfter(':').trim() }
            .filter { it.startsWith("https://") || it.startsWith("http://") }
            .forEach { sitemapUrls += it }
        sitemapUrls += "$base/sitemap.xml"
        sitemapUrls += "$base/sitemap_index.xml"
        sitemapUrls += "$base/feed"
        sitemapUrls += "$base/feed.xml"
        sitemapUrls += "$base/rss.xml"

        val found = linkedMapOf<String, DiscoveredImage>()
        val pending = sitemapUrls.toMutableList()
        val visited = mutableSetOf<String>()
        while (pending.isNotEmpty() && visited.size < MAX_SITEMAPS && found.size < MAX_IMAGES) {
            val endpoint = pending.removeAt(0)
            if (!visited.add(endpoint)) continue
            if (!sameHost(base, endpoint) || isDisallowed(endpoint, rules)) continue
            val xml = readText(endpoint) ?: continue
            parseXml(endpoint, xml, source.id, pending, found, base, rules)
        }
        return found.values.take(MAX_IMAGES)
    }

    private fun parseRobots(text: String): List<String> {
        val rules = mutableListOf<String>()
        var appliesToAll = false
        var sawAgent = false
        for (raw in text.lineSequence()) {
            val line = raw.substringBefore('#').trim()
            if (line.isEmpty() || !line.contains(':')) continue
            val key = line.substringBefore(':').trim()
            val value = line.substringAfter(':').trim()
            when {
                key.equals("user-agent", true) -> {
                    if (sawAgent && value == "*") appliesToAll = true
                    else if (!sawAgent) appliesToAll = value == "*"
                    sawAgent = true
                }
                key.equals("disallow", true) && appliesToAll && value.isNotEmpty() -> rules += value
            }
        }
        return rules
    }

    private fun isDisallowed(url: String, rules: List<String>): Boolean {
        val path = try { URL(url).file.substringBefore('?') } catch (_: Exception) { return true }
        return rules.any { path.startsWith(it) }
    }

    private fun parseXml(
        endpoint: String, xml: String, sourceId: Long, pending: MutableList<String>,
        found: MutableMap<String, DiscoveredImage>, base: String, rules: List<String>
    ) {
        try {
            val parser = Xml.newPullParser()
            parser.setInput(xml.reader())
            var event = parser.eventType
            var pageUrl = endpoint
            var inUrl = false
            var inSitemapIndex = false
            var inSitemapEntry = false
            var inFeedEntry = false
            while (event != XmlPullParser.END_DOCUMENT && found.size < MAX_IMAGES) {
                when (event) {
                    XmlPullParser.START_TAG -> {
                        val rawName = parser.name
                        val tag = rawName.substringAfter(':').lowercase(Locale.US)
                        when (tag) {
                            "sitemapindex" -> inSitemapIndex = true
                            "sitemap" -> inSitemapEntry = true
                            "url" -> { inUrl = true; pageUrl = endpoint }
                            "item", "entry" -> inFeedEntry = true
                        }
                        if (tag == "loc") {
                            val value = parser.nextText().trim()
                            if (value.startsWith("https://") || value.startsWith("http://")) {
                                if (rawName.contains(':') && rawName.substringBefore(':').equals("image", true)) {
                                    found.putIfAbsent(value, DiscoveredImage(sourceId, pageUrl, value))
                                } else if (inSitemapIndex && inSitemapEntry && sameHost(base, value) && !isDisallowed(value, rules)) {
                                    if (pending.size < MAX_SITEMAPS) pending += value
                                } else if (inUrl) {
                                    pageUrl = value
                                }
                            }
                        } else if (tag == "enclosure" || (tag == "link" && parser.getAttributeValue(null, "rel") == "enclosure")) {
                            val value = parser.getAttributeValue(null, "url")
                                ?: parser.getAttributeValue(null, "href")
                            if (value != null && isImageUrl(value)) {
                                found.putIfAbsent(value, DiscoveredImage(sourceId, pageUrl, value))
                            }
                        } else if (tag == "content") {
                            val value = parser.getAttributeValue(null, "url")
                            if (value != null && isImageUrl(value)) found.putIfAbsent(value, DiscoveredImage(sourceId, pageUrl, value))
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        when (parser.name.substringAfter(':').lowercase(Locale.US)) {
                            "sitemap" -> inSitemapEntry = false
                            "sitemapindex" -> inSitemapIndex = false
                            "url" -> inUrl = false
                            "item", "entry" -> inFeedEntry = false
                        }
                    }
                }
                event = parser.next()
            }
        } catch (_: Exception) {
            // Malformed, unsupported, or non-feed endpoints are skipped.
        }
    }

    private fun sameHost(base: String, candidate: String): Boolean = try {
        URL(base).host.equals(URL(candidate).host, true)
    } catch (_: Exception) { false }

    private fun isImageUrl(value: String): Boolean {
        val path = try { URL(value).path.lowercase(Locale.US) } catch (_: Exception) { return false }
        return listOf(".jpg", ".jpeg", ".png", ".webp", ".gif").any { path.endsWith(it) }
    }

    private fun readText(address: String): String? {
        var connection: URLConnection? = null
        return try {
            connection = URL(address).openConnection()
            connection.connectTimeout = 7000
            connection.readTimeout = 9000
            connection.setRequestProperty("User-Agent", "FacePersonalScanner/1.0 (public sitemap/feed reader)")
            connection.setRequestProperty("Accept", "application/xml,text/xml,application/rss+xml,application/atom+xml,text/plain")
            if (connection is HttpURLConnection) {
                connection.instanceFollowRedirects = false
                if (connection.responseCode !in 200..299) return null
            }
            if (connection.contentLengthLong > MAX_BYTES) return null
            val bytes = BufferedInputStream(connection.getInputStream()).use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > MAX_BYTES) return null
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            bytes.toString(Charsets.UTF_8)
        } catch (_: Exception) { null }
        finally { (connection as? HttpURLConnection)?.disconnect() }
    }
}
