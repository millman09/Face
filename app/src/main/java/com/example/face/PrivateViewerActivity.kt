package com.example.face

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton

/**
 * Opens a result page inside Face, like an incognito tab: nothing goes into your browser's history,
 * no cookies or site data are kept, the page isn't cached, and everything it stored is wiped when you
 * close it. Downloads and links into other apps are blocked.
 */
class PrivateViewerActivity : AppCompatActivity() {
    private lateinit var web: WebView
    private lateinit var address: TextView
    private lateinit var loading: ProgressBar

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val start = intent.getStringExtra(EXTRA_URL).orEmpty()
        if (!isWebAddress(start)) {
            Toast.makeText(this, "This link is not a valid public web address.", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), dp(6), dp(8), dp(6))
            setBackgroundColor(Color.rgb(20, 22, 32))
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        bar.addView(TextView(this).apply {
            text = "✕"
            textSize = 20f
            setTextColor(Color.WHITE)
            setPadding(dp(10), dp(4), dp(14), dp(4))
            contentDescription = "Close private view"
            setOnClickListener { finish() }
        })
        val labels = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        labels.addView(TextView(this).apply {
            text = "Private view · nothing is saved"
            textSize = 12f
            setTextColor(Color.rgb(173, 160, 255))
        })
        address = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.rgb(210, 214, 225))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        labels.addView(address)
        bar.addView(labels, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(MaterialButton(this, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
            text = "Browser"
            isAllCaps = false
            contentDescription = "Open this page in your browser instead (not private)"
            setOnClickListener { openInBrowser() }
        })

        loading = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }

        // Fresh start: no leftovers from an earlier private view.
        wipeSiteData()
        web = WebView(this).apply {
            settings.javaScriptEnabled = true // most image and video pages need it to show anything
            settings.domStorageEnabled = true // wiped when the viewer closes
            settings.cacheMode = WebSettings.LOAD_NO_CACHE
            @Suppress("DEPRECATION")
            settings.saveFormData = false
            settings.setSupportMultipleWindows(false)
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    // Stay on the web inside this viewer; never hand off to other apps (intent:, market:, ...).
                    return !isWebAddress(request.url.toString())
                }
                override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                    address.text = url
                    loading.visibility = View.VISIBLE
                }
                override fun onPageFinished(view: WebView, url: String) {
                    address.text = url
                    loading.visibility = View.GONE
                }
            }
            webChromeClient = object : android.webkit.WebChromeClient() {
                override fun onProgressChanged(view: WebView, newProgress: Int) {
                    loading.progress = newProgress
                }
            }
            // Downloads are ignored on purpose: nothing from these pages lands on the phone.
            setDownloadListener { _, _, _, _, _ ->
                Toast.makeText(this@PrivateViewerActivity, "Downloads are blocked in private view.", Toast.LENGTH_SHORT).show()
            }
        }

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            addView(bar)
            addView(loading, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)))
            addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        })
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (web.canGoBack()) web.goBack() else finish()
            }
        })
        address.text = start
        web.loadUrl(start)
    }

    private fun openInBrowser() {
        val current = web.url ?: intent.getStringExtra(EXTRA_URL).orEmpty()
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(current)))
        } catch (_: Exception) {
            Toast.makeText(this, "No browser is available.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun wipeSiteData() {
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        WebStorage.getInstance().deleteAllData()
    }

    override fun onDestroy() {
        if (::web.isInitialized) {
            web.stopLoading()
            web.clearHistory()
            web.clearCache(true)
            web.clearFormData()
            (web.parent as? ViewGroup)?.removeView(web)
            web.destroy()
        }
        wipeSiteData()
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_URL = "com.example.face.EXTRA_PRIVATE_URL"

        private fun isWebAddress(address: String): Boolean {
            val uri = Uri.parse(address)
            return uri.scheme in listOf("https", "http") && !uri.host.isNullOrBlank() && uri.userInfo == null
        }

        fun open(activity: Activity, address: String) {
            if (!isWebAddress(address)) {
                Toast.makeText(activity, "This link is not a valid public web address.", Toast.LENGTH_SHORT).show()
                return
            }
            activity.startActivity(Intent(activity, PrivateViewerActivity::class.java).putExtra(EXTRA_URL, address))
        }
    }
}
