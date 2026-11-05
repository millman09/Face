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
data class StoredCandidate(val id: Long, val sourceId: Long, val sourceBaseUrl: String, val pageUrl: String, val imageUrl: String)
data class MatchResult(val id: Long, val sourceBaseUrl: String, val pageUrl: String, val imageUrl: String, val similarity: Float, val checkedAt: Long)

/** Local source registry and deduplicated candidate-link store. */
class PublicSourceDatabase(context: Context) : SQLiteOpenHelper(context, "public_sources.db", null, 6) {
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
            similarity REAL, checked_at INTEGER NOT NULL DEFAULT 0,
            FOREIGN KEY(source_id) REFERENCES sources(id)
        )""".trimIndent())
        seedSources(db)
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 4) seedSources(db)
        if (oldVersion < 5) {
            db.execSQL("ALTER TABLE candidates ADD COLUMN similarity REAL")
            db.execSQL("ALTER TABLE candidates ADD COLUMN checked_at INTEGER NOT NULL DEFAULT 0")
        }
        if (oldVersion < 6) {
            // Remove male-focused and gay-specific entries from existing installations.
            db.delete("sources", "name IN (?, ?, ?, ?, ?, ?)", arrayOf(
                "FabGuys", "LPSG Forum", "JustUsBoys Forum", "GayHeaven Forum",
                "GayXNow", "AnyShemale"
            ))
        }
    }

    private fun seedSources(db: SQLiteDatabase) {
        // Starter catalogue of public adult-content platforms and forums.
        // Sitemap/feed availability is checked at scan time; no access controls are bypassed.
        val starterDomains = listOf(
            // Major public video and clip platforms
            "Pornhub" to "https://www.pornhub.com",
            "XVideos" to "https://www.xvideos.com",
            "XNXX" to "https://www.xnxx.com",
            "xHamster" to "https://xhamster.com",
            "YouPorn" to "https://www.youporn.com",
            "RedTube" to "https://www.redtube.com",
            "Tube8" to "https://www.tube8.com",
            "SpankBang" to "https://spankbang.com",
            "Beeg" to "https://beeg.com",
            "TNAFlix" to "https://www.tnaflix.com",
            "Nuvid" to "https://www.nuvid.com",
            "DrTuber" to "https://www.drtuber.com",
            "Porn.com" to "https://www.porn.com",
            "PornMD" to "https://www.pornmd.com",
            "KeezMovies" to "https://www.keezmovies.com",
            "EmpFlix" to "https://www.empflix.com",
            "ThePornDude" to "https://theporndude.com",
            "RedGIFs" to "https://www.redgifs.com",
            "GIFs.com Adult" to "https://gifs.com",
            "Erome" to "https://www.erome.com",
            "Motherless" to "https://motherless.com",
            "ImageFap" to "https://www.imagefap.com",
            "PornPics" to "https://www.pornpics.com",
            "PornPic" to "https://www.pornpic.com",
            "XXX Gallery" to "https://xxx.gallery",
            "Nudy.pics" to "https://nudy.pics",
            "Siss.pics" to "https://siss.pics",
            "Fapello" to "https://fapello.com",
            "Baraag" to "https://baraag.net",
            "Paheal" to "https://paheal.net",
            "e621" to "https://e621.net",
            "Gelbooru" to "https://gelbooru.com",
            "Rule34" to "https://rule34.xxx",
            "Danbooru" to "https://danbooru.donmai.us",
            "Safebooru" to "https://safebooru.org",
            "ExHentai" to "https://exhentai.org",
            "E-Hentai" to "https://e-hentai.org",
            "Hitomi" to "https://hitomi.la",
            "Fakku" to "https://www.fakku.net",
            "HentaiFoundry" to "https://www.hentai-foundry.com",
            "8muses" to "https://www.8muses.com",
            "Luscious" to "https://www.luscious.net",
            "HentaiRox" to "https://hentairox.com",
            // Creator and community platforms
            "OnlyFans" to "https://onlyfans.com",
            "Fansly" to "https://fansly.com",
            "Fanvue" to "https://www.fanvue.com",
            "ManyVids" to "https://www.manyvids.com",
            "Clips4Sale" to "https://www.clips4sale.com",
            "JustForFans" to "https://justfor.fans",
            "LoyalFans" to "https://www.loyalfans.com",
            "AVN Stars" to "https://avn.com",
            "FetLife" to "https://fetlife.com",
            "Fabswingers" to "https://www.fabswingers.com",
            "AdultFriendFinder" to "https://adultfriendfinder.com",
            "Collarspace" to "https://collar.space",
            "KinkD" to "https://kinkd.app",
            // Adult forums and discussion communities
            "GoFuckYourself Forum" to "https://gfy.com",
            "AdultDVDTalk Forum" to "https://www.adultdvdtalk.com",
            "PornBB Forum" to "https://www.pornbb.org",
            "Phun Forum" to "https://www.phun.org",
            "FreeOnes Forum" to "https://www.freeones.com",
            "F95Zone Forum" to "https://f95zone.to",
            "VIP-Files Forum" to "https://vip-files.com",
            "Social Media Girls Forum" to "https://socialmediagirls.com",
            "Empornium Forum" to "https://www.empornium.is",
            "Vintage Erotica Forum" to "https://vintage-erotica-forum.com",
            "EroMe Community" to "https://www.erome.com",
            "Reddit (public communities)" to "https://www.reddit.com",
            "4chan (public boards)" to "https://www.4chan.org",
            "8kun (public boards)" to "https://8kun.top",
            // Stories, blogs, and adult publishing communities
            "StoriesOnline" to "https://storiesonline.net",
            "Literotica" to "https://www.literotica.com",
            "LushStories" to "https://www.lushstories.com",
            "SexStories" to "https://www.sexstories.com",
            "ASexStories" to "https://asexstories.com",
            "LitErotica" to "https://literotica.com",
            "Nifty Archive" to "https://www.nifty.org",
            // Additional public platforms and directories
            "AEBN" to "https://www.aebn.com",
            "Bang" to "https://www.bang.com",
            "JavLibrary" to "https://www.javlibrary.com",
            "JavBus" to "https://www.javbus.com",
            "JavDatabase" to "https://javdatabase.com",
            "Hentai0Day" to "https://hentai0day.com",
            "CoedCherry" to "https://coedcherry.com",
            "Sex.com" to "https://www.sex.com",
            "SavePorn" to "https://saveporn.net",
            "91Porn" to "https://91porn.com",
            "XGX" to "https://xgx.mobi",
            "Ero-Video" to "https://ero-video.net",
            "Toppornsites" to "https://toppornsites.com",
            "SkipTheGames" to "https://skipthegames.com",
            // Additional public adult forums and communities
            "PlanetSuzy Forum" to "https://www.planetsuzy.org",
            "ViperGirls Forum" to "https://vipergirls.to",
            "Forumophilia" to "https://forumophilia.com",
            "Climax Connection Forum" to "https://climaxconnection.com",
            "TooTimid Forum" to "https://forums.tootimid.com",
            "Social Swinging Forum" to "https://socialswinging.com",
            "RedHotPie Forums" to "https://www.redhotpie.com.au",
            "VAmateur Forum" to "https://www.vamateur.com",
            "LoversLab Forum" to "https://www.loverslab.com",
            "LewdCorner Forum" to "https://lewdcorner.com",
            "Fenoxo Forums" to "https://forum.fenoxo.com",
            "Serious Bondage Forum" to "https://www.seriousbondage.com",
            "The Cage Forum" to "https://thecage.co",
            "Extreme Board" to "https://www.extreme-board.com",
            "CuckoldPlace Forum" to "https://www.cuckoldplace.com",
            "GoonVilla Forum" to "https://goonvilla.com",
            "xBunker Forum" to "https://xbunker.com",
            "ErotikForum" to "https://www.erotikforum.at",
            "SexForum NZ" to "https://www.sexforum.co.nz",
            "Fritchy Forum" to "https://www.fritchy.com",
            "ULMF Forum" to "https://www.ulmf.org",
            "Undertow Club" to "https://undertow.club",
            "TitsInTops Forum" to "https://www.titsintops.com",
            "NWorship Forum" to "https://nworship.com",
            "WifeWantsToPlay Forum" to "https://www.wifewantstoplay.com",
            "PeeFans Forum" to "https://www.peefans.com",
            "OneClickChicks Forum" to "https://www.oneclickchicks.com",
            "JAV Forum" to "https://www.javforum.com",
            "SimpCity Forum" to "https://simpcity.su",
            "Social Media Girls Forum (alt)" to "https://forums.socialmediagirls.com",
            // Additional image-first galleries, boards and adult publishing communities
            "PicHunter" to "https://www.pichunter.com",
            "ImageTwist" to "https://imagetwist.com",
            "ImageBam" to "https://www.imagebam.com",
            "ImageVenue" to "https://www.imagevenue.com",
            "JPG Fish" to "https://jpg.fish",
            "Flickr" to "https://www.flickr.com",
            "Tumblr" to "https://www.tumblr.com",
            "Newgrounds" to "https://www.newgrounds.com",
            "Inkbunny" to "https://inkbunny.net",
            "Fur Affinity" to "https://www.furaffinity.net",
            "Hentai2Read" to "https://hentai2read.com",
            "MyHentaiGallery" to "https://myhentaigallery.com",
            "MultPorn" to "https://multporn.net",
            "PornComics" to "https://porncomics.com",
            "XXXComics" to "https://xxxcomics.org",
            "Fenoxo Blog" to "https://www.fenoxo.com",
            "Marriage Heat" to "https://marriageheat.com",
            "Audiochan" to "https://audiochan.com",
            "ChatZoZo" to "https://chatzozo.com",
            "European Girls Adult Film Database" to "https://www.egafd.com",
            "FapHouse" to "https://faphouse.com",
            "Eporner" to "https://www.eporner.com",
            "Chaturbate" to "https://chaturbate.com",
            "Cam4" to "https://www.cam4.com",
            "CamSoda" to "https://www.camsoda.com",
            "Stripchat" to "https://stripchat.com",
            "BongaCams" to "https://bongacams.com",
            "MyFreeCams" to "https://www.myfreecams.com",
            "LiveJasmin" to "https://www.livejasmin.com",
            "Nutaku" to "https://www.nutaku.net",
            "DLsite" to "https://www.dlsite.com",
            "Itch.io" to "https://itch.io",
            "Newgrounds Adult" to "https://www.newgrounds.com"
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

        @Synchronized fun clearCandidatesForNewScan() {
        writableDatabase.delete("candidates", null, null)
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

    /** Candidates not yet compared in a previous run; unavailable URLs may be retried. */
    @Synchronized fun pendingCandidates(limit: Int = 5000): List<StoredCandidate> {
        val result = mutableListOf<StoredCandidate>()
        readableDatabase.rawQuery(
            """SELECT c.id,c.source_id,s.base_url,c.page_url,c.image_url
               FROM candidates c JOIN sources s ON s.id=c.source_id
               WHERE c.checked_at=0 OR c.scan_status='Unavailable'
               ORDER BY c.discovered_at DESC LIMIT ?""".trimIndent(),
            arrayOf(limit.coerceIn(1, 5000).toString())
        ).use { c ->
            while (c.moveToNext()) result += StoredCandidate(
                c.getLong(0), c.getLong(1), c.getString(2), c.getString(3), c.getString(4)
            )
        }
        return result
    }

    @Synchronized fun allCandidates(limit: Int = 100): List<StoredCandidate> {
        val result = mutableListOf<StoredCandidate>()
        readableDatabase.rawQuery(
            """SELECT c.id,c.source_id,s.base_url,c.page_url,c.image_url
               FROM candidates c JOIN sources s ON s.id=c.source_id
               ORDER BY c.discovered_at DESC LIMIT ?""".trimIndent(),
            arrayOf(limit.coerceIn(1, 5000).toString())
        ).use { c ->
            while (c.moveToNext()) result += StoredCandidate(
                c.getLong(0), c.getLong(1), c.getString(2), c.getString(3), c.getString(4)
            )
        }
        return result
    }

    @Synchronized fun updateCandidateStatus(id: Long, status: String) {
        val values = ContentValues().apply { put("scan_status", status.take(100)) }
        writableDatabase.update("candidates", values, "id=?", arrayOf(id.toString()))
    }

    @Synchronized fun updateCandidateResult(id: Long, status: String, similarity: Float?) {
        val values = ContentValues().apply {
            put("scan_status", status.take(100))
            if (similarity == null) putNull("similarity") else put("similarity", similarity)
            put("checked_at", System.currentTimeMillis())
        }
        writableDatabase.update("candidates", values, "id=?", arrayOf(id.toString()))
    }

    @Synchronized fun possibleMatches(limit: Int = 500, bestFirst: Boolean = false): List<MatchResult> {
        val result = mutableListOf<MatchResult>()
        readableDatabase.rawQuery(
            """SELECT c.id,s.base_url,c.page_url,c.image_url,c.similarity,c.checked_at
               FROM candidates c JOIN sources s ON s.id=c.source_id
               WHERE c.scan_status='Possible match' AND c.similarity IS NOT NULL
               ORDER BY ${if (bestFirst) "c.similarity DESC, c.checked_at DESC" else "c.checked_at DESC"} LIMIT ?""".trimIndent(),
            arrayOf(limit.coerceIn(1, 2000).toString())
        ).use { c ->
            while (c.moveToNext()) result += MatchResult(
                c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getFloat(4), c.getLong(5)
            )
        }
        return result
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
    // Deep but bounded per-domain crawl limits to protect phone performance.
    private const val MAX_BYTES = 4 * 1024 * 1024
    private const val MAX_SITEMAPS = 40
    private const val MAX_IMAGES = 2500
    private const val MAX_SITEMAP_PAGES = 150
    private const val MAX_LINKED_PAGES = 350
    private const val MAX_PAGE_BYTES = 1024 * 1024

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
        sitemapUrls += listOf(
            "$base/sitemap.xml",
            "$base/sitemap_index.xml",
            "$base/sitemap-index.xml",
            "$base/wp-sitemap.xml",
            "$base/image-sitemap.xml",
            "$base/images-sitemap.xml",
            "$base/sitemap-images.xml",
            "$base/sitemap1.xml",
            "$base/sitemap-1.xml",
            "$base/feed",
            "$base/feed.xml",
            "$base/rss.xml",
            "$base/atom.xml"
        )

        val found = linkedMapOf<String, DiscoveredImage>()
        val pending = sitemapUrls.toMutableList()
        val visited = mutableSetOf<String>()
        val publicPages = linkedSetOf<String>()
        while (pending.isNotEmpty() && visited.size < MAX_SITEMAPS && found.size < MAX_IMAGES) {
            val endpoint = pending.removeAt(0)
            if (!visited.add(endpoint)) continue
            if (!sameHost(base, endpoint) || isDisallowed(endpoint, rules)) continue
            val xml = readText(endpoint) ?: continue
            parseXml(endpoint, xml, source.id, pending, found, publicPages, base, rules)
        }

        // Sitemap entries are often public gallery/article pages, not direct images.
        // Inspect a bounded sample, then follow a small number of same-host public
        // links from those pages. This reaches gallery/detail pages omitted from
        // image sitemaps without turning the phone into an unbounded crawler.
        val pageQueue = ArrayDeque<String>()
        publicPages.take(MAX_SITEMAP_PAGES).forEach { pageQueue.addLast(it) }

        // Forum software often exposes its useful public content from index,
        // category, latest-topic and gallery routes rather than sitemaps.
        // Each route is still checked against robots.txt before it is fetched.
        val forumLike = source.name.contains("forum", true) ||
            source.name.contains("community", true) ||
            source.name.contains("board", true)
        if (forumLike) {
            val forumRoutes = listOf(
                "/forums", "/forum", "/community", "/boards", "/latest",
                "/categories", "/threads", "/topics", "/discussions",
                "/forum/index.php", "/viewforum.php", "/gallery", "/media",
                "/albums", "/members"
            )
            for (route in forumRoutes) {
                val address = base.trimEnd('/') + route
                if (!isDisallowed(address, rules) && address !in publicPages) {
                    pageQueue.addLast(address)
                }
            }
        }
        val visitedPages = linkedSetOf<String>()
        var linkedPagesQueued = 0
        while (pageQueue.isNotEmpty() &&
            visitedPages.size < MAX_SITEMAP_PAGES + MAX_LINKED_PAGES &&
            found.size < MAX_IMAGES) {
            val page = pageQueue.removeFirst()
            if (!visitedPages.add(page) || !sameHost(base, page) || isDisallowed(page, rules)) continue
            val html = readPage(page) ?: continue
            val links = extractPageImages(page, html, source.id, found)
            for (link in links) {
                if (linkedPagesQueued >= MAX_LINKED_PAGES) break
                if (link !in visitedPages && pageQueue.none { it == link } &&
                    sameHost(base, link) && !isDisallowed(link, rules) && isLikelyHtmlPage(link)) {
                    pageQueue.addLast(link)
                    linkedPagesQueued++
                }
            }
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
        found: MutableMap<String, DiscoveredImage>, publicPages: MutableSet<String>, base: String, rules: List<String>
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
                            val namespace = parser.namespace ?: ""
                            val value = parser.nextText().trim()
                            if (value.startsWith("https://") || value.startsWith("http://")) {
                                if (namespace.contains("sitemap-image", ignoreCase = true)) {
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
                            "url" -> {
                                inUrl = false
                                if ((pageUrl.startsWith("https://") || pageUrl.startsWith("http://")) &&
                                    sameHost(base, pageUrl) && !isDisallowed(pageUrl, rules) &&
                                    publicPages.size < MAX_SITEMAP_PAGES * 4) publicPages += pageUrl
                            }
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


    private fun extractPageImages(
        pageUrl: String,
        html: String,
        sourceId: Long,
        found: MutableMap<String, DiscoveredImage>
    ): Set<String> {
        val candidates = linkedSetOf<String>()
        val pageLinks = linkedSetOf<String>()
        val metaTag = Regex("(?is)<meta\\b[^>]*>")
        val attribute = Regex("(?is)([a-z_:][-a-z0-9_:.]*)\\s*=\\s*(['\"])(.*?)\\2")
        for (tag in metaTag.findAll(html)) {
            val attrs = attribute.findAll(tag.value).associate {
                it.groupValues[1].lowercase(Locale.US) to it.groupValues[3]
            }
            val key = (attrs["property"] ?: attrs["name"] ?: "").lowercase(Locale.US)
            if (key in setOf("og:image", "og:image:url", "twitter:image", "twitter:image:src")) {
                attrs["content"]?.let { candidates += it }
            }
        }
        val anchorTag = Regex("(?is)<a\\b[^>]*>")
        for (tag in anchorTag.findAll(html)) {
            val attrs = attribute.findAll(tag.value).associate {
                it.groupValues[1].lowercase(Locale.US) to it.groupValues[3]
            }
            attrs["href"]?.let { raw ->
                val value = raw.trim().replace("&amp;", "&")
                if (value.isNotBlank() && !value.startsWith("javascript:", true) &&
                    !value.startsWith("mailto:", true) && !value.startsWith("#")) {
                    try {
                        val resolved = URL(URL(pageUrl), value)
                        if (resolved.protocol in listOf("https", "http") && resolved.userInfo == null) {
                            val normalized = URL(resolved.protocol, resolved.host, resolved.port, resolved.file).toString()
                            if (isImageUrl(normalized)) candidates += normalized
                            else if (normalized != pageUrl) pageLinks += normalized
                        }
                    } catch (_: Exception) { }
                }
            }
            if (pageLinks.size >= MAX_LINKED_PAGES) break
        }
        // Include standard images, responsive <source> entries, video posters,
        // and common lazy-loading attributes used by public gallery pages.
        val mediaTag = Regex("(?is)<(?:img|source|video|link)\\b[^>]*>")
        for (tag in mediaTag.findAll(html)) {
            val attrs = attribute.findAll(tag.value).associate {
                it.groupValues[1].lowercase(Locale.US) to it.groupValues[3]
            }
            listOf(
                "src", "data-src", "data-original", "data-original-src",
                "data-lazy-src", "data-flickity-lazyload", "data-echo",
                "data-image", "poster"
            ).forEach { key -> attrs[key]?.let { candidates += it } }
            listOf("srcset", "data-srcset", "data-lazy-srcset").forEach { key ->
                attrs[key]?.split(',')?.forEach { entry ->
                    entry.trim().substringBefore(' ').takeIf { it.isNotBlank() }?.let { candidates += it }
                }
            }
            if (tag.value.contains("image_src", ignoreCase = true)) {
                attrs["href"]?.let { candidates += it }
            }
            if (candidates.size >= MAX_IMAGES) break
        }
        for (raw in candidates) {
            if (found.size >= MAX_IMAGES) break
            val value = raw.trim().replace("&amp;", "&")
            if (value.isBlank() || value.startsWith("data:", true) ||
                value.startsWith("javascript:", true)) continue
            val resolved = try { URL(URL(pageUrl), value) } catch (_: Exception) { continue }
            if (resolved.protocol !in listOf("https", "http") || resolved.userInfo != null) continue
            val imageUrl = resolved.toString()
            found.putIfAbsent(imageUrl, DiscoveredImage(sourceId, pageUrl, imageUrl))
        }
        return pageLinks.sortedByDescending { isPriorityContentPage(it) }
            .toCollection(linkedSetOf())
    }

    private fun isPriorityContentPage(address: String): Boolean {
        val path = try { URL(address).path.lowercase(Locale.US) } catch (_: Exception) { return false }
        val full = address.lowercase(Locale.US)
        val markers = listOf(
            "gallery", "photo", "image", "album", "media", "post", "profile",
            "model", "thread", "topic", "forum", "discussion", "viewforum",
            "showthread", "page=", "start=", "offset=", "p="
        )
        return markers.any { marker -> full.contains(marker) || path.contains(marker) }
    }

    private fun isLikelyHtmlPage(address: String): Boolean {
        val path = try { URL(address).path.lowercase(Locale.US) } catch (_: Exception) { return false }
        val blocked = listOf(
            ".jpg", ".jpeg", ".png", ".webp", ".gif", ".svg", ".mp4", ".webm",
            ".mov", ".mp3", ".pdf", ".zip", ".rar", ".7z", ".css", ".js",
            ".woff", ".woff2", ".ttf", ".ico", ".xml", ".json"
        )
        return path.isNotBlank() && blocked.none { path.endsWith(it) }
    }

    private fun readPage(address: String): String? {
        var connection: URLConnection? = null
        return try {
            connection = URL(address).openConnection()
            connection.connectTimeout = 6000
            connection.readTimeout = 7000
            connection.setRequestProperty("User-Agent", "FacePersonalScanner/1.0 (public page metadata reader)")
            connection.setRequestProperty("Accept", "text/html,application/xhtml+xml")
            if (connection is HttpURLConnection) {
                connection.instanceFollowRedirects = false
                if (connection.responseCode !in 200..299) return null
            }
            val type = connection.contentType?.lowercase(Locale.US) ?: return null
            if (!type.contains("text/html") && !type.contains("application/xhtml+xml")) return null
            if (connection.contentLengthLong > MAX_PAGE_BYTES) return null
            val bytes = BufferedInputStream(connection.getInputStream()).use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > MAX_PAGE_BYTES) return null
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            bytes.toString(Charsets.UTF_8)
        } catch (_: Exception) { null }
        finally { (connection as? HttpURLConnection)?.disconnect() }
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
