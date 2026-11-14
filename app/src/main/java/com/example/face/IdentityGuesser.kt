package com.example.face

/**
 * Generates likely username/email patterns from one of the other, so the exposure checks can be
 * run against those guesses too. These are guesses, never a confirmed link between an email and an
 * account: nothing here is probed or verified, it only produces candidates for the existing checks
 * (the WhatsMyName username scan, and the breach/password checks) to run against.
 */
object IdentityGuesser {

    /** Likely usernames derived from an email's local part (before the @ and any +tag). */
    fun usernamesFromEmail(email: String): List<String> {
        val at = email.indexOf('@')
        val local = (if (at >= 0) email.substring(0, at) else email)
            .substringBefore('+').trim().lowercase()
        if (local.isEmpty()) return emptyList()

        val out = linkedSetOf<String>()
        out += local
        out += local.replace(".", "").replace("_", "").replace("-", "")

        val parts = local.split('.', '_', '-').filter { it.isNotBlank() }
        if (parts.size >= 2) {
            val first = parts.first()
            val last = parts.last()
            out += first + last
            out += first + "." + last
            out += first + "_" + last
            out += first + "-" + last
            out += first.take(1) + last
            out += first + last.take(1)
            out += last + first
            out += last + "." + first
            out += last.take(1) + first
            // A couple of digits are an extremely common suffix when the bare name was already taken.
            out += first + last + "1"
            out += first + last + "123"
        }
        return out.filter { UsernameChecker.clean(it) != null }.take(12)
    }

    private val emailProviders = listOf("gmail.com", "outlook.com", "yahoo.com", "icloud.com", "protonmail.com", "hotmail.com")

    /** Likely email addresses people form from a username, one per major provider. Unverified guesses only. */
    fun emailsFromUsername(username: String): List<String> {
        val local = username.trim().removePrefix("@").lowercase()
        if (local.isEmpty()) return emptyList()
        return emailProviders.map { "$local@$it" }
    }
}
