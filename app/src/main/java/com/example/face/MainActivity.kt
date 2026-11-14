package com.example.face

import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch

/** Home screen: the deep face search, the exposure check, and a few settings. */
class MainActivity : AppCompatActivity() {

    private val notificationPermission = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            AppSettings.setReminderEnabled(this, true)
            ScanReminderWorker.schedule(this)
        } else {
            AppSettings.setReminderEnabled(this, false)
            reminderSwitch?.isChecked = false
            Toast.makeText(this, "Allow notifications to get the weekly reminder.", Toast.LENGTH_LONG).show()
        }
    }
    private var reminderSwitch: MaterialSwitch? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(28))
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(page, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        setContentView(scroll)
        // Android 15+ draws apps edge to edge, so keep content clear of the system bars.
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        page.addView(TextView(this).apply {
            text = "Face"
            textSize = 30f
            setPadding(0, 0, 0, dp(6))
        })
        page.addView(TextView(this).apply {
            text = "Check where your own face, email, usernames and domain show up online."
            textSize = 15f
            setPadding(0, 0, 0, dp(20))
        })

        addTile(page, "Deep face search", "Choose photos of yourself and scan public image sources for matches.") {
            startActivity(Intent(this, WebScanActivity::class.java))
        }
        addTile(page, "Exposure check", "Email breaches, username profiles, domain security and data-broker opt-outs.") {
            startActivity(Intent(this, ExposureCheckActivity::class.java))
        }

        addSection(page, "Settings")
        addSwitch(page, "Ask for my screen lock when opening the app", AppSettings.lockEnabled(this)) { toggle, on ->
            val keyguard = getSystemService(KEYGUARD_SERVICE) as android.app.KeyguardManager
            if (on && !keyguard.isDeviceSecure) {
                toggle.isChecked = false
                Toast.makeText(this, "Set a screen lock (PIN, pattern or fingerprint) in your phone settings first.", Toast.LENGTH_LONG).show()
            } else {
                AppSettings.setLockEnabled(this, on)
            }
        }
        addSwitch(page, "Only scan on Wi-Fi", AppSettings.wifiOnly(this)) { _, on -> AppSettings.setWifiOnly(this, on) }
        reminderSwitch = addSwitch(page, "Remind me weekly to run a deep search", AppSettings.reminderEnabled(this)) { _, on ->
            if (!on) {
                AppSettings.setReminderEnabled(this, false)
                ScanReminderWorker.cancel(this)
            } else if (android.os.Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            } else {
                AppSettings.setReminderEnabled(this, true)
                ScanReminderWorker.schedule(this)
            }
        }

        showLastCrashIfAny()
    }

    /** If the app closed unexpectedly last time, show the error so it can be copied and sent for a fix. */
    private fun showLastCrashIfAny() {
        val report = CrashReport.take(this) ?: return
        try {
            MaterialAlertDialogBuilder(this)
                .setTitle("The app closed last time")
                .setMessage(report)
                .setPositiveButton("Copy") { _, _ ->
                    val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Crash report", report))
                    Toast.makeText(this, "Copied.", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Close", null)
                .show()
        } catch (_: Exception) {
            // The report is only a debugging aid; never let it cause another crash.
        }
    }

    private fun addSwitch(container: ViewGroup, text: String, checked: Boolean, onChange: (MaterialSwitch, Boolean) -> Unit): MaterialSwitch {
        val toggle = MaterialSwitch(this).apply {
            this.text = text
            isChecked = checked
            setPadding(dp(8), dp(10), dp(8), dp(10))
            setOnCheckedChangeListener { _, isOn -> onChange(this, isOn) }
        }
        container.addView(toggle, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        return toggle
    }

    private fun addSection(container: ViewGroup, text: String) {
        val view = layoutInflater.inflate(R.layout.item_section, container, false) as TextView
        view.text = text
        container.addView(view)
    }

    private fun addTile(container: ViewGroup, title: String, body: String, onClick: () -> Unit) {
        val tile = layoutInflater.inflate(R.layout.item_tile, container, false)
        tile.findViewById<TextView>(R.id.title).text = title
        tile.findViewById<TextView>(R.id.body).text = body
        tile.setOnClickListener { onClick() }
        container.addView(tile)
    }
}
