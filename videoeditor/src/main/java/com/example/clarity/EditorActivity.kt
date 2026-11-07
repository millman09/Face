package com.example.clarity

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.core.os.BundleCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.slider.RangeSlider
import com.google.android.material.slider.Slider
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * The whole editor: open a video, preview it with live enhancements, tap Auto
 * Fix or a preset, fine-tune with sliders, trim, and export to the gallery.
 */
@OptIn(UnstableApi::class)
class EditorActivity : AppCompatActivity() {

    /** One adjustment slider and how it reads and writes [EnhanceSettings]. */
    private class Adjustment(
        val label: String,
        val min: Float,
        val read: (EnhanceSettings) -> Float,
        val write: (EnhanceSettings, Float) -> EnhanceSettings,
    ) {
        lateinit var slider: Slider
    }

    private val adjustments = listOf(
        "Clean up" to listOf(
            Adjustment("Denoise", 0f, { it.denoise }, { s, v -> s.copy(denoise = v) }),
            Adjustment("Sharpen", 0f, { it.sharpen }, { s, v -> s.copy(sharpen = v) }),
        ),
        "Light" to listOf(
            Adjustment("Exposure", -100f, { it.exposure }, { s, v -> s.copy(exposure = v) }),
            Adjustment("Contrast", -100f, { it.contrast }, { s, v -> s.copy(contrast = v) }),
            Adjustment("Shadows", -100f, { it.shadows }, { s, v -> s.copy(shadows = v) }),
            Adjustment("Highlights", -100f, { it.highlights }, { s, v -> s.copy(highlights = v) }),
        ),
        "Color" to listOf(
            Adjustment("Saturation", -100f, { it.saturation }, { s, v -> s.copy(saturation = v) }),
            Adjustment("Vibrance", -100f, { it.vibrance }, { s, v -> s.copy(vibrance = v) }),
            Adjustment("Warmth", -100f, { it.warmth }, { s, v -> s.copy(warmth = v) }),
            Adjustment("Tint", -100f, { it.tint }, { s, v -> s.copy(tint = v) }),
        ),
    )

    @Volatile private var settings = EnhanceSettings()
    @Volatile private var showOriginal = false

    private var videoUri: Uri? = null
    private var info: VideoInfo? = null
    private var player: ExoPlayer? = null
    private val worker = Executors.newSingleThreadExecutor()
    private val exporter by lazy { VideoExporter(this) }
    private var exportDialog: AlertDialog? = null
    private var exportProgress: LinearProgressIndicator? = null

    private lateinit var playerView: PlayerView
    private lateinit var emptyState: View
    private lateinit var controls: LinearLayout
    private lateinit var autoFixButton: MaterialButton
    private lateinit var autoFixSpinner: CircularProgressIndicator
    private lateinit var autoFixCard: MaterialCardView
    private lateinit var autoFixNotes: TextView
    private lateinit var trimSlider: RangeSlider
    private lateinit var trimLabel: TextView
    private lateinit var upscaleSwitch: MaterialSwitch
    private lateinit var removeAudioSwitch: MaterialSwitch
    private lateinit var exportButton: MaterialButton

