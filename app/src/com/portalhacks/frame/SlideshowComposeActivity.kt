package com.portalhacks.frame

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat

/**
 * The live slideshow screensaver, hosted in Jetpack Compose.
 *
 * The slideshow's rendering is a deeply imperative, custom-animated View stack —
 * crossfading [android.widget.ImageView]s, a [android.animation.ValueAnimator]
 * Ken Burns engine, a `Canvas` shimmer, an `ImageSpan` weather glyph, distance-based
 * touch gestures — exactly the case Compose's `AndroidView` interop exists for. So we
 * keep the battle-tested [SlideshowController] and bridge it into `setContent`, rather
 * than re-deriving the animation engine in Compose (which would risk regressing the
 * marquee features for no user-facing gain). The album fetch/cache/refresh and
 * night-dimming logic live here in Kotlin.
 *
 * This is the screensaver target ([FrameDreamService] launches it).
 */
class SlideshowComposeActivity : ComponentActivity() {

    private lateinit var loader: ImageLoader
    private lateinit var controller: SlideshowController
    private val handler = Handler(Looper.getMainLooper())

    private var currentAlbums: List<String> = emptyList()
    private var currentIds: List<String> = ArrayList()

    // "AirDrop for Portal": photos arrive via the always-on DropServerService, which
    // broadcasts ACTION_UPLOAD when one lands so we can show it immediately.
    private var uploadReceiverRegistered = false
    private val uploadReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            onLocalUploadChanged()
        }
    }

    private val sensorManager by lazy { getSystemService(SENSOR_SERVICE) as SensorManager }
    private val lightSensor: Sensor? by lazy { sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT) }

    // Low-light "clock only" mode (mirrors the Portal night-mode option). When enabled and the
    // room is dark, drop to a clock-only screen; restore the photos when the light returns.
    private val lightListener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            val lux = e.values.firstOrNull() ?: return
            when {
                lux <= LOW_LUX -> controller.setClockOnly(true)
                lux >= HIGH_LUX -> controller.setClockOnly(false)
            }
        }
        override fun onAccuracyChanged(s: Sensor?, accuracy: Int) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                or WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                or WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD,
        )
        // Honor the Portal's own brightness: don't override the window brightness, so the
        // system's adaptive/manual brightness (and its light sensor) governs the frame.
        window.attributes = window.attributes.apply {
            screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }

        loader = ImageLoader(this)
        // Run the LAN photo-drop server as an always-on foreground service so phones can
        // push photos anytime, not just while this slideshow is on screen.
        DropServerService.start(this)
        // Build the slideshow's View hierarchy, then host it inside Compose.
        val root = FrameLayout(this)
        controller = SlideshowController(this, root, loader).apply {
            setOnDismiss {
                // Tap dismisses the screensaver. The dream launched us in our own task
                // (FLAG_ACTIVITY_NEW_TASK) on top of whatever app the user had open, so
                // just tearing this task down hands control back to that app — matching
                // the stock Portal screensaver. (We used to force CATEGORY_HOME here,
                // which always bounced the user to the launcher instead; see issue #3.)
                finishAndRemoveTask()
            }
            // Portal's launcher won't show sideloaded app icons, so long-press the
            // slideshow to reach the setup/settings screen.
            setOnSettings {
                startActivity(Intent(this@SlideshowComposeActivity, SettingsActivity::class.java))
            }
        }
        setContent {
            AndroidView(factory = { root }, modifier = Modifier.fillMaxSize())
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                )
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // The manifest declares configChanges for orientation, so the Activity (and the hosted
        // SlideshowController) survive a rotation. Tell the controller to recompute its screen
        // dimensions / pairing axis so side-by-side ↔ top/bottom follows the new orientation.
        if (::controller.isInitialized) {
            controller.onScreenConfigChanged()
        }
    }

    override fun onResume() {
        super.onResume()
        // Clear any photo retained from a previous run so re-entering the frame
        // doesn't flash the old image before the first new frame loads.
        controller.blank()
        // Re-apply the clock position/size (picks up a Settings "reset" done while away).
        controller.applyClockTransform()
        val prefs = getSharedPreferences(ConfigReceiver.PREFS, MODE_PRIVATE)
        // Pick up a sticky note set over ADB / Settings while we were away.
        controller.setNote(prefs.getString(ConfigReceiver.KEY_NOTE, "") ?: "")

        // "Only show clock in low light": watch the ambient light sensor when enabled.
        sensorManager.unregisterListener(lightListener)
        val low = lightSensor
        if (prefs.getBoolean(ConfigReceiver.KEY_CLOCK_LOW_LIGHT, ConfigReceiver.DEFAULT_CLOCK_LOW_LIGHT) &&
            low != null
        ) {
            sensorManager.registerListener(lightListener, low, SensorManager.SENSOR_DELAY_NORMAL)
        } else {
            controller.setClockOnly(false)
        }

        currentAlbums = Albums.enabled(prefs)
        DropServerService.start(this)
        if (!uploadReceiverRegistered) {
            ContextCompat.registerReceiver(
                this, uploadReceiver, IntentFilter(DropServerService.ACTION_UPLOAD),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            uploadReceiverRegistered = true
        }

        // Photos pushed onto the frame over the LAN always play, alongside any albums.
        val local = LocalUploads.slides(this)
        val cached = currentAlbums.flatMap { AlbumCache.read(prefs, it) ?: emptyList() }
        val initial = cached + local

        if (currentAlbums.isEmpty() && local.isEmpty()) {
            // Nothing configured and nothing pushed: show the bundled samples.
            controller.start()
            return
        }

        // Start straight from the caches + pushed photos if we have any (disk-cached images
        // make the first photo appear near-instantly); otherwise a black "Loading…" screen —
        // never the samples. When there are phone-pushed photos, open on the newest one so a
        // freshly-added photo greets you on resume instead of being buried mid-rotation.
        if (initial.isNotEmpty()) {
            currentIds = idsOf(initial)
            val newestLocal = local.firstOrNull()?.id
            if (newestLocal != null) {
                controller.setItemsShowing(initial, newestLocal, instant = true)
            } else {
                controller.setItems(initial)
            }
        } else {
            currentIds = ArrayList()
            controller.setStatusHint("Loading photos…")
        }

        // Refresh now, then keep checking periodically while we're on screen.
        fetchAllAndApply(initial.isEmpty())
        handler.removeCallbacks(refreshTick)
        handler.postDelayed(refreshTick, REFRESH_INTERVAL_MS)
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(lightListener)
        handler.removeCallbacks(refreshTick)
        // Leave DropServerService running so photos can still arrive while we're away.
        if (uploadReceiverRegistered) {
            unregisterReceiver(uploadReceiver)
            uploadReceiverRegistered = false
        }
        controller.stop()
    }

    /**
     * A phone just pushed photo(s) over the LAN — fold them into the slideshow and jump
     * straight to the newest one (the in-room "tada"), instead of waiting for the rotation.
     */
    private fun onLocalUploadChanged() {
        if (!::controller.isInitialized) return
        val prefs = getSharedPreferences(ConfigReceiver.PREFS, MODE_PRIVATE)
        val local = LocalUploads.slides(this)
        val merged = currentAlbums.flatMap { AlbumCache.read(prefs, it) ?: emptyList() } + local
        if (merged.isEmpty()) return
        currentIds = idsOf(merged)
        val newest = local.firstOrNull()?.id
        if (newest != null) {
            // Show the just-pushed photo immediately (crossfades to it).
            controller.setItemsShowing(merged, newest)
        } else {
            controller.setItems(merged)
        }
    }

    private val refreshTick = object : Runnable {
        override fun run() {
            fetchAllAndApply(false)
            handler.postDelayed(this, REFRESH_INTERVAL_MS)
        }
    }

    /**
     * Fetch every configured album in the background; cache each and re-apply the merged
     * photo set as each one lands (only when the set actually changed, to avoid flicker).
     */
    private fun fetchAllAndApply(showHint: Boolean) {
        val albums = currentAlbums
        if (albums.isEmpty()) {
            return
        }
        val prefs = getSharedPreferences(ConfigReceiver.PREFS, MODE_PRIVATE)
        for (url in albums) {
            loader.executor().execute {
                try {
                    val album = PhotoSources.fetch(url)
                    if (album.slides.isNotEmpty()) {
                        AlbumCache.write(prefs, url, album.slides, album.title)
                    }
                    runOnUiThread { rebuildFromCaches(showHint) }
                } catch (e: Exception) {
                    Log.e(TAG, "album fetch failed: $url", e)
                    if (showHint) {
                        runOnUiThread { rebuildFromCaches(true) }
                    }
                }
            }
        }
    }

    /** Recompute the slideshow from all albums' caches and apply it if it changed. */
    private fun rebuildFromCaches(showHint: Boolean) {
        val prefs = getSharedPreferences(ConfigReceiver.PREFS, MODE_PRIVATE)
        if (currentAlbums != Albums.enabled(prefs)) {
            return // the playing album set changed while fetching
        }
        val merged = currentAlbums.flatMap { AlbumCache.read(prefs, it) ?: emptyList() } +
            LocalUploads.slides(this)
        if (merged.isEmpty()) {
            if (showHint) controller.setStatusHint("Couldn't load photos — retrying later")
            return
        }
        val ids = idsOf(merged)
        if (ids != currentIds) {
            currentIds = ids
            // Preserve whatever photo is showing (e.g. a just-pushed upload) across the
            // rebuild instead of snapping back to index 0 when an album fetch changes the set.
            val cur = controller.currentId()
            if (cur != null && ids.contains(cur)) {
                controller.setItemsShowing(merged, cur, instant = true)
            } else {
                controller.setItems(merged)
            }
        }
    }

    companion object {
        private const val TAG = "PortalFrame"
        private const val REFRESH_INTERVAL_MS = 20 * 60 * 1000L // 20 min

        // Lux thresholds for clock-only mode, with hysteresis to avoid flicker near the edge.
        private const val LOW_LUX = 8f
        private const val HIGH_LUX = 25f

        private fun idsOf(slides: List<Slide>): List<String> {
            val ids = ArrayList<String>(slides.size)
            for (s in slides) {
                ids.add(s.id)
            }
            return ids
        }
    }
}
