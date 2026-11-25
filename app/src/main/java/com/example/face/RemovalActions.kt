package com.example.face

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** The "Get this removed" menu for a match, shared by the scan screen and the saved-images gallery. */
object RemovalActions {
    fun show(activity: Activity, pageUrl: String, imageUrl: String) {
        val labels = arrayOf(
            "Email the site a removal request",
            "Copy removal request text",
            "StopNCII.org (block reposts on major platforms)",
            "Report to Google (remove from search)",
            "Copy image link", "Copy page link",
        )
        MaterialAlertDialogBuilder(activity)
            .setTitle("Get this removed")
            .setItems(labels) { _, which ->
                when (which) {
                    0 -> emailRemovalRequest(activity, pageUrl, imageUrl)
                    1 -> copyText(activity, "Removal request", removalRequestText(pageUrl, imageUrl))
                    2 -> openUrl(activity, "https://stopncii.org")
                    3 -> openUrl(activity, "https://support.google.com/websearch/answer/13650142")
                    4 -> copyText(activity, "Image link", imageUrl)
                    else -> copyText(activity, "Page link", pageUrl.ifBlank { imageUrl })
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    /** Site domain without "www.", e.g. "example.com". */
    private fun siteDomain(pageUrl: String, imageUrl: String): String =
        (Uri.parse(pageUrl.ifBlank { imageUrl }).host ?: "").lowercase(java.util.Locale.US).removePrefix("www.")

    fun removalRequestText(pageUrl: String, imageUrl: String): String = buildString {
        appendLine("Hello,")
        appendLine()
        appendLine("I am the person shown in an image on your site, and I did not consent to it being posted. Please remove it and any copies:")
        appendLine()
        if (pageUrl.isNotBlank()) appendLine("Page: $pageUrl")
        appendLine("Image: $imageUrl")
        appendLine()
        appendLine("Please confirm once it has been removed.")
        appendLine()
        append("Thank you.")
    }

    /**
     * Opens the user's email app with the request filled in, addressed to the site's standard abuse mailbox
     * (abuse@domain). Many sites read that mailbox; if a site lists its own removal contact, the user can
     * change the address before sending.
     */
    private fun emailRemovalRequest(activity: Activity, pageUrl: String, imageUrl: String) {
        val domain = siteDomain(pageUrl, imageUrl)
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")).apply {
            if (domain.isNotBlank()) putExtra(Intent.EXTRA_EMAIL, arrayOf("abuse@$domain"))
            putExtra(Intent.EXTRA_SUBJECT, "Removal request: image posted without my consent")
            putExtra(Intent.EXTRA_TEXT, removalRequestText(pageUrl, imageUrl))
        }
        try {
            activity.startActivity(intent)
            Toast.makeText(activity, "Check the address before sending. If the site lists its own removal contact, use that.", Toast.LENGTH_LONG).show()
        } catch (_: Exception) {
            copyText(activity, "Removal request", removalRequestText(pageUrl, imageUrl))
            Toast.makeText(activity, "No email app found. The request was copied so you can paste it into the site's contact form.", Toast.LENGTH_LONG).show()
        }
    }


    fun copyText(activity: Activity, label: String, text: String) {
        val clipboard = activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText(label, text))
        Toast.makeText(activity, "$label copied.", Toast.LENGTH_SHORT).show()
    }

    fun openUrl(activity: Activity, address: String) {
        val uri = Uri.parse(address)
        if (uri.scheme !in listOf("https", "http") || uri.host.isNullOrBlank() || uri.userInfo != null) {
            Toast.makeText(activity, "This link is not a valid public web address.", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (_: Exception) {
            Toast.makeText(activity, "No browser is available to open this link.", Toast.LENGTH_SHORT).show()
        }
    }
}
