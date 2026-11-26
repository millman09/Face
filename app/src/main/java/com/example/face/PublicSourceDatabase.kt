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
import java.util.Locale

data class WebsiteSource(
    val id: Long, val name: String, val baseUrl: String, val enabled: Boolean,
    val lastScan: Long = 0L, val lastStatus: String = "Not scanned",
    /** Scans in a row that found no images or failed; see [isResting]. */
    val emptyRuns: Int = 0,
    val lastAttempt: Long = 0L,
) {
    /**
     * A site that came back empty or failed twice in a row (it blocks crawlers, needs a login, or has
     * nothing public) sits out searches for a week, then gets tried again. Those sites were costing
     * minutes each per search for nothing.
     */
    fun isResting(now: Long = System.currentTimeMillis()): Boolean =
        emptyRuns >= 2 && now - lastAttempt < 7L * 24L * 60L * 60L * 1000L
}
data class CandidateUpdate(val id: Long, val status: String, val similarity: Float?, val faceBox: String? = null)
data class DiscoveredImage(val sourceId: Long, val pageUrl: String, val imageUrl: String, val postText: String = "")
data class StoredCandidate(val id: Long, val sourceId: Long, val sourceBaseUrl: String, val pageUrl: String, val imageUrl: String, val postText: String = "")
data class SearchInfo(val id: Long, val savedAt: Long, val matchCount: Int)
/** A source's crawl progress, carried between scans so each run can continue deeper into the site. */
data class CrawlState(
    val sitemapVisited: Set<String> = emptySet(),
    val sitemapFrontier: List<String> = emptyList(),
    val pageVisited: Set<String> = emptySet(),
    val pageFrontier: List<String> = emptyList(),
) {
    companion object {
        const val MAX_PERSISTED_SITEMAP_VISITED = 2000
        const val MAX_PERSISTED_PAGE_VISITED = 8000
        const val MAX_PERSISTED_FRONTIER = 3000
    }
}
data class MatchResult(val id: Long, val sourceBaseUrl: String, val pageUrl: String, val imageUrl: String, val similarity: Float, val checkedAt: Long, val faceBox: String? = null, val confirmed: Boolean = false, val postText: String = "")

/** Local source registry and deduplicated candidate-link store. */
/**
 * Sources removed in database version 15: 8kun, plus drawn/anime boards that were briefly added on
 * another branch (they can't contain a photo of a real face, so scanning them only slows searches).
 */
private val REMOVED_IN_15_HOSTS = listOf(
    "8kun.top", "tbib.org", "hypnohub.net", "konachan.com", "yande.re", "rule34.us",
    "rule34.paheal.net", "furry.booru.org", "booru.org", "pixiv.net", "deviantart.com",
)

private val REMOVED_IN_17_HOSTS = listOf(
    "realbooru.com", "xbooru.com", "e621.net", "gelbooru.com", "rule34.xxx",
    "danbooru.donmai.us", "safebooru.org", "paheal.net", "exhentai.org",
    "e-hentai.org", "hitomi.la", "tbib.org", "hypnohub.net", "konachan.com",
    "yande.re", "rule34.us", "rule34.paheal.net", "furry.booru.org", "booru.org",
    "pixiv.net", "deviantart.com", "inkbunny.net", "furaffinity.net",
    "newgrounds.com", "baraag.net",
)

/**
 * Sources removed in database version 20: professional studios, celebrity and porn-star sites, industry
 * directories, text-only story sites, drawn/3D game communities and professional photography portfolios.
 * None of them carry amateur photos of private people, so they only slowed searches down.
 */
private val REMOVED_IN_20_HOSTS = listOf(
    "1pondo.tv",
    "35photo.pro",
    "500px.com",
    "adultdvdtalk.com",
    "aebn.com",
    "asexstories.com",
    "avn.com",
    "babepedia.com",
    "bang.com",
    "bangbros.com",
    "blacked.com",
    "brazzers.com",
    "cammodeldirectory.com",
    "caribbeancom.com",
    "coedcherry.com",
    "digitalplayground.com",
    "egafd.com",
    "empornium.is",
    "f95zone.to",
    "faphouse.com",
    "forum.fenoxo.com",
    "freeones.com",
    "ftvgirls.com",
    "gfy.com",
    "hegre.com",
    "heyzo.com",
    "hqporner.com",
    "javbus.com",
    "javdatabase.com",
    "javforum.com",
    "javlibrary.com",
    "kemono.su",
    "kink.com",
    "lewdcorner.com",
    "literotica.com",
    "loverslab.com",
    "lushstories.com",
    "metart.com",
    "mofos.com",
    "mrskin.com",
    "naughtyamerica.com",
    "nifty.org",
    "nudography.com",
    "pbase.com",
    "penthouse.com",
    "photo.net",
    "pixieset.com",
    "playboy.com",
    "r18.com",
    "realitykings.com",
    "scoreland.com",
    "sexstories.com",
    "smugmug.com",
    "storiesonline.net",
    "teamskeet.com",
    "theporndude.com",
    "tokyo-hot.com",
    "toppornsites.com",
    "tushy.com",
    "ulmf.org",
    "viewbug.com",
    "vintage-erotica-forum.com",
    "vip-files.com",
    "vixen.com",
    "vjav.com",
    "youpic.com",
    "zenfolio.com",
)

