package com.example.face

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Asks the free XposedOrNot service which known data breaches include an email address.
 * The address is sent to that service; the screen says so before it is used.
 */
object BreachChecker {
    sealed class Result {
        data class Found(val breaches: List<String>) : Result()
        object None : Result()
        data class Error(val message: String) : Result()
    }

    private val adultKeywords = listOf(
        "adult", "porn", "xxx", "sex", "brazzers", "ashley", "friendfinder", "fling", "onlyfans", "chaturbate",
        "xhamster", "xvideos", "redtube", "stripchat", "camsoda", "fetlife", "livejasmin", "nsfw", "erotic"
    )

    /** True when a breach name looks like an adult site, so those can be pointed out separately. */
    fun isAdult(breachName: String): Boolean = adultKeywords.any { breachName.contains(it, ignoreCase = true) }

    data class BreachDetail(val name: String, val exposedData: List<String>, val year: String)

    /**
     * The fuller picture for one email: per breach, which kinds of data it exposed (password,
     * phone, address, etc.), not just the breach's name. Uses the same free service, a second,
     * separate request (its own rate limit applies), so call this only after [check] found something.
     */
    fun analyticsDetail(email: String): List<BreachDetail> {
        var c: HttpURLConnection? = null
        return try {
            c = (URL("https://api.xposedornot.com/v1/breach-analytics?email=" + URLEncoder.encode(email, "UTF-8")).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 12000
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "FaceSelfCheck/1.0")
            }
            if (c.responseCode != 200) return emptyList()
            val body = JSONObject(c.inputStream.bufferedReader().readText())
            val details = body.optJSONObject("ExposedBreaches")?.optJSONArray("breaches_details") ?: return emptyList()
            (0 until details.length()).map { i ->
                val item = details.getJSONObject(i)
                BreachDetail(
                    name = item.optString("breach", "Unknown"),
                    exposedData = item.optString("xposed_data", "").split(';').map { it.trim() }.filter { it.isNotEmpty() },
                    year = item.optString("xposed_date", "")
                )
            }
        } catch (_: Exception) {
            emptyList()
        } finally { c?.disconnect() }
    }

    /**
     * Checks a password against Have I Been Pwned's Pwned Passwords service using k-anonymity:
     * only the first 5 characters of its SHA-1 hash are sent, never the password itself or its full hash.
     * Returns how many times this exact password has been seen in known breach dumps (0 = not seen there).
     */
    fun checkPassword(password: String): Int {
        val hash = MessageDigest.getInstance("SHA-1").digest(password.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02X".format(it) }
        val prefix = hash.take(5)
        val suffix = hash.substring(5)
        var c: HttpURLConnection? = null
        return try {
            c = (URL("https://api.pwnedpasswords.com/range/$prefix").openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 10000
                setRequestProperty("Add-Padding", "true")
            }
            if (c.responseCode != 200) return -1
            c.inputStream.bufferedReader().useLines { lines ->
                lines.firstOrNull { it.startsWith(suffix, ignoreCase = true) }
                    ?.substringAfter(':')?.trim()?.toIntOrNull() ?: 0
            }
        } catch (_: Exception) {
            -1
        } finally { c?.disconnect() }
    }

    fun check(email: String): Result {
        var c: HttpURLConnection? = null
        return try {
            c = (URL("https://api.xposedornot.com/v1/check-email/" + URLEncoder.encode(email, "UTF-8")).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 12000
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "FaceSelfCheck/1.0")
            }
            when (val code = c.responseCode) {
                404 -> Result.None
                429 -> Result.Error("The breach service is rate-limiting requests. Try again in a minute.")
                200 -> {
                    val body = c.inputStream.bufferedReader().readText()
                    val names = mutableListOf<String>()
                    collect(JSONObject(body).opt("breaches"), names)
                    if (names.isEmpty()) Result.None else Result.Found(names.distinct())
                }
                else -> Result.Error("The breach service answered with code $code.")
            }
        } catch (e: Exception) {
            Result.Error("Could not reach the breach service: ${e.localizedMessage ?: e.javaClass.simpleName}")
        } finally { c?.disconnect() }
    }

    /** The service nests breach names in arrays; flatten whatever shape comes back. */
    private fun collect(value: Any?, out: MutableList<String>) {
        when (value) {
            is JSONArray -> for (i in 0 until value.length()) collect(value.opt(i), out)
            is String -> if (value.isNotBlank()) out += value
        }
    }
}