    private val pickVideo =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null) openVideo(uri)
        }

    private val storagePermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startExport()
            else toast("Storage access is needed to save the video on this Android version.")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildScreen())

        val restored = savedInstanceState?.let {
            BundleCompat.getParcelable(it, KEY_URI, Uri::class.java)
        }
        val incoming = restored ?: incomingVideo(intent)
        if (incoming != null) openVideo(incoming) else showEmpty(true)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        incomingVideo(intent)?.let { openVideo(it) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        videoUri?.let { outState.putParcelable(KEY_URI, it) }
    }

    override fun onStart() {
        super.onStart()
        createPlayer()
    }

    override fun onStop() {
        super.onStop()
        player?.release()
        player = null
        playerView.player = null
    }

    override fun onDestroy() {
        super.onDestroy()
        exporter.cancel()
        worker.shutdownNow()
    }

    // ---------------------------------------------------------------- Video

    private fun incomingVideo(intent: Intent?): Uri? = when (intent?.action) {
        Intent.ACTION_SEND ->
            IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
        Intent.ACTION_VIEW, Intent.ACTION_EDIT -> intent.data
        else -> null
    }

    private fun openVideo(uri: Uri) {
        worker.execute {
            val probed = VideoProbe.info(this, uri)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (probed == null || probed.durationMs <= 0) {
                    toast("That video couldn't be opened.")
                    return@runOnUiThread
                }
                videoUri = uri
                info = probed
                settings = EnhanceSettings()
                syncSliders()
                autoFixCard.visibility = View.GONE
                setUpTrim(probed)
                setUpUpscale(probed)
                showEmpty(false)
                player?.let { loadInto(it) }
            }
        }
    }

    private fun createPlayer() {
        val p = ExoPlayer.Builder(this).build()
        // The enhancement runs on the GPU for every frame, so the preview shows
        // exactly what the export will look like.
        p.setVideoEffects(listOf(EnhanceEffect { if (showOriginal) ORIGINAL else settings }))
        p.repeatMode = Player.REPEAT_MODE_ONE
        playerView.player = p
        player = p
        loadInto(p)
    }

    private fun loadInto(p: ExoPlayer) {
        val uri = videoUri ?: return
        p.setMediaItem(MediaItem.fromUri(uri))
        p.prepare()
        p.seekTo(trimSlider.values.firstOrNull()?.let { (it * 1000).toLong() } ?: 0L)
    }

    /** When paused, re-draw the current frame so slider changes show immediately. */
    private fun refreshPreview() {
        val p = player ?: return
        if (!p.isPlaying && p.playbackState != Player.STATE_IDLE) {
            p.seekTo(p.currentPosition)
        }
    }

    // ---------------------------------------------------------------- Auto fix

    private fun runAutoFix() {
        val uri = videoUri ?: return
        val i = info ?: return
        val (startMs, endMs) = trimRangeMs(i)
        autoFixButton.isEnabled = false
        autoFixSpinner.visibility = View.VISIBLE
        worker.execute {
            val frames = VideoProbe.sampleFrames(this, uri, startMs, endMs)
            val result = if (frames.isEmpty()) null else AutoFix.analyze(frames)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                autoFixButton.isEnabled = true
                autoFixSpinner.visibility = View.GONE
                if (result == null) {
                    toast("Couldn't read frames from this video.")
                    return@runOnUiThread
                }
                settings = result.settings
                syncSliders()
                refreshPreview()
                autoFixNotes.text = if (result.notes.isEmpty()) {
                    "This video already looks good. Only small touch-ups were needed."
                } else {
                    result.notes.joinToString("\n") { "•  $it" }
                }
                autoFixCard.visibility = View.VISIBLE
            }
        }
    }

    private fun applyPreset(preset: EnhanceSettings) {
        // Presets set the look but keep Auto Fix's measured exposure and color corrections.
        val current = settings
        settings = preset.copy(
            autoBlack = current.autoBlack,
            autoWhite = current.autoWhite,
            autoGamma = current.autoGamma,
            autoGainR = current.autoGainR,
            autoGainG = current.autoGainG,
            autoGainB = current.autoGainB,
        )
        syncSliders()
        refreshPreview()
    }

    private fun resetAll() {
        settings = EnhanceSettings()
        autoFixCard.visibility = View.GONE
        syncSliders()
        refreshPreview()
    }

    private fun syncSliders() {
        for ((_, group) in adjustments) {
            for (a in group) a.slider.value = a.read(settings).coerceIn(a.min, 100f)
        }
    }

    // ---------------------------------------------------------------- Export

    private fun onExportClicked() {
        if (videoUri == null) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }
        startExport()
    }

    private fun startExport() {
        val uri = videoUri ?: return
        val i = info ?: return
        val (startMs, endMs) = trimRangeMs(i)
        player?.pause()

        val progress = LinearProgressIndicator(this).apply {
            isIndeterminate = true
            max = 100
        }
        val status = TextView(this).apply {
            text = "Enhancing every frame. Keep the app open."
            setPadding(0, 0, 0, dp(12))
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(status)
            addView(progress)
        }
        exportProgress = progress
        exportDialog = MaterialAlertDialogBuilder(this)
            .setTitle("Exporting")
            .setView(body)
            .setCancelable(false)
            .setNegativeButton("Cancel") { _, _ -> exporter.cancel(); exportDialog = null }
            .show()

        exporter.start(
            uri, i, settings,
            ExportOptions(
                startMs = startMs,
                endMs = endMs,
                upscale = upscaleSwitch.isEnabled && upscaleSwitch.isChecked,
                removeAudio = removeAudioSwitch.isChecked
            ),
            object : VideoExporter.Callback {
                override fun onProgress(percent: Int) {
                    exportProgress?.let {
                        if (it.isIndeterminate) it.isIndeterminate = false
                        it.setProgressCompat(percent, true)
                    }
                }

                override fun onSaved(uri: Uri) {
                    if (exportDialog == null || isFinishing) return
                    exportDialog?.dismiss()
                    exportDialog = null
                    MaterialAlertDialogBuilder(this@EditorActivity)
                        .setTitle("Saved")
                        .setMessage("Your enhanced video is in Movies › Clarity.")
                        .setPositiveButton("Done", null)
                        .setNeutralButton("Share") { _, _ -> share(uri) }
                        .show()
                }

                override fun onFailed(message: String) {
                    if (exportDialog == null || isFinishing) return
                    exportDialog?.dismiss()
                    exportDialog = null
                    MaterialAlertDialogBuilder(this@EditorActivity)
                        .setTitle("Export failed")
                        .setMessage(message)
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        )
    }

    private fun share(uri: Uri) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, "Share video"))
    }

    // ---------------------------------------------------------------- Trim & output

    private fun setUpTrim(i: VideoInfo) {
        val seconds = i.durationMs / 1000f
        trimSlider.valueFrom = 0f
        trimSlider.valueTo = seconds
        trimSlider.values = listOf(0f, seconds)
        updateTrimLabel()
    }

    private fun trimRangeMs(i: VideoInfo): Pair<Long, Long> {
        val v = trimSlider.values
        if (v.size < 2) return 0L to i.durationMs
        val start = (v[0] * 1000).toLong().coerceIn(0L, i.durationMs)
        val end = (v[1] * 1000).toLong().coerceIn(start + 100, i.durationMs.coerceAtLeast(start + 100))
        return start to end
    }

    private fun updateTrimLabel() {
        val v = trimSlider.values
        if (v.size < 2) return
        trimLabel.text = "Trim  ${clock(v[0])} – ${clock(v[1])}  (${clock(v[1] - v[0])})"
    }

    private fun setUpUpscale(i: VideoInfo) {
        if (i.shortSide in 1 until VideoExporter.HD_SHORT_SIDE) {
            upscaleSwitch.isEnabled = true
            upscaleSwitch.text = "Enhance resolution to 1080p (from ${i.shortSide}p)"
        } else {
            upscaleSwitch.isEnabled = false
            upscaleSwitch.isChecked = false
            upscaleSwitch.text = "Enhance resolution (already ${i.shortSide}p HD)"
        }
    }

    // ---------------------------------------------------------------- Layout

    private fun buildScreen(): View {
        val toolbar = MaterialToolbar(this).apply {
            title = "Clarity"
            subtitle = "Clean up & enhance video"
            menu.add("Open").apply {
                setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
                setOnMenuItemClickListener { pickNewVideo(); true }
            }
        }

        playerView = PlayerView(this).apply {
            setShutterBackgroundColor(Color.BLACK)
            setBackgroundColor(Color.BLACK)
        }
        emptyState = buildEmptyState()
        val compareHint = TextView(this).apply {
            text = "Original"
            setTextColor(Color.WHITE)
            setBackgroundColor(0x99000000.toInt())
            setPadding(dp(10), dp(4), dp(10), dp(4))
            visibility = View.GONE
        }
        val preview = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(playerView, FrameLayout.LayoutParams(MATCH, MATCH))
            addView(emptyState, FrameLayout.LayoutParams(MATCH, MATCH))
            addView(
                compareHint,
                FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.START).apply {
                    setMargins(dp(12), dp(12), 0, 0)
                }
            )
        }

        controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(24))
        }
        buildControls(controls, compareHint)

        val scroll = ScrollView(this).apply {
            clipToPadding = false
            addView(controls, ViewGroup.LayoutParams(MATCH, WRAP))
        }

        exportButton = MaterialButton(this).apply {
            text = "Export enhanced video"
            setOnClickListener { onExportClicked() }
        }
        val bottomBar = FrameLayout(this).apply {
            setBackgroundColor(ContextCompat.getColor(context, R.color.clarity_surface_high))
            setPadding(dp(16), dp(8), dp(16), dp(8))
            addView(exportButton, FrameLayout.LayoutParams(MATCH, WRAP))
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(toolbar, LinearLayout.LayoutParams(MATCH, WRAP))
            addView(preview, LinearLayout.LayoutParams(MATCH, 0, 0.42f))
            addView(scroll, LinearLayout.LayoutParams(MATCH, 0, 0.58f))
            addView(bottomBar, LinearLayout.LayoutParams(MATCH, WRAP))
        }
        // Android 15+ draws edge to edge, so keep content clear of the system bars.
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        return root
    }

    private fun buildEmptyState(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setBackgroundColor(ContextCompat.getColor(context, R.color.clarity_surface))
        setPadding(dp(24), dp(24), dp(24), dp(24))
        addView(TextView(context).apply {
            text = "Make any video clearer"
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
        })
        addView(TextView(context).apply {
            text = "Remove grain, fix dark or washed-out footage, correct color and sharpen detail."
            setTextColor(ContextCompat.getColor(context, R.color.clarity_on_surface_muted))
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(16))
        })
        addView(MaterialButton(context).apply {
            text = "Choose a video"
            setOnClickListener { pickNewVideo() }
        }, LinearLayout.LayoutParams(WRAP, WRAP))
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildControls(column: LinearLayout, compareHint: TextView) {
        // Auto fix row
        autoFixButton = MaterialButton(this).apply {
            text = "✨  Auto fix"
            setOnClickListener { runAutoFix() }
        }
        autoFixSpinner = CircularProgressIndicator(this).apply {
            isIndeterminate = true
            indicatorSize = dp(24)
            visibility = View.GONE
        }
        val compare = MaterialButton(
            this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            text = "Hold to compare"
            contentDescription = "Hold to show the original video"
            setOnTouchListener { v, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        showOriginal = true
                        compareHint.visibility = View.VISIBLE
                        v.isPressed = true
                        refreshPreview()
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        showOriginal = false
                        compareHint.visibility = View.GONE
                        v.isPressed = false
                        refreshPreview()
                    }
                }
                true
            }
        }
        column.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(autoFixButton, LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(autoFixSpinner, LinearLayout.LayoutParams(WRAP, WRAP).apply {
                setMargins(dp(8), 0, dp(8), 0)
            })
            addView(compare, LinearLayout.LayoutParams(0, WRAP, 1f).apply {
                marginStart = dp(8)
            })
        })

        // What Auto fix did
        autoFixNotes = TextView(this).apply {
            setPadding(dp(16), dp(4), dp(16), dp(14))
            setLineSpacing(0f, 1.2f)
        }
        autoFixCard = MaterialCardView(this).apply {
            visibility = View.GONE
            setCardBackgroundColor(ContextCompat.getColor(context, R.color.clarity_container))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(context).apply {
                    text = "Auto fix applied"
                    setTypeface(typeface, Typeface.BOLD)
                    setPadding(dp(16), dp(14), dp(16), 0)
                })
                addView(autoFixNotes)
            })
        }
        column.addView(autoFixCard, LinearLayout.LayoutParams(MATCH, WRAP).apply {
            topMargin = dp(12)
        })

        // Presets
        column.addView(sectionTitle("Quick looks"))
        val presets = ChipGroup(this)
        listOf(
            "Clean up" to EnhanceSettings.CLEAN,
            "Low light" to EnhanceSettings.LOW_LIGHT,
            "Vivid" to EnhanceSettings.VIVID,
            "Cinematic" to EnhanceSettings.CINEMATIC,
        ).forEach { (name, preset) ->
            presets.addView(Chip(this).apply {
                text = name
                setOnClickListener { applyPreset(preset) }
            })
        }
        presets.addView(Chip(this).apply {
            text = "Reset to original"
            setOnClickListener { resetAll() }
        })
        column.addView(presets)

        // Adjustment sliders
        for ((title, group) in adjustments) {
            column.addView(sectionTitle(title))
            for (a in group) column.addView(adjustmentRow(a))
        }

        // Trim and output
        column.addView(sectionTitle("Trim & output"))
        trimLabel = TextView(this)
        trimSlider = RangeSlider(this).apply {
            valueFrom = 0f
            valueTo = 1f
            values = listOf(0f, 1f)
            setLabelFormatter { clock(it) }
            addOnChangeListener { slider, _, fromUser ->
                updateTrimLabel()
                if (fromUser) player?.seekTo((slider.values[0] * 1000).toLong())
            }
        }
        column.addView(trimLabel)
        column.addView(trimSlider)
        upscaleSwitch = MaterialSwitch(this).apply { text = "Enhance resolution to 1080p" }
        removeAudioSwitch = MaterialSwitch(this).apply { text = "Remove sound" }
        column.addView(upscaleSwitch)
        column.addView(removeAudioSwitch)
    }

    private fun adjustmentRow(a: Adjustment): View {
        val name = TextView(this).apply { text = a.label }
        val valueText = TextView(this).apply {
            setTextColor(ContextCompat.getColor(context, R.color.clarity_on_surface_muted))
        }
        a.slider = Slider(this).apply {
            valueFrom = a.min
            valueTo = 100f
            stepSize = 1f
            value = 0f
            setLabelFormatter { it.roundToInt().toString() }
            addOnChangeListener { _, v, fromUser ->
                valueText.text = v.roundToInt().toString()
                if (fromUser) {
                    settings = a.write(settings, v)
                    refreshPreview()
                }
            }
        }
        valueText.text = "0"
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(4), 0, 0)
            addView(name, LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(valueText)
        }
        // Tap the name to reset just this slider.
        name.setOnClickListener {
            if (!a.slider.isEnabled) return@setOnClickListener
            a.slider.value = 0f
            settings = a.write(settings, 0f)
            refreshPreview()
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(header)
            addView(a.slider)
        }
    }

    private fun sectionTitle(text: String) = TextView(this).apply {
        this.text = text
        textSize = 16f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(ContextCompat.getColor(context, R.color.clarity_primary))
        setPadding(0, dp(20), 0, dp(6))
    }

    private fun showEmpty(empty: Boolean) {
        emptyState.visibility = if (empty) View.VISIBLE else View.GONE
        playerView.visibility = if (empty) View.INVISIBLE else View.VISIBLE
        setControlsEnabled(!empty)
    }

    private fun setControlsEnabled(enabled: Boolean) {
        controls.alpha = if (enabled) 1f else 0.4f
        setEnabledDeep(controls, enabled)
        exportButton.isEnabled = enabled
        if (enabled) info?.let { setUpUpscale(it) }
    }

    private fun setEnabledDeep(view: View, enabled: Boolean) {
        view.isEnabled = enabled
        if (view is ViewGroup) for (i in 0 until view.childCount) setEnabledDeep(view.getChildAt(i), enabled)
    }

    private fun pickNewVideo() {
        pickVideo.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
        )
    }

    private fun clock(seconds: Float): String {
        val total = seconds.roundToInt().coerceAtLeast(0)
        return "%d:%02d".format(total / 60, total % 60)
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private companion object {
        const val KEY_URI = "video_uri"
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        val ORIGINAL = EnhanceSettings()
    }
}