class PublicSourceDatabase(context: Context) : SQLiteOpenHelper(context, "public_sources.db", null, 21) {
    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        if (!db.isReadOnly) db.enableWriteAheadLogging()
    }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE sources (
            id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL,
            base_url TEXT NOT NULL UNIQUE, enabled INTEGER NOT NULL DEFAULT 1,
            last_scan INTEGER NOT NULL DEFAULT 0, last_status TEXT NOT NULL DEFAULT 'Not scanned',
            empty_runs INTEGER NOT NULL DEFAULT 0, last_attempt INTEGER NOT NULL DEFAULT 0
        )""".trimIndent())
        db.execSQL("""CREATE TABLE candidates (
            id INTEGER PRIMARY KEY AUTOINCREMENT, source_id INTEGER NOT NULL,
            page_url TEXT NOT NULL, image_url TEXT NOT NULL UNIQUE,
            discovered_at INTEGER NOT NULL, post_text TEXT NOT NULL DEFAULT '', scan_status TEXT NOT NULL DEFAULT 'Discovered',
            similarity REAL, checked_at INTEGER NOT NULL DEFAULT 0, face_box TEXT,
            FOREIGN KEY(source_id) REFERENCES sources(id)
        )""".trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_candidates_pending ON candidates(checked_at, discovered_at)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_candidates_matches ON candidates(scan_status, similarity, checked_at)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_sources_enabled_scan ON sources(enabled, last_scan)")
        createArchiveTables(db)
        createCrawlStateTable(db)
        seedSources(db)
    }

    private fun createCrawlStateTable(db: SQLiteDatabase) {
        // Lets a source's crawl pick up deeper into the site on its next scan instead of re-treading
        // the same bounded slice near the sitemap root every time. Lists are newline-joined URLs.
        db.execSQL("""CREATE TABLE IF NOT EXISTS crawl_state (
            source_id INTEGER PRIMARY KEY,
            sitemap_visited TEXT NOT NULL DEFAULT '',
            sitemap_frontier TEXT NOT NULL DEFAULT '',
            page_visited TEXT NOT NULL DEFAULT '',
            page_frontier TEXT NOT NULL DEFAULT '',
            FOREIGN KEY(source_id) REFERENCES sources(id)
        )""".trimIndent())
    }

    private fun createArchiveTables(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS searches (id INTEGER PRIMARY KEY AUTOINCREMENT, saved_at INTEGER NOT NULL)")
        db.execSQL("""CREATE TABLE IF NOT EXISTS archived_matches (
            id INTEGER PRIMARY KEY AUTOINCREMENT, search_id INTEGER NOT NULL,
            source_base_url TEXT NOT NULL, page_url TEXT NOT NULL, image_url TEXT NOT NULL,
            similarity REAL NOT NULL, checked_at INTEGER NOT NULL, face_box TEXT, confirmed INTEGER NOT NULL DEFAULT 0, post_text TEXT NOT NULL DEFAULT ''
        )""".trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_archived_search ON archived_matches(search_id)")
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
        if (oldVersion < 7) {
            // Remove anime, cartoon, hentai-comic, booru and furry-art sources.
            db.delete("sources", "name IN (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", arrayOf(
                "Baraag", "Paheal", "e621", "Gelbooru", "Rule34", "Danbooru",
                "Safebooru", "ExHentai", "E-Hentai", "Hitomi", "Fakku",
                "HentaiFoundry", "8muses", "Luscious", "HentaiRox", "Hentai0Day",
                "Newgrounds", "Inkbunny", "Fur Affinity", "Hentai2Read",
                "MyHentaiGallery", "MultPorn", "PornComics", "XXXComics",
                "Fenoxo Blog", "Audiochan", "Nutaku", "DLsite", "Newgrounds Adult"
            ))
        }
        if (oldVersion < 8) {
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_candidates_pending ON candidates(checked_at, discovered_at)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_candidates_matches ON candidates(scan_status, similarity, checked_at)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_sources_enabled_scan ON sources(enabled, last_scan)")
        }
        if (oldVersion < 9) {
            db.execSQL("ALTER TABLE candidates ADD COLUMN face_box TEXT")
        }
        if (oldVersion < 10) createArchiveTables(db)
        if (oldVersion < 11) seedSources(db)
        if (oldVersion < 12) seedSources(db)
        if (oldVersion < 13) createCrawlStateTable(db)
        if (oldVersion < 14) seedSources(db)
        if (oldVersion < 15) {
            removeSources(db, REMOVED_IN_15_HOSTS)
            seedSources(db)
        }
        if (oldVersion < 16) seedSources(db)
        if (oldVersion < 17) {
            removeSources(db, REMOVED_IN_17_HOSTS)
        }
        if (oldVersion < 18) {
            db.execSQL("ALTER TABLE candidates ADD COLUMN post_text TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE archived_matches ADD COLUMN post_text TEXT NOT NULL DEFAULT ''")
        }
        if (oldVersion < 19) {
            // Add photo-focused public galleries and portfolio communities.
            seedSources(db)
        }
        if (oldVersion < 20) removeSources(db, REMOVED_IN_20_HOSTS)
        if (oldVersion < 21) {
            db.execSQL("ALTER TABLE sources ADD COLUMN empty_runs INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE sources ADD COLUMN last_attempt INTEGER NOT NULL DEFAULT 0")
        }
    }

    /** Deletes these sources (by host) along with their stored image links and crawl progress. */
    private fun removeSources(db: SQLiteDatabase, hosts: List<String>) {
        val ids = mutableListOf<Long>()
        db.rawQuery("SELECT id, base_url FROM sources", null).use { c ->
            while (c.moveToNext()) {
                val host = android.net.Uri.parse(c.getString(1)).host?.lowercase()?.removePrefix("www.").orEmpty()
                if (hosts.any { host == it || host.endsWith(".$it") }) ids += c.getLong(0)
            }
        }
        for (id in ids) {
            val arg = arrayOf(id.toString())
            db.delete("candidates", "source_id=?", arg)
            db.delete("crawl_state", "source_id=?", arg)
            db.delete("sources", "id=?", arg)
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
            "RedGIFs" to "https://www.redgifs.com",
            "GIFs.com Adult" to "https://gifs.com",
            "Erome" to "https://www.erome.com",
            "Anonibx (public pages only)" to "https://anonibx.com",
            "ImageFap" to "https://www.imagefap.com",
            "PornPics" to "https://www.pornpics.com",
            "PornPic" to "https://www.pornpic.com",
            "XXX Gallery" to "https://xxx.gallery",
            "Nudy.pics" to "https://nudy.pics",
            "Siss.pics" to "https://siss.pics",
            "Fapello" to "https://fapello.com",
            // Creator and community platforms
            "OnlyFans" to "https://onlyfans.com",
            "Fansly" to "https://fansly.com",
            "Fanvue" to "https://www.fanvue.com",
            "ManyVids" to "https://www.manyvids.com",
            "Clips4Sale" to "https://www.clips4sale.com",
            "JustForFans" to "https://justfor.fans",
            "LoyalFans" to "https://www.loyalfans.com",
            "FetLife" to "https://fetlife.com",
            "Fabswingers" to "https://www.fabswingers.com",
            "AdultFriendFinder" to "https://adultfriendfinder.com",
            "Collarspace" to "https://collar.space",
            "KinkD" to "https://kinkd.app",
            // Adult forums and discussion communities
            "PornBB Forum" to "https://www.pornbb.org",
            "Phun Forum" to "https://www.phun.org",
            "Social Media Girls Forum" to "https://socialmediagirls.com",
            "EroMe Community" to "https://www.erome.com",
            "Reddit (public communities)" to "https://www.reddit.com",
            "4chan (public boards)" to "https://www.4chan.org",
            // Additional public platforms and directories
            "Anonibx" to "https://anonibx.com",
            "Snapwins" to "https://snapwins.net",
            "Xpics" to "https://xpics.me",
            "Sex.com" to "https://www.sex.com",
            "SavePorn" to "https://saveporn.net",
            "91Porn" to "https://91porn.com",
            "XGX" to "https://xgx.mobi",
            "Ero-Video" to "https://ero-video.net",
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
            "Serious Bondage Forum" to "https://www.seriousbondage.com",
            "The Cage Forum" to "https://thecage.co",
            "Extreme Board" to "https://www.extreme-board.com",
            "CuckoldPlace Forum" to "https://www.cuckoldplace.com",
            "GoonVilla Forum" to "https://goonvilla.com",
            "xBunker Forum" to "https://xbunker.com",
            "ErotikForum" to "https://www.erotikforum.at",
            "SexForum NZ" to "https://www.sexforum.co.nz",
            "Fritchy Forum" to "https://www.fritchy.com",
            "Undertow Club" to "https://undertow.club",
            "TitsInTops Forum" to "https://www.titsintops.com",
            "NWorship Forum" to "https://nworship.com",
            "WifeWantsToPlay Forum" to "https://www.wifewantstoplay.com",
            "PeeFans Forum" to "https://www.peefans.com",
            "OneClickChicks Forum" to "https://www.oneclickchicks.com",
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
            "Marriage Heat" to "https://marriageheat.com",
            "ChatZoZo" to "https://chatzozo.com",
            "Eporner" to "https://www.eporner.com",
            "Chaturbate" to "https://chaturbate.com",
            "Cam4" to "https://www.cam4.com",
            "CamSoda" to "https://www.camsoda.com",
            "Stripchat" to "https://stripchat.com",
            "BongaCams" to "https://bongacams.com",
            "MyFreeCams" to "https://www.myfreecams.com",
            "LiveJasmin" to "https://www.livejasmin.com",
            // Dating and swinger platforms with public profile pages
            "AdultFriendFinder" to "https://adultfriendfinder.com",
            "Fabswingers" to "https://www.fabswingers.com",
            "SwingLifeStyle" to "https://www.sls.com",
            "FetLife" to "https://fetlife.com",
            "Seeking" to "https://www.seeking.com",
            "WhatsYourPrice" to "https://www.whatsyourprice.com",
            "Ashley Madison" to "https://www.ashleymadison.com",
            // Additional cam and clip platforms
            "Cherry.tv" to "https://cherry.tv",
            "Flirt4Free" to "https://www.flirt4free.com",
            "ImLive" to "https://www.imlive.com",
            "Streamate" to "https://streamate.com",
            // Additional image-first boards
            "Reddit (NSFW search, old.reddit.com)" to "https://old.reddit.com",
            "Coomer" to "https://coomer.su",
            // More video and clip platforms
            "PornOne" to "https://www.pornone.com",
            "SxyPrn" to "https://sxyprn.com",
            "Txxx" to "https://txxx.com",
            "ThisVid" to "https://thisvid.com",
            "PornTrex" to "https://porntrex.com",
            "GotPorn" to "https://gotporn.com",
            "PornDig" to "https://porndig.com",
            "PornKai" to "https://pornkai.com",
            "4Tube" to "https://www.4tube.com",
            "HClips" to "https://hclips.com",
            "UPorn" to "https://uporn.com",
            "XXXBunker" to "https://xxxbunker.com",
            "ExtremeTube" to "https://www.extremetube.com",
            // More galleries and image boards
            "PimpAndHost" to "https://pimpandhost.com",
            "Fapdu" to "https://fapdu.com",
            "Imgbox" to "https://imgbox.com",
            "Pixhost" to "https://pixhost.to",
            "Turboimagehost" to "https://www.turboimagehost.com",
            "Vipr.im" to "https://vipr.im",
            "Picbaron" to "https://picbaron.com",
            "AcidImg" to "https://acidimg.cc",
            // Escort and companion ad / review aggregators (a common place a photo ends up reposted without consent)
            "Tryst" to "https://tryst.link",
            "CityXGuide" to "https://cityxguide.com",
            "ListCrawler" to "https://listcrawler.eu",
            "Slixa" to "https://slixa.com",
            "EscortAlligator" to "https://escortalligator.com",
            "AdultSearch" to "https://adultsearch.com",
            "USASexGuide" to "https://usasexguide.info",
            "Rubmaps" to "https://rubmaps.ch",
            "Megapersonals" to "https://megapersonals.eu",
            "Harlothub" to "https://harlothub.com",
            "Bedpage" to "https://bedpage.com",
            "AdultWork" to "https://www.adultwork.com",
            "PunterNet" to "https://www.punternet.com",
            // More camming
            "Jerkmate" to "https://jerkmate.com",
            "Cams.com" to "https://www.cams.com",
            // More dating and swinger platforms
            "Feeld" to "https://feeld.co",
            "SwapFinder" to "https://www.swapfinder.com",
            "Kasidie" to "https://www.kasidie.com",
            "SDC (Swingers Date Club)" to "https://www.sdc.com",
            "Alt.com" to "https://www.alt.com",
            "CollarMe" to "https://www.collarme.com",
            // Mugshot and public-record aggregators (a common, non-adult exposure vector for your face)
            "Mugshots.com" to "https://mugshots.com",
            "JailBase" to "https://jailbase.com",
            "BustedNewspaper" to "https://www.bustednewspaper.com",
            "Arrests.org" to "https://arrests.org",
            "Busted Mugshots" to "https://bustedmugshots.com",
            // People-search / data-broker aggregators (these often surface photos pulled from other public sources)
            "Spokeo" to "https://www.spokeo.com",
            "BeenVerified" to "https://www.beenverified.com",
            "TruePeopleSearch" to "https://www.truepeoplesearch.com",
            "PeopleFinders" to "https://www.peoplefinders.com",
            "Radaris" to "https://radaris.com",
            "PeekYou" to "https://www.peekyou.com",
            "FastPeopleSearch" to "https://www.fastpeoplesearch.com",
            "Whitepages" to "https://www.whitepages.com",
            "Intelius" to "https://www.intelius.com",
            "CheckPeople" to "https://checkpeople.com",
            // Even more tube/clip platforms
            "3Movs" to "https://3movs.com",
            "AnyPorn" to "https://anyporn.com",
            "Vporn" to "https://vporn.com",
            "Fuq" to "https://fuq.com",
            "Shooshtime" to "https://shooshtime.com",
            "XCafe" to "https://xcafe.com",
            "Analdin" to "https://analdin.com",
            "Upornia" to "https://upornia.com",
            "Hellporno" to "https://hellporno.com",
            "Hotmovs" to "https://hotmovs.com",
            "SunPorno" to "https://www.sunporno.com",
            "Ah-Me" to "https://ah-me.com",
            // More galleries
            "Fuskator" to "https://fuskator.com",
            "ImgSpice" to "https://imgspice.com",
            // More camming
            "SkyPrivate" to "https://skyprivate.com",
            "xLoveCam" to "https://www.xlovecam.com",
            // More escort/companion directories
            "EROS Guide" to "https://www.eros.com",
            "Vivastreet" to "https://www.vivastreet.co.uk",
            // More people-search / public-record aggregators
            "USSearch" to "https://www.ussearch.com",
            "PeopleLooker" to "https://peoplelooker.com",
            "InstantCheckmate" to "https://www.instantcheckmate.com",
            "ThatsThem" to "https://thatsthem.com",
            "Nuwber" to "https://nuwber.com",
            "Spytox" to "https://www.spytox.com",
            "ZabaSearch" to "https://www.zabasearch.com",
            "Pipl" to "https://pipl.com",
            "ClustrMaps" to "https://clustrmaps.com",
            "CountyOffice" to "https://www.countyoffice.org",
            // Personal photo albums.
            "Photobucket Public Albums" to "https://photobucket.com",
            // Image hosts and social sites where photos get reposted.
            "Imgur" to "https://imgur.com",
            "ImgBB" to "https://imgbb.com",
            "Postimages" to "https://postimages.org",
            "ImageChest" to "https://imgchest.com",
            "Catbox" to "https://catbox.moe",
            "Pixeldrain" to "https://pixeldrain.com",
            "Pinterest" to "https://www.pinterest.com",
            "Bluesky" to "https://bsky.app",
            "Mastodon Social" to "https://mastodon.social",
            "VK" to "https://vk.com",
            "X" to "https://x.com",
            "Lipstick Alley Forum" to "https://www.lipstickalley.com",
            // Real-photo boorus (drawn/anime boorus are deliberately left out: they can't contain a real face).
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
        readableDatabase.rawQuery("SELECT id,name,base_url,enabled,last_scan,last_status,empty_runs,last_attempt FROM sources ORDER BY name COLLATE NOCASE", null).use { c ->
            while (c.moveToNext()) result += WebsiteSource(
                c.getLong(0), c.getString(1), c.getString(2), c.getInt(3) == 1, c.getLong(4), c.getString(5), c.getInt(6), c.getLong(7)
            )
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
                    put("post_text", item.postText.take(240))
                }
                if (db.insertWithOnConflict("candidates", null, values, SQLiteDatabase.CONFLICT_IGNORE) != -1L) inserted++
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return inserted
    }

    /** [productive] = the scan found at least one image; otherwise the site's empty-run count goes up. */
    @Synchronized fun updateSourceStatus(id: Long, status: String, successful: Boolean = true, productive: Boolean = successful) {
        val now = System.currentTimeMillis()
        val values = ContentValues().apply {
            if (successful) put("last_scan", now)
            put("last_status", status.take(300))
            put("last_attempt", now)
        }
        writableDatabase.update("sources", values, "id=?", arrayOf(id.toString()))
        writableDatabase.execSQL(
            if (productive) "UPDATE sources SET empty_runs=0 WHERE id=?" else "UPDATE sources SET empty_runs=empty_runs+1 WHERE id=?",
            arrayOf(id)
        )
    }

    /** Where a source's crawl left off, so the next scan can resume deeper instead of restarting at the root. */
    @Synchronized fun loadCrawlState(sourceId: Long): CrawlState {
        readableDatabase.rawQuery(
            "SELECT sitemap_visited,sitemap_frontier,page_visited,page_frontier FROM crawl_state WHERE source_id=?",
            arrayOf(sourceId.toString())
        ).use { c ->
            if (c.moveToFirst()) {
                return CrawlState(
                    sitemapVisited = splitLines(c.getString(0)),
                    sitemapFrontier = splitLines(c.getString(1)).toList(),
                    pageVisited = splitLines(c.getString(2)),
                    pageFrontier = splitLines(c.getString(3)).toList(),
                )
            }
        }
        return CrawlState()
    }

    @Synchronized fun saveCrawlState(sourceId: Long, state: CrawlState) {
        val values = ContentValues().apply {
            put("source_id", sourceId)
            put("sitemap_visited", joinLines(state.sitemapVisited, CrawlState.MAX_PERSISTED_SITEMAP_VISITED))
            put("sitemap_frontier", joinLines(state.sitemapFrontier, CrawlState.MAX_PERSISTED_FRONTIER))
            put("page_visited", joinLines(state.pageVisited, CrawlState.MAX_PERSISTED_PAGE_VISITED))
            put("page_frontier", joinLines(state.pageFrontier, CrawlState.MAX_PERSISTED_FRONTIER))
        }
        writableDatabase.insertWithOnConflict("crawl_state", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun splitLines(text: String?): LinkedHashSet<String> =
        text.orEmpty().split('\n').filter { it.isNotBlank() }.toCollection(LinkedHashSet())

    /** Keeps only the most recent [max] entries so a very link-dense site can't grow this table forever. */
    private fun joinLines(items: Collection<String>, max: Int): String =
        items.toList().takeLast(max).joinToString("\n")

    /** Candidates not yet compared in a previous run; unavailable URLs may be retried. */
    /** Images not compared yet, newest first. [sourceIds] limits it to those sources (null = every source). */
    @Synchronized fun pendingCandidates(
        limit: Int = 5000,
        sourceIds: Collection<Long>? = null,
        priorityKeywords: List<String> = emptyList()
    ): List<StoredCandidate> {
        val result = mutableListOf<StoredCandidate>()
        if (sourceIds != null && sourceIds.isEmpty()) return result
        // Ids are numbers from our own table, so inlining them is safe and avoids SQLite's bound-argument limit.
        val sourceFilter = sourceIds?.joinToString(",", prefix = " AND c.source_id IN (", postfix = ")").orEmpty()
        val terms = priorityKeywords.map { it.trim().lowercase(Locale.US) }
            .filter { it.length >= 2 }.distinct().take(6)
        val prioritySql = if (terms.isEmpty()) "" else
            "CASE WHEN (" + terms.joinToString(" OR ") {
                "(lower(c.post_text) LIKE ? OR lower(c.page_url) LIKE ?)"
            } + ") THEN 0 ELSE 1 END, "
        val args = terms.flatMap { listOf("%$it%", "%$it%") } +
            listOf(limit.coerceIn(1, 5000).toString())
        readableDatabase.rawQuery(
            """SELECT c.id,c.source_id,s.base_url,c.page_url,c.image_url,c.post_text
               FROM candidates c JOIN sources s ON s.id=c.source_id
               WHERE c.checked_at=0$sourceFilter
               ORDER BY $prioritySql c.discovered_at DESC LIMIT ?""".trimIndent(),
            args.toTypedArray()
        ).use { c ->
            while (c.moveToNext()) result += StoredCandidate(
                c.getLong(0), c.getLong(1), c.getString(2), c.getString(3), c.getString(4), c.getString(5)
            )
        }
        return result
    }

    /** Forget earlier comparisons so every image is scored again. Matches marked "Not a match" stay dismissed. */
    @Synchronized fun resetCheckedResults() {
        writableDatabase.execSQL(
            "UPDATE candidates SET scan_status='Discovered', similarity=NULL, checked_at=0 " +
                "WHERE scan_status IN ('Possible match','Compared','Unavailable')"
        )
    }

    @Synchronized fun allCandidates(limit: Int = 100): List<StoredCandidate> {
        val result = mutableListOf<StoredCandidate>()
        readableDatabase.rawQuery(
            """SELECT c.id,c.source_id,s.base_url,c.page_url,c.image_url,c.post_text
               FROM candidates c JOIN sources s ON s.id=c.source_id
               WHERE c.checked_at=0
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

    @Synchronized fun updateCandidateResults(updates: List<CandidateUpdate>) {
        if (updates.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            val statement = db.compileStatement("UPDATE candidates SET scan_status=?, similarity=?, checked_at=?, face_box=? WHERE id=?")
            val now = System.currentTimeMillis()
            for (item in updates) {
                statement.clearBindings()
                statement.bindString(1, item.status.take(100))
                if (item.similarity == null) statement.bindNull(2) else statement.bindDouble(2, item.similarity.toDouble())
                statement.bindLong(3, now)
                if (item.faceBox == null) statement.bindNull(4) else statement.bindString(4, item.faceBox)
                statement.bindLong(5, item.id)
                statement.executeUpdateDelete()
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    @Synchronized fun matchRevision(): String {
        readableDatabase.rawQuery("SELECT COALESCE(MAX(checked_at), 0), COUNT(*) FROM candidates WHERE scan_status IN ('Possible match','Confirmed match') AND similarity >= 0.3", null).use {
            return if (it.moveToFirst()) "${it.getLong(0)}:${it.getLong(1)}" else "0:0"
        }
    }

    @Synchronized fun updateCandidateResult(id: Long, status: String, similarity: Float?) {
        val values = ContentValues().apply {
            put("scan_status", status.take(100))
            if (similarity == null) putNull("similarity") else put("similarity", similarity)
            put("checked_at", System.currentTimeMillis())
        }
        writableDatabase.update("candidates", values, "id=?", arrayOf(id.toString()))
    }

    @Synchronized fun possibleMatches(limit: Int = 50, bestFirst: Boolean = false): List<MatchResult> {
        val result = mutableListOf<MatchResult>()
        readableDatabase.rawQuery(
            """SELECT c.id,s.base_url,c.page_url,c.image_url,c.similarity,c.checked_at,c.face_box,c.scan_status,c.post_text
               FROM candidates c JOIN sources s ON s.id=c.source_id
               WHERE c.scan_status IN ('Possible match','Confirmed match') AND c.similarity >= 0.3
               ORDER BY ${if (bestFirst) "c.similarity DESC, c.checked_at DESC" else "c.checked_at DESC"} LIMIT ?""".trimIndent(),
            arrayOf(limit.coerceIn(1, 2000).toString())
        ).use { c ->
            while (c.moveToNext()) result += MatchResult(
                c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getFloat(4), c.getLong(5),
                c.getString(6), c.getString(7) == "Confirmed match", c.getString(8)
            )
        }
        return result
    }

    /**
     * Saves the current possible matches as a separate past search, then empties the working data so a new
     * search starts from scratch. Returns how many matches were saved (0 means nothing was worth keeping).
     */
    @Synchronized fun archiveCurrentSearch(): Int {
        val db = writableDatabase
        var saved = 0
        val keep = "c.scan_status IN ('Possible match','Confirmed match') AND c.similarity >= 0.3"
        db.beginTransaction()
        try {
            db.rawQuery("SELECT COUNT(*) FROM candidates c WHERE $keep", null).use { if (it.moveToFirst()) saved = it.getInt(0) }
            if (saved > 0) {
                db.execSQL("INSERT INTO searches(saved_at) VALUES (${System.currentTimeMillis()})")
                db.execSQL(
                    """INSERT INTO archived_matches(search_id, source_base_url, page_url, image_url, similarity, checked_at, face_box, confirmed, post_text)
                       SELECT (SELECT MAX(id) FROM searches), s.base_url, c.page_url, c.image_url, c.similarity, c.checked_at, c.face_box,
                              CASE WHEN c.scan_status='Confirmed match' THEN 1 ELSE 0 END, c.post_text
                       FROM candidates c JOIN sources s ON s.id=c.source_id WHERE $keep""".trimIndent()
                )
            }
            db.execSQL("DELETE FROM candidates")
            db.execSQL("UPDATE sources SET last_scan=0, last_status='Not scanned'")
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return saved
    }

    @Synchronized fun pastSearches(): List<SearchInfo> {
        val result = mutableListOf<SearchInfo>()
        readableDatabase.rawQuery(
            "SELECT s.id, s.saved_at, (SELECT COUNT(*) FROM archived_matches a WHERE a.search_id=s.id) FROM searches s ORDER BY s.id DESC", null
        ).use { c -> while (c.moveToNext()) result += SearchInfo(c.getLong(0), c.getLong(1), c.getInt(2)) }
        return result
    }

    @Synchronized fun pastSearchMatches(searchId: Long): List<MatchResult> {
        val result = mutableListOf<MatchResult>()
        readableDatabase.rawQuery(
            "SELECT id, source_base_url, page_url, image_url, similarity, checked_at, face_box, confirmed, post_text " +
                "FROM archived_matches WHERE search_id=? ORDER BY similarity DESC", arrayOf(searchId.toString())
        ).use { c ->
            while (c.moveToNext()) result += MatchResult(
                c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getFloat(4), c.getLong(5),
                c.getString(6), c.getInt(7) == 1, c.getString(8)
            )
        }
        return result
    }

    @Synchronized fun deletePastSearch(searchId: Long) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("archived_matches", "search_id=?", arrayOf(searchId.toString()))
            db.delete("searches", "id=?", arrayOf(searchId.toString()))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    /** Wipes every collected image link and result and marks all sources as never scanned, for a fresh search. */
    @Synchronized fun clearSearch() {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("DELETE FROM candidates")
            db.execSQL("UPDATE sources SET last_scan=0, last_status='Not scanned'")
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    /** Possible matches first seen after [sinceMillis]; used by the scheduled re-scan. */
    @Synchronized fun newMatchCountSince(sinceMillis: Long): Int {
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM candidates WHERE scan_status='Possible match' AND similarity >= 0.3 AND checked_at > ?",
            arrayOf(sinceMillis.toString())
        ).use { return if (it.moveToFirst()) it.getInt(0) else 0 }
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
    private const val MAX_SITEMAPS = 60
    private const val MAX_IMAGES = 8000
    private const val MAX_SITEMAP_PAGES = 300
    private const val MAX_LINKED_PAGES = 1000
    private const val MAX_PAGE_BYTES = 1024 * 1024
    // One slow or half-dead site shouldn't hold a crawl slot for the whole search. When either limit
    // is hit, the unvisited pages are saved and the next search carries on from there.
    private const val SOURCE_TIME_BUDGET_MS = 4L * 60L * 1000L
    private const val MAX_CONSECUTIVE_PAGE_FAILURES = 12

    /**
     * [previousState] resumes a crawl where an earlier scan of this same source left off (its unexplored
     * frontier, plus what's already been visited), so repeated scans of a large site progress deeper into
     * it over time instead of re-discovering the same bounded slice near the sitemap root every run. The
     * returned [CrawlState] is this run's progress, to persist and pass in again next time.
     */
    fun scan(
        source: WebsiteSource,
        previousState: CrawlState = CrawlState(),
        amateurFocus: Boolean = false,
        onProgress: (String) -> Unit = {}
    ): Pair<List<DiscoveredImage>, CrawlState> {
        val base = source.baseUrl
        // "Amateur and homemade only" (see TagFocus): on tagged video/photo sites, only amateur-tagged pages
        // and the pages their tag listings link to are looked at.
        val tagFocus = amateurFocus && TagFocus.appliesTo(source)
        val tagApproved = HashSet<String>()
        fun tagAllowed(page: String) = !tagFocus || TagFocus.matches(page) || page in tagApproved
        onProgress("Checking public access rules (robots.txt)")
        val deadline = System.currentTimeMillis() + SOURCE_TIME_BUDGET_MS
        fun shouldStop() = Thread.currentThread().isInterrupted || System.currentTimeMillis() > deadline
        // A timeout, server error, or forbidden robots.txt response means skip this host.
        // A genuine 404 means the site has no robots file and can be crawled normally.
        val robotsText = readRobots("$base/robots.txt")
            ?: throw java.io.IOException("robots.txt could not be read (blocked, timed out, or server error)")
        val rules = parseRobots(robotsText)
        onProgress("Reading sitemap and feed indexes")
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
        // Resume the leftover frontier from last time first, so this run pushes further into it;
        // freshly-seeded sitemap entries are appended after and still get a look each run.
        val pending = (previousState.sitemapFrontier + sitemapUrls.toList()).distinct().toMutableList()
        val visited = previousState.sitemapVisited.toMutableSet()
        val publicPages = linkedSetOf<String>()
        while (pending.isNotEmpty() && visited.size < previousState.sitemapVisited.size + MAX_SITEMAPS &&
            found.size < MAX_IMAGES && !shouldStop()) {
            val endpoint = pending.removeAt(0)
            if (visited.size % 3 == 0) onProgress("Reading sitemap indexes · ${visited.size} checked · ${found.size} image links")
            if (!visited.add(endpoint)) continue
            if (!sameHost(base, endpoint) || isDisallowed(endpoint, rules)) continue
            val xml = readText(endpoint) ?: continue
            parseXml(endpoint, xml, source.id, pending, found, publicPages, base, rules)
        }

        // Sitemap entries are often public gallery/article pages, not direct images.
        // Inspect a bounded sample, then follow a small number of same-host public
        // links from those pages. This reaches gallery/detail pages omitted from
        // image sitemaps without turning the phone into an unbounded crawler.
        onProgress("Preparing public gallery and thread pages · ${found.size} image links found")
        val pageQueue = ArrayDeque<String>()
        // Resume last run's unexplored page frontier first, so depth genuinely accumulates over time.
        previousState.pageFrontier.filter { tagAllowed(it) }.forEach { pageQueue.addLast(it) }
        publicPages.filter { tagAllowed(it) }.take(MAX_SITEMAP_PAGES).forEach { if (it !in pageQueue) pageQueue.addLast(it) }
        val seedPages = if (tagFocus) TagFocus.seedPages(base).filter { !isDisallowed(it, rules) }.toSet() else emptySet()
        if (tagFocus) {
            // Tag listings first, and always re-read: they're where new uploads appear.
            seedPages.reversed().forEach { if (it !in pageQueue) pageQueue.addFirst(it) }
        } else {
            // Always inspect the public home page, not only forums. Many galleries and
            // photo sites have no sitemap/feed, but expose their public catalog from /.
            val homePage = base.trimEnd('/') + "/"
            if (!isDisallowed(homePage, rules) && homePage !in pageQueue) pageQueue.addFirst(homePage)
        }

        // Forum software often exposes its useful public content from index,
        // category, latest-topic and gallery routes rather than sitemaps.
        // Each route is still checked against robots.txt before it is fetched.
        val imageboardLike = SourceGroup.IMAGEBOARDS.contains(source)
        val forumLike = imageboardLike ||
            source.name.contains("forum", true) ||
            source.name.contains("community", true) ||
            source.name.contains("board", true)
        if (forumLike) {
            // Public index, gallery, thread and pagination entry points used by
            // common forum/imageboard software. Missing routes simply return no
            // page; every request remains same-host and robots.txt checked.
            val forumRoutes = buildList {
                addAll(listOf(
                    "/", "/forums", "/forum", "/community", "/boards", "/board",
                    "/latest", "/recent", "/activity", "/whats-new/",
                    "/whats-new/posts/", "/categories", "/threads", "/topics",
                    "/discussions", "/posts", "/images", "/photos", "/gallery",
                    "/media", "/albums", "/members", "/search",
                    "/forum/index.php", "/forumdisplay.php", "/showthread.php",
                    "/viewthread.php", "/viewtopic.php", "/viewforum.php"
                ))
                if (imageboardLike) {
                    addAll(listOf(
                        "/index", "/index.php", "/board/index.php", "/boards/index.php",
                        "/catalog", "/catalog.html", "/archive", "/archive/",
                        "/recent-images", "/latest-images", "/latest-posts",
                        "/images/recent", "/photos/recent", "/threads/recent",
                        "/posts/recent", "/media/recent",
                        "/thread", "/threads", "/posts", "/galleries", "/groups",
                        "/users", "/members", "/videos", "/images"
                    ))
                }
            }
            for (route in forumRoutes) {
                val address = base.trimEnd('/') + route
                if (!isDisallowed(address, rules) && address !in publicPages) {
                    pageQueue.addLast(address)
                }
            }
        }
        val visitedPages = previousState.pageVisited.toMutableSet()
        visitedPages.removeAll(seedPages)
        var newlyVisited = 0
        var linkedPagesQueued = 0
        var consecutiveFailures = 0
        // Mirrors pageQueue so "already queued?" is a constant-time lookup instead of a scan of the queue.
        val queued = pageQueue.toHashSet()
        while (pageQueue.isNotEmpty() &&
            newlyVisited < MAX_SITEMAP_PAGES + MAX_LINKED_PAGES &&
            found.size < MAX_IMAGES &&
            consecutiveFailures < MAX_CONSECUTIVE_PAGE_FAILURES &&
            !shouldStop()) {
            val page = pageQueue.removeFirst()
            queued.remove(page)
            if (!tagAllowed(page)) continue
            if (!visitedPages.add(page) || !sameHost(base, page) || isDisallowed(page, rules)) continue
            newlyVisited++
            if (newlyVisited == 1 || newlyVisited % 5 == 0) {
                onProgress("Checking public pages · $newlyVisited visited · ${pageQueue.size} queued · ${found.size} image links")
            }
            val html = readPage(page)
            if (html == null) {
                // Guessed tag addresses a site doesn't have aren't a sign the site is failing.
                if (page !in seedPages) consecutiveFailures++
                continue
            }
            consecutiveFailures = 0
            val links = extractPageImages(page, html, source.id, found)
            // From an amateur tag page, follow the pages it lists (one step); elsewhere only amateur-named pages.
            val onTagPage = tagFocus && TagFocus.matches(page)
            for (link in links) {
                if (linkedPagesQueued >= MAX_LINKED_PAGES) break
                if (tagFocus && !onTagPage && !TagFocus.matches(link)) continue
                if (link !in visitedPages && link !in queued &&
                    sameHost(base, link) && !isDisallowed(link, rules) && isLikelyHtmlPage(link)) {
                    if (onTagPage) tagApproved.add(link)
                    pageQueue.addLast(link)
                    queued.add(link)
                    linkedPagesQueued++
                }
            }
        }
        onProgress("Finishing source · $newlyVisited pages checked · ${found.size} image links found")
        val newState = CrawlState(
            sitemapVisited = visited,
            sitemapFrontier = pending.toList(),
            pageVisited = visitedPages,
            pageFrontier = pageQueue.toList(),
        )
        // Image-sitemap entries can point at any page, so drop the ones from pages outside the amateur tags.
        val images = found.values.filter { tagAllowed(it.pageUrl) }
        return images.take(MAX_IMAGES).toList() to newState
    }

    private fun readRobots(address: String): String? {
        var connection: HttpURLConnection? = null
        return try {
            connection = URL(address).openConnection() as? HttpURLConnection ?: return null
            connection.connectTimeout = 7000
            connection.readTimeout = 9000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("User-Agent", "FacePersonalScanner/1.1 (public page and sitemap reader)")
            connection.setRequestProperty("Accept", "text/plain")
            when (val code = connection.responseCode) {
                404 -> ""
                in 200..299 -> {
                    val type = connection.contentType?.substringBefore(';')?.trim()?.lowercase(Locale.US).orEmpty()
                    if (!type.contains("text/plain") && !type.contains("text")) return null
                    if (connection.contentLengthLong > 256 * 1024) return null
                    val output = java.io.ByteArrayOutputStream()
                    connection.inputStream.use { input ->
                        val buffer = ByteArray(8192)
                        var total = 0
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            if (total > 256 * 1024) return null
                            output.write(buffer, 0, count)
                        }
                    }
                    output.toString(Charsets.UTF_8.name())
                }
                else -> null
            }
        } catch (_: Exception) {
            null
        } finally { connection?.disconnect() }
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
        val attrRegex = Regex("(?is)([a-z_:][-a-z0-9_:.]*)\\s*=\\s*(['\"])(.*?)\\2")
        val labels = metaTag.findAll(html).mapNotNull { tag ->
            val attrs = attrRegex.findAll(tag.value).associate {
                it.groupValues[1].lowercase(Locale.US) to it.groupValues[3]
            }
            val key = (attrs["property"] ?: attrs["name"] ?: "").lowercase(Locale.US)
            if (key in setOf("og:title", "twitter:title", "og:description", "description")) attrs["content"] else null
        }
        val pageLabel = (labels.firstOrNull { it.isNotBlank() }
            ?: Regex("(?is)<title\\b[^>]*>(.*?)</title>").find(html)?.groupValues?.get(1)
            ?: Regex("(?is)<h1\\b[^>]*>(.*?)</h1>").find(html)?.groupValues?.get(1)
            ?: "").replace(Regex("<[^>]+>"), " ").replace("&amp;", "&")
            .replace(Regex("\\s+"), " ").trim().take(240)
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
            found.putIfAbsent(imageUrl, DiscoveredImage(sourceId, pageUrl, imageUrl, pageLabel))
        }
        return pageLinks.sortedWith(
            compareByDescending<String> { pagePriority(it) }
                .thenBy { it.length }
        ).toCollection(linkedSetOf())
    }

    private fun pagePriority(address: String): Int {
        val path = try { URL(address).path.lowercase(Locale.US) } catch (_: Exception) { return 0 }
        val full = address.lowercase(Locale.US)
        var score = 0
        // Prefer pages likely to contain user-uploaded photos and public discussions.
        if (listOf("gallery", "photo", "image", "album", "media", "post", "thread", "topic")
                .any { path.contains(it) }) score += 5
        if (listOf("forum", "discussion", "viewforum", "showthread", "viewthread",
                "viewtopic", "forumdisplay", "profile", "member").any { path.contains(it) }) score += 3
        // Follow visible pagination/older/newer links before unrelated navigation.
        if (listOf("page=", "start=", "offset=", "cursor=", "after=", "before=",
                "next", "older", "previous", "prev").any { full.contains(it) }) score += 6
        return score
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

    private fun readPage(address: String): String? =
        readPublicText(
            address,
            "text/html,application/xhtml+xml",
            MAX_PAGE_BYTES,
            setOf("text/html", "application/xhtml+xml")
        )

    /**
     * Reads an openly accessible public endpoint. Temporary server/network
     * failures get one bounded retry. 429 responses honor Retry-After; this
     * is resilience, not an attempt to evade rate limits or access controls.
     */
    private fun readText(address: String): String? =
        readPublicText(
            address,
            "application/xml,text/xml,application/rss+xml,application/atom+xml,text/plain",
            MAX_BYTES,
            null
        )

    private fun readPublicText(
        address: String,
        accept: String,
        maxBytes: Int,
        allowedTypes: Set<String>?
    ): String? {
        var attempt = 0
        while (attempt < 2) {
            var connection: HttpURLConnection? = null
            try {
                connection = URL(address).openConnection() as? HttpURLConnection ?: return null
                connection.connectTimeout = 7000
                connection.readTimeout = 9000
                connection.instanceFollowRedirects = false
                connection.setRequestProperty(
                    "User-Agent",
                    "FacePersonalScanner/1.1 (public page and sitemap reader)"
                )
                connection.setRequestProperty("Accept", accept)

                val code = connection.responseCode
                if (code in 300..399) {
                    // Do not follow redirects automatically. The destination may
                    // be another host or a path disallowed by that site's robots.txt.
                    return null
                }
                if (code !in 200..299) {
                    val retryable = code == 408 || code == 425 || code == 429 ||
                        code == 500 || code == 502 || code == 503 || code == 504
                    if (attempt == 0 && retryable) {
                        val retryAfter = connection.getHeaderField("Retry-After")
                            ?.trim()?.toLongOrNull()
                        val delayMs = if (code == 429 && retryAfter != null) {
                            retryAfter.coerceIn(1L, 30L) * 1000L
                        } else {
                            1200L
                        }
                        connection.disconnect()
                        connection = null
                        Thread.sleep(delayMs)
                        attempt++
                        continue
                    }
                    return null
                }

                val type = connection.contentType?.substringBefore(';')
                    ?.trim()?.lowercase(Locale.US) ?: return null
                if (allowedTypes != null && allowedTypes.none { type.contains(it) }) return null
                if (connection.contentLengthLong > maxBytes) return null

                val bytes = BufferedInputStream(connection.inputStream).use { input ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    var total = 0
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > maxBytes) return null
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                }
                return bytes.toString(Charsets.UTF_8)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return null
            } catch (_: Exception) {
                if (attempt == 0) {
                    attempt++
                    try {
                        Thread.sleep(500L)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return null
                    }
                } else {
                    return null
                }
            } finally {
                connection?.disconnect()
            }
        }
        return null
    }

    private fun sameHost(base: String, candidate: String): Boolean = try {
        URL(base).host.equals(URL(candidate).host, true)
    } catch (_: Exception) { false }

    private fun isImageUrl(value: String): Boolean {
        val path = try { URL(value).path.lowercase(Locale.US) } catch (_: Exception) { return false }
        return listOf(".jpg", ".jpeg", ".png", ".webp", ".gif").any { path.endsWith(it) }
    }

}
