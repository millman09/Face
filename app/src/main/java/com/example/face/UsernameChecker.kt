package com.example.face

import java.net.HttpURLConnection
import java.net.URL

/**
 * Checks whether a username has a public profile page on well-known sites, by asking each site for
 * the profile address and reading the HTTP status. It only looks at public pages, never logs in, and
 * never reads more than a small part of a page. Meant for checking your own usernames.
 */
object UsernameChecker {
    enum class Outcome { FOUND, NOT_FOUND, UNSURE }

    /** [url] contains {u} where the username goes. [missingText] is shown by sites that answer 200 for unknown users. */
    data class Site(val name: String, val url: String, val missingText: String? = null, val adult: Boolean = false)

    val sites: List<Site> = listOf(
        Site("GitHub", "https://github.com/{u}"),
        Site("GitLab", "https://gitlab.com/{u}"),
        Site("DEV", "https://dev.to/{u}"),
        Site("Medium", "https://medium.com/@{u}"),
        Site("Reddit", "https://www.reddit.com/user/{u}/"),
        Site("YouTube", "https://www.youtube.com/@{u}"),
        Site("Pinterest", "https://www.pinterest.com/{u}/"),
        Site("SoundCloud", "https://soundcloud.com/{u}"),
        Site("Flickr", "https://www.flickr.com/people/{u}"),
        Site("DeviantArt", "https://www.deviantart.com/{u}"),
        Site("Vimeo", "https://vimeo.com/{u}"),
        Site("Keybase", "https://keybase.io/{u}"),
        Site("Gravatar", "https://gravatar.com/{u}"),
        Site("Linktree", "https://linktr.ee/{u}"),
        Site("Ko-fi", "https://ko-fi.com/{u}"),
        Site("Patreon", "https://www.patreon.com/{u}"),
        Site("Behance", "https://www.behance.net/{u}"),
        Site("Dribbble", "https://dribbble.com/{u}"),
        Site("Mastodon (mastodon.social)", "https://mastodon.social/@{u}"),
        Site("Steam", "https://steamcommunity.com/id/{u}", "The specified profile could not be found"),
        Site("Hacker News", "https://news.ycombinator.com/user?id={u}", "No such user"),
        Site("Chess.com", "https://www.chess.com/member/{u}"),
        Site("Lichess", "https://lichess.org/@/{u}"),
        Site("Replit", "https://replit.com/@{u}"),
        Site("CodePen", "https://codepen.io/{u}"),
        Site("Letterboxd", "https://letterboxd.com/{u}/"),
        Site("Last.fm", "https://www.last.fm/user/{u}"),
        Site("Imgur", "https://imgur.com/user/{u}"),
        Site("Pastebin", "https://pastebin.com/u/{u}"),
        Site("npm", "https://www.npmjs.com/~{u}"),
        Site("PyPI", "https://pypi.org/user/{u}/"),
        Site("Wattpad", "https://www.wattpad.com/user/{u}"),
        Site("Etsy", "https://www.etsy.com/people/{u}"),
        Site("About.me", "https://about.me/{u}"),
        // Adult sites: profile pages only, so you can find accounts or impersonation under your own name.
        Site("Pornhub", "https://www.pornhub.com/users/{u}", adult = true),
        Site("XVideos", "https://www.xvideos.com/profiles/{u}", adult = true),
        Site("xHamster", "https://xhamster.com/users/{u}", adult = true),
        Site("SpankBang", "https://spankbang.com/profile/{u}", adult = true),
        Site("RedGIFs", "https://www.redgifs.com/users/{u}", adult = true),
        Site("Chaturbate", "https://chaturbate.com/{u}/", adult = true),
        Site("Stripchat", "https://stripchat.com/{u}", adult = true),
        Site("CamSoda", "https://www.camsoda.com/{u}", adult = true),
        Site("OnlyFans", "https://onlyfans.com/{u}", adult = true),
        Site("Fansly", "https://fansly.com/{u}", adult = true),
    )

    /** Returns the cleaned username, or null if it has characters that could change the address. */
    fun clean(raw: String): String? {
        val name = raw.trim().removePrefix("@")
        return if (Regex("[A-Za-z0-9._-]{2,40}").matches(name)) name else null
    }

    fun profileUrl(site: Site, username: String) = site.url.replace("{u}", username)

    fun check(site: Site, username: String): Outcome {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(profileUrl(site, username)).openConnection() as HttpURLConnection).apply {
                connectTimeout = 6000
                readTimeout = 8000
                instanceFollowRedirects = true
                requestMethod = "GET"
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) FaceUsernameCheck/1.0")
                setRequestProperty("Accept", "text/html")
            }
            val code = connection.responseCode
            when {
                code == 404 || code == 410 -> Outcome.NOT_FOUND
                code in 200..299 -> {
                    val missing = site.missingText
                    if (missing == null) Outcome.FOUND else {
                        val text = connection.inputStream.use { stream ->
                            val buffer = ByteArray(64 * 1024)
                            val count = stream.read(buffer)
                            if (count > 0) String(buffer, 0, count, Charsets.UTF_8) else ""
                        }
                        if (text.contains(missing, ignoreCase = true)) Outcome.NOT_FOUND else Outcome.FOUND
                    }
                }
                else -> Outcome.UNSURE // blocked (403/429) or a server error: can't tell
            }
        } catch (_: Exception) {
            Outcome.UNSURE
        } finally {
            connection?.disconnect()
        }
    }
}
