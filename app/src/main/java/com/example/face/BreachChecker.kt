package com.example.face

import org.json.JSONArray
import org.json.JSONObject
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
