package com.example.face

import android.content.Context
import android.net.Uri

/**
 * "Amateur and homemade only": on video and photo sites, the scan only looks at pages filed under
 * amateur/homemade-style tags (and the pages those tag listings link to), instead of the whole site.
 * That's where a private person's photos usually end up, and it makes each site much faster to scan.
 * Other kinds of sources (forums, image hosts, people-search sites, ...) are scanned as before.
 */
object TagFocus {
    private const val PREFS = "deep_search"
    private const val KEY = "amateur_focus"

    fun isOn(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, true)

    fun set(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, on).apply()
    }

    /** Words that mark a page as amateur/homemade-style when they appear in its address. */
    private val KEYWORDS = listOf(
        // Amateur and homemade
        "amateur", "homemade", "home-made", "home_made", "homevideo", "home-video",
        // Self-shot
        "selfie", "selfshot", "self-shot", "nudes", "sexting", "snapchat", "pov", "vertical",
        // Real-life couples
        "realcouple", "real-couple", "wife", "girlfriend", "exgf", "ex-gf", "ex-girlfriend",
        // Leaked or shared without consent
        "leak", "revenge", "cheating", "caught", "private",
        // Recorded secretly
        "hiddencam", "hidden-cam", "hidden-camera", "spycam", "spy-cam", "voyeur", "locker-room", "lockerroom",
        // Webcam and live
        "webcam", "camgirl", "cam-girl", "periscope", "livestream", "live-stream",
    )

    /** Tag listings tried on each site; ones a site doesn't have just come back empty. */
    private val MAIN_TAGS = listOf("amateur", "homemade")
    private val MAIN_PATTERNS = listOf("/categories/%s", "/category/%s", "/tags/%s", "/tag/%s", "/c/%s", "/%s")
    private val EXTRA_TAGS = listOf("selfie", "webcam", "voyeur", "hidden-cam", "leaked", "real-couple", "wife", "pov")
    private val EXTRA_PATTERNS = listOf("/categories/%s", "/tags/%s", "/tag/%s")

    /** Video and photo sites that file their content under tags or categories. */
    private val TAGGED_SITE_HOSTS = setOf(
        "pornhub.com", "xvideos.com", "xnxx.com", "xhamster.com", "youporn.com", "redtube.com", "tube8.com",
        "spankbang.com", "beeg.com", "tnaflix.com", "nuvid.com", "drtuber.com", "porn.com", "pornmd.com",
        "keezmovies.com", "empflix.com", "redgifs.com", "erome.com", "imagefap.com", "pornpics.com",
        "pornpic.com", "xxx.gallery", "nudy.pics", "siss.pics", "pichunter.com", "eporner.com", "faphouse.com",
        "sex.com", "coedcherry.com", "pornone.com", "sxyprn.com", "txxx.com", "thisvid.com", "hqporner.com",
        "porntrex.com", "gotporn.com", "porndig.com", "pornkai.com", "4tube.com", "hclips.com", "uporn.com",
        "xxxbunker.com", "extremetube.com", "3movs.com", "anyporn.com", "vporn.com", "fuq.com", "shooshtime.com",
        "xcafe.com", "analdin.com", "upornia.com", "hellporno.com", "hotmovs.com", "sunporno.com", "ah-me.com",
        "fuskator.com", "fapdu.com",
    )

    fun appliesTo(source: WebsiteSource): Boolean {
        val host = Uri.parse(source.baseUrl).host?.lowercase()?.removePrefix("www.").orEmpty()
        return TAGGED_SITE_HOSTS.any { host == it || host.endsWith(".$it") }
    }

    /** True if the address itself says amateur/homemade (tag pages, and video pages named that way). */
    fun matches(address: String): Boolean {
        val lower = address.lowercase()
        return KEYWORDS.any { lower.contains(it) }
    }

    fun seedPages(base: String): List<String> {
        val root = base.trimEnd('/')
        return MAIN_TAGS.flatMap { tag -> MAIN_PATTERNS.map { root + it.format(tag) } } +
            EXTRA_TAGS.flatMap { tag -> EXTRA_PATTERNS.map { root + it.format(tag) } }
    }
}
