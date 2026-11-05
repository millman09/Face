package com.example.face

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView

/**
 * A short, ordered guide for getting an image of you taken down.
 * Nothing is uploaded from this app: each step opens the official site in your browser.
 */
class RemovalGuideActivity : AppCompatActivity() {

    private data class Step(val title: String, val body: String, val button: String? = null, val url: String? = null)

    private val steps = listOf(
        Step(
            "Keep a record",
            "Before anything is removed, screenshot the page and copy its address. You will need both for reports."
        ),
        Step(
            "Block copies from spreading",
            "If the image was shared without your consent and you were 18 or older, StopNCII makes a fingerprint on your phone so partner platforms can block copies. The image never leaves your device.",
            "Open StopNCII", "https://stopncii.org"
        ),
        Step(
            "If you were under 18 in the image",
            "NCMEC's Take It Down works the same way, on your device, for images from before you turned 18.",
            "Open Take It Down", "https://takeitdown.ncmec.org"
        ),
        Step(
            "Report it to the site",
            "Use the site's own report or takedown form. Most list a contact for privacy, DMCA or consent removal. Include the page address and your screenshot."
        ),
        Step(
            "Remove it from Google Search",
            "Ask Google to stop showing explicit or intimate images of you in search results.",
            "Open Google's form", "https://support.google.com/websearch/answer/13650142"
        ),
        Step(
            "Watch for it coming back",
            "Set an alert for your name or usernames so you hear about new pages.",
            "Open Google Alerts", "https://www.google.com/alerts"
        )
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(12))
        }
        setToolPage("Removal guide", page)

        steps.forEachIndexed { index, step ->
            val inner = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(12), dp(16), dp(12))
            }
            inner.addView(TextView(this).apply {
                text = "${index + 1}. ${step.title}"
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleSmall)
            })
            inner.addView(TextView(this).apply {
                text = step.body
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
                setPadding(0, dp(4), 0, 0)
            })
            if (step.button != null && step.url != null) {
                inner.addView(MaterialButton(this).apply {
                    text = step.button
                    isAllCaps = false
                    setOnClickListener { open(step.url) }
                }, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(8) })
            }
            val card = MaterialCardView(this, null, com.google.android.material.R.attr.materialCardViewFilledStyle)
            card.addView(inner)
            page.addSpaced(card, 6)
        }
        page.addView(TextView(this).apply {
            text = "If someone is threatening you or you feel unsafe, contact your local law enforcement."
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
            setPadding(0, dp(8), 0, 0)
        })
    }

    private fun open(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, url, Toast.LENGTH_LONG).show()
        }
    }
}
