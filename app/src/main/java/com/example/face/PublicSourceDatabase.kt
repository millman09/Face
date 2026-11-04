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

data class WebsiteSource(
    val id: Long,
    val name: String,
    val baseUrl: String,
    val enabled: Boolean
)

data class DiscoveredImage(
    val sourceId: Long,
    val pageUrl: String,
    val imageUrl: String
)

/**
 * Local registry and candidate store. The user controls which public domains
 * are included. Only public sitemap/feed endpoints are read; this does not
 * bypass logins, robots rules, CAPTCHAs, or other access controls.
 */
class PublicSourceDatabase(context: Context) :
    SQLiteOpenHelper(context, "public_sources.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE sources (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                base_url TEXT NOT NULL UNIQUE,
                enabled INTEGER NOT NULL DEFAULT 1,
                last_scan INTEGER NOT NULL DEFAULT 0,
                last_status TEXT NOT NULL DEFAULT 'Not scanned'
            )""".trimIndent()
        )
        db.execSQL(
            """CREATE TABLE candidates (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                source_id INTEGER NOT NULL,
                page_url TEXT NOT NULL,
                image_url TEXT NOT NULL UNIQUE,
                discovered_at INTEGER NOT NULL,
                scan_status TEXT NOT NULL DEFAULT 'Discovered',
                FOREIGN KEY(source_id) REFERENCES sources(id)
            )""".trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    @Synchronized
    fun addSource(name: String, rawUrl: String): Boolean {
        val normalized = normalizeBaseUrl(rawUrl) ?: return false
        val values = ContentValues().apply {
            put("name", name.trim().ifBlank { URL(normalized).host })
            put("base_url", normalized)
            put("enabled", 1)
        }
        return writableDatabase.insertWithOnConflict(
            "sources", null, values, SQLiteDatabase.CONFLICT_IGNORE
        ) != -1L
    }

    @Synchronized
    fun sources(): List<WebsiteSource> {
        val result = mutableListOf<WebsiteSource>()
        readableDatabase.rawQuery(
            "SELECT id,name,base_url,enabled FROM sources ORDER BY name COLLATE NOCASE", null
        ).use { c ->
            while (c.moveToNext()) {
                result += WebsiteSource(c.getLong(0), c.getString(1), c.getString(2), c.getInt(3) == 1)
            }
        }
        return result
    }

    @Synchronized
    fun setEnabled(id: Long, enabled: Boolean) {
        writableDatabase.execSQL("UPDATE sources SET enabled=? WHERE id=?", arrayOf(if (enabled) 1 else 0, id))
    }

    @Synchronized
    fun saveCandidates(items: List<DiscoveredImage>): Int {
        var inserted = 0
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (item in items) {
                val values = ContentValues().apply {
                    put("source_id", item.sourceId)
                    put("page_url", item.pageUrl)
                    put("image_url", item.imageUrl)
                    put("discovered_at", System.currentTimeMillis())
                }
                if (db.insertWithOnConflict("candidates", null, values, SQLiteDatabase.CONFLICT_IGNORE) != -1L) {
                    inserted++
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return inserted
    }

    @Synchronized
    fun updateSourceStatus(id: Long, status: String) {
        val values = ContentValues().apply {
            put("last_scan", System.currentTimeMillis())
            put("last_status", status.take(300))
        }
        writableDatabase.update("sources", values, "id=?", arrayOf(id.toString()))
    }

    @Synchronized
    fun candidateCount(): Int {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM candidates", null).use {
            return if (it.moveToFirst()) it.getInt(0) else 0
        }
    }

    companion object {
        fun normalizeBaseUrl(raw: String): String? {
            val candidate = raw.trim().let { if (it.startsWith("https://", true) || it.startsWith("http://", true)) it else "https://$it" }
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
 * Conservative public-index scanner. It reads robots.txt sitemap declarations,
 * sitemap XML (including image sitemap tags), sitemap indexes, and RSS/Atom
 * feeds. It never crawls arbitrary page links or tries to defeat site controls.
 */
object PublicSourceScanner {
    private const val MAX_BYTES = 2 * 1024 * 1024
    private const val MAX_SITEMAPS = 12
    private const val MAX_IMAGES_PER_SOURCE = 500

    fun scan(source: WebsiteSource): List<DiscoveredImage> {
        val base = source.baseUrl
        val sitemapUrls = linkedSetOf<String>()
        val robots = readText("$base/robots.txt")
        if (robots != null) {
            robots.lineSequence().map { it.trim() }
                .filter { it.startsWith("Sitemap:", true) }
                .map { it.substringAfter(':').trim() }
                .filter { it.startsWith("https://") || it.startsWith("http://") }
                .forEach { sitemapUrls += it }
        }
        sitemapUrls += "$base/sitemap.xml"
        sitemapUrls += "$base/sitemap_index.xml"
        sitemapUrls += "$base/feed"
        sitemapUrls += "$base/feed.xml"
        sitemapUrls += "$base/rss.xml"

        val found = linkedMapOf<String, DiscoveredImage>()
        val pending = sitemapUrls.toMutableList()
        val visited = mutableSetOf<String>()
        while (pending.isNotEmpty() && visited.size < MAX_SITEMAPS && found.size < MAX_IMAGES_PER_SOURCE) {
            val endpoint = pending.removeAt(0)
            if (!visited.add(endpoint)) continue
            val xml = readText(endpoint) ?: continue
            parseXml(endpoint, xml, source.id, pending, found)
        }
        return found.values.take(MAX_IMAGES_PER_SOURCE)
    }

    private fun parseXml(
        endpoint: String,
        xml: String,
        sourceId: Long,
        pending: MutableList<String>,
        found: MutableMap<String, DiscoveredImage>
    ) {
        try {
            val parser = Xml.newPullParser()
            parser.setInput(xml.reader())
            var event = parser.eventType
            var page = endpoint
            var inItem = false
            var enclosureUrl: String? = null
            while (event != XmlPullParser.END_DOCUMENT && found.size < MAX_IMAGES_PER_SOURCE) {
                when (event) {
                    XmlPullParser.START_TAG -> {
                        val tag = parser.name.substringAfter(':').lowercase(Locale.US)
                        if (tag == "url" || tag == "item" || tag == "entry") {
                            inItem = true
                            page = endpoint
                            enclosureUrl = null
                        }
                        if (tag == "loc") {
                            val value = parser.nextText().trim()
                            if (value.startsWith("https://") || value.startsWith("http://")) {
                                if (endpoint.contains("sitemap", true) && !isImageUrl(value)) {
                                    if (pending.size < MAX_SITEMAPS) pending += value
                                    page = value
                                } else if (isImageUrl(value)) {
                                    found.putIfAbsent(value, DiscoveredImage(sourceId, page, value))
                                } else if (inItem) {
                                    page = value
                                }
                            }
                        } else if (tag == "enclosure" || tag == "content") {
                            val value = parser.getAttributeValue(null, "url")
                                ?: parser.getAttributeValue("http://www.w3.org/1999/02/22-rdf-syntax-ns#", "about")
                            if (value != null && isImageUrl(value)) enclosureUrl = value
                        } else if (tag == "image") {
                            val value = parser.getAttributeValue(null, "href")
                            if (value != null && isImageUrl(value)) {
                                found.putIfAbsent(value, DiscoveredImage(sourceId, page, value))
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        val tag = parser.name.substringAfter(':').lowercase(Locale.US)
                        if ((tag == "item" || tag == "entry") && enclosureUrl != null) {
                            val image = enclosureUrl!!
                            found.putIfAbsent(image, DiscoveredImage(sourceId, page, image))
                            inItem = false
                        }
                        if (tag == "url") inItem = false
                    }
                }
                event = parser.next()
            }
        } catch (_: Exception) {
            // Invalid or unsupported feeds are skipped; other source endpoints continue.
        }
    }

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
            connection.setRequestProperty("User-Agent", "FacePersonalScanner/1.0 (public sitemap and feed reader)")
            connection.setRequestProperty("Accept", "application/xml,text/xml,application/rss+xml,application/atom+xml,text/plain")
            if (connection is HttpURLConnection) {
                connection.instanceFollowRedirects = false
                val code = connection.responseCode
                if (code !in 200..299) return null
            }
            val length = connection.contentLengthLong
            if (length > MAX_BYTES) return null
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
        } catch (_: Exception) {
            null
        } finally {
            (connection as? HttpURLConnection)?.disconnect()
        }
    }
}
