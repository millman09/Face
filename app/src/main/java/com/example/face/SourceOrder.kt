package com.example.face

import android.net.Uri

/**
 * The order sites are scanned in: quick ones first, then the big popular sites, then everything else,
 * and the slow or unreliable ones last. Results from easy sites show up within minutes instead of
 * waiting behind a site that takes the full time budget.
 */
object SourceOrder {
    /** A site that finished in this long or less last time counts as quick. */
    private const val QUICK_MS = 45_000L

    /** The most-used sites, where a repost is most likely to be seen. */
    private val POPULAR_HOSTS = setOf(
        "pornhub.com", "xvideos.com", "xnxx.com", "xhamster.com", "youporn.com", "redtube.com", "spankbang.com",
        "eporner.com", "redgifs.com", "erome.com", "imagefap.com", "pornpics.com", "thisvid.com",
        "txxx.com", "porntrex.com", "4chan.org", "coomer.su", "imgur.com",
        "chaturbate.com", "stripchat.com", "bongacams.com", "camsoda.com",
    )

    fun isPopular(source: WebsiteSource): Boolean {
        val host = Uri.parse(source.baseUrl).host?.lowercase()?.removePrefix("www.").orEmpty()
        return POPULAR_HOSTS.any { host == it || host.endsWith(".$it") }
    }

    private fun tier(source: WebsiteSource): Int {
        val measured = source.lastDurationMs > 0L
        val troubled = source.emptyRuns > 0 || source.lastStatus.startsWith("Could not scan", ignoreCase = true)
        return when {
            measured && source.lastDurationMs <= QUICK_MS && !troubled -> 0 // quick
            isPopular(source) -> 1                                            // popular
            !measured && !troubled -> 2                                       // not timed yet
            else -> 3                                                         // slow or unreliable
        }
    }

    fun sort(sources: List<WebsiteSource>): List<WebsiteSource> =
        sources.sortedWith(compareBy<WebsiteSource>({ tier(it) }, { if (it.lastDurationMs > 0L) it.lastDurationMs else Long.MAX_VALUE }, { it.name.lowercase() }))
}
