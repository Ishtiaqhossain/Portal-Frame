package com.portalhacks.frame

import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.ImageFormat
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.hardware.Camera
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.text.TextUtils
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import java.util.EnumMap

/**
 * On-device setup, shown as the "Photos" app icon. Styled with the Portal design
 * system ([Ui]: dark theme, platform palette, Inter type, room-distance hit
 * targets, centred max-width column, reserved top overlay inset). Three screens:
 *
 *  - Status/settings: album currently playing, how to add one, slideshow settings.
 *  - Scanner: live camera QR scan (validated, written to [ConfigReceiver.KEY_ALBUM]).
 *  - Manual entry: type/paste the link with the on-screen keyboard (QR-less fallback).
 *
 * All persist to the same `portalframe` prefs the slideshow reads
 * ([ConfigReceiver]). Uses the legacy [Camera] API for compactness on
 * this fixed API-29 device.
 */
@Suppress("DEPRECATION")
class PhotosActivity : Activity() {

    private val main = Handler(Looper.getMainLooper())
    private val reader = QRCodeReader()

    private lateinit var root: FrameLayout

    // Scanner state
    private var camera: Camera? = null
    private var surface: SurfaceView? = null
    private var scanHint: TextView? = null

    @Volatile
    private var scanning = false
    private var stopArmed = false // "Stop showing photos" two-tap confirm
    private var showingStatus = false // re-check screensaver state on return
    private var previewW = 0
    private var previewH = 0 // cached so we don't hit getParameters() per frame
    private var frameCount = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        root = FrameLayout(this)
        root.setBackgroundColor(Ui.BG)
        setContentView(root)
        // The Compose SettingsActivity hands off here for the camera / manual flows.
        val gotoExtra = intent?.getStringExtra("goto")
        when (gotoExtra) {
            "scan" -> startScan()
            "manual" -> showManualEntry()
            else -> showStatus()
        }
    }

    override fun onResume() {
        super.onResume()
        // Returning from the system Screen-saver picker: refresh the status so the
        // "Screensaver" card reflects the new selection.
        if (showingStatus) {
            showStatus()
        }
    }

    override fun onPause() {
        super.onPause()
        stopCamera()
    }

    // ------------------------------------------------ screensaver setup

    /** True if Portal Frame is the enabled system screensaver. */
    private fun isOurScreensaver(): Boolean {
        return try {
            val enabled = Settings.Secure.getInt(
                contentResolver, "screensaver_enabled", 0,
            ) == 1
            val comp = Settings.Secure.getString(contentResolver, "screensaver_components")
            enabled && comp != null && comp.contains(packageName)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Deep-link to the system Screen-saver picker, where the user selects
     * "Portal Frame". A normal app can't set the screensaver itself (it needs
     * WRITE_SECURE_SETTINGS), but the Settings app can — so this is the no-ADB path.
     */
    private fun openScreensaverSettings() {
        try {
            startActivity(Intent(Settings.ACTION_DREAM_SETTINGS))
        } catch (e: Exception) {
            toast("Open Settings → Display → Screen saver, then choose Frame")
        }
    }

    // ---------------------------------------------------------------- prefs

    private fun prefs(): SharedPreferences =
        getSharedPreferences(ConfigReceiver.PREFS, MODE_PRIVATE)

    private fun album(): String = prefs().getString(ConfigReceiver.KEY_ALBUM, "") ?: ""

    private fun getDelay(): Long =
        prefs().getLong(ConfigReceiver.KEY_DELAY_MS, ConfigReceiver.DEFAULT_DELAY_MS)

    private fun getFade(): Long =
        prefs().getLong(ConfigReceiver.KEY_FADE_MS, ConfigReceiver.DEFAULT_FADE_MS)

    private fun getShuffle(): Boolean = prefs().getBoolean(ConfigReceiver.KEY_SHUFFLE, false)

    private fun getPairs(): Boolean =
        prefs().getBoolean(ConfigReceiver.KEY_PAIRS, ConfigReceiver.DEFAULT_PAIRS)

    // ------------------------------------------------------- Status / settings

    private fun showStatus() {
        stopCamera()
        stopArmed = false
        showingStatus = true
        root.removeAllViews()
        val col = Ui.screen(this, root, Ui.MAX_W_WIDE_DP)

        val url = album()
        val hasAlbum = url.isNotEmpty()

        col.addView(Ui.title(this, if (hasAlbum) "Your photos" else "Show your Google Photos"))

        val panes = Ui.twoColumns(this, col)
        val left = panes[0]
        val right = panes[1]

        // ---------------------------------------------------- LEFT: Source
        // Screensaver — the slideshow only runs once "Portal Frame" is chosen in
        // the system picker (a normal app can't set this itself).
        val ssActive = isOurScreensaver()
        val ssCard = Ui.card(this)
        ssCard.addView(Ui.sectionLabel(this, "Screensaver"))
        val ssBody = Ui.body(
            this,
            if (ssActive) {
                "✓ Frame is your screensaver. Your photos appear when the Portal is idle."
            } else {
                "Almost there — tap below, then choose “Frame” so your photos show " +
                    "when the Portal is idle."
            },
        )
        topMargin(ssBody, 6)
        ssCard.addView(ssBody)
        ssCard.addView(
            if (ssActive) {
                Ui.outline(this, "Change screensaver") { openScreensaverSettings() }
            } else {
                Ui.primary(this, "Use as screensaver") { openScreensaverSettings() }
            },
        )
        left.addView(ssCard)

        // Album
        val albumCard = Ui.card(this)
        albumCard.addView(Ui.sectionLabel(this, if (hasAlbum) "Album" else "No album yet"))
        if (hasAlbum) {
            val u = Ui.body(this, url)
            u.setTextColor(Ui.BLUE)
            u.setSingleLine(true)
            u.ellipsize = TextUtils.TruncateAt.MIDDLE
            topMargin(u, 6)
            albumCard.addView(u)
        } else {
            val none = Ui.body(
                this,
                "Add a Google Photos shared album to show your own photos.",
            )
            topMargin(none, 6)
            albumCard.addView(none)
        }
        albumCard.addView(
            Ui.primary(this, if (hasAlbum) "Change album" else "Add album") { startScan() },
        )
        albumCard.addView(
            Ui.outline(this, "Enter link manually") { showManualEntry() },
        )
        if (hasAlbum) {
            val stop = Ui.destructive(this, "Stop showing photos", null)
            stop.setOnClickListener {
                if (!stopArmed) {
                    stopArmed = true
                    stop.text = "Tap again to confirm"
                } else {
                    prefs().edit().remove(ConfigReceiver.KEY_ALBUM).apply()
                    toast("Showing sample photos")
                    showStatus()
                }
            }
            albumCard.addView(stop)
        }
        left.addView(albumCard)

        // ------------------------------------------------- RIGHT: Slideshow
        val slideCard = Ui.card(this)
        slideCard.addView(Ui.sectionLabel(this, "Slideshow"))

        val delayRow = Ui.row(this, "Seconds per photo", fmtDelay(getDelay()), null)
        delayRow.setOnClickListener {
            val next = cycle(DELAY_CHOICES, getDelay(), 0)
            prefs().edit().putLong(ConfigReceiver.KEY_DELAY_MS, next).apply()
            Ui.setRowValue(delayRow, fmtDelay(next))
        }
        topMargin(delayRow, 8)
        slideCard.addView(delayRow)
        slideCard.addView(Ui.hairline(this))

        val shuffleRow = Ui.row(this, "Shuffle photos", if (getShuffle()) "On" else "Off", null)
        shuffleRow.setOnClickListener {
            val next = !getShuffle()
            prefs().edit().putBoolean(ConfigReceiver.KEY_SHUFFLE, next).apply()
            Ui.setRowValue(shuffleRow, if (next) "On" else "Off")
        }
        slideCard.addView(shuffleRow)
        slideCard.addView(Ui.hairline(this))

        val fadeRow = Ui.row(this, "Transition", fadeLabel(getFade()), null)
        fadeRow.setOnClickListener {
            val next = cycle(FADE_CHOICES, getFade(), 1)
            prefs().edit().putLong(ConfigReceiver.KEY_FADE_MS, next).apply()
            Ui.setRowValue(fadeRow, fadeLabel(next))
        }
        slideCard.addView(fadeRow)
        slideCard.addView(Ui.hairline(this))

        val pairsRow = Ui.row(this, "Side-by-side portraits", if (getPairs()) "On" else "Off", null)
        pairsRow.setOnClickListener {
            val next = !getPairs()
            prefs().edit().putBoolean(ConfigReceiver.KEY_PAIRS, next).apply()
            Ui.setRowValue(pairsRow, if (next) "On" else "Off")
        }
        slideCard.addView(pairsRow)
        slideCard.addView(Ui.hairline(this))
        slideCard.addView(
            boolRow(
                "Cinematic motion",
                ConfigReceiver.KEY_KEN_BURNS, ConfigReceiver.DEFAULT_KEN_BURNS,
            ),
        )
        slideCard.addView(Ui.hairline(this))
        slideCard.addView(
            boolRow(
                "Photo captions",
                ConfigReceiver.KEY_CAPTIONS, ConfigReceiver.DEFAULT_CAPTIONS,
            ),
        )
        right.addView(slideCard)

        // Ambient & on-device AI demos
        val aiCard = Ui.card(this)
        aiCard.addView(Ui.sectionLabel(this, "Ambient intelligence"))
        val faceRow = boolRow(
            "Face-aware framing",
            ConfigReceiver.KEY_FACE, ConfigReceiver.DEFAULT_FACE,
        )
        topMargin(faceRow, 8)
        aiCard.addView(faceRow)
        aiCard.addView(Ui.hairline(this))
        aiCard.addView(
            boolRow(
                "Auto-enhance photos",
                ConfigReceiver.KEY_ENHANCE, ConfigReceiver.DEFAULT_ENHANCE,
            ),
        )
        aiCard.addView(Ui.hairline(this))
        aiCard.addView(
            boolRow(
                "Ambient color glow",
                ConfigReceiver.KEY_AMBIENT, ConfigReceiver.DEFAULT_AMBIENT,
            ),
        )
        aiCard.addView(Ui.hairline(this))
        aiCard.addView(
            boolRow(
                "Clock & weather",
                ConfigReceiver.KEY_CLOCK, ConfigReceiver.DEFAULT_CLOCK,
            ),
        )
        aiCard.addView(Ui.hairline(this))
        aiCard.addView(
            boolRow(
                "Night warmth",
                ConfigReceiver.KEY_NIGHT, ConfigReceiver.DEFAULT_NIGHT,
            ),
        )
        aiCard.addView(Ui.hairline(this))
        aiCard.addView(
            boolRow(
                "On This Day memories",
                ConfigReceiver.KEY_ON_THIS_DAY, ConfigReceiver.DEFAULT_ON_THIS_DAY,
            ),
        )
        right.addView(aiCard)

        // Tips
        val tipsCard = Ui.card(this)
        tipsCard.addView(Ui.sectionLabel(this, "Tips"))
        val howto = Ui.body(
            this,
            "On your phone: open Google Photos, open the album you want, tap Share, and " +
                "choose Create link / show its QR code. Then tap " +
                (if (hasAlbum) "Change album" else "Add album") +
                " here and hold your phone up to this screen.\n\n" +
                "Tip: the album must be shared by link so the frame can see it.",
        )
        topMargin(howto, 6)
        tipsCard.addView(howto)
        right.addView(tipsCard)

        // ----------------------------------------------------------- Done
        val done = Ui.secondary(this, "Done") { finish() }
        topMargin(done, 24)
        col.addView(done)
    }

    private fun topMargin(v: View, dp: Int) {
        var lp = v.layoutParams as? LinearLayout.LayoutParams
        if (lp == null) {
            lp = LinearLayout.LayoutParams(MATCH, WRAP)
        }
        lp.topMargin = Ui.dp(this, dp.toFloat())
        v.layoutParams = lp
    }

    /** A tappable On/Off row bound to a boolean pref. */
    private fun boolRow(label: String, key: String, def: Boolean): View {
        val row = Ui.row(this, label, if (prefs().getBoolean(key, def)) "On" else "Off", null)
        row.setOnClickListener {
            val next = !prefs().getBoolean(key, def)
            prefs().edit().putBoolean(key, next).apply()
            Ui.setRowValue(row, if (next) "On" else "Off")
        }
        return row
    }

    // ------------------------------------------------ Manual entry

    /** Type/paste the album link using the on-screen keyboard (QR-less fallback). */
    private fun showManualEntry() {
        stopCamera()
        stopArmed = false
        showingStatus = false
        root.removeAllViews()
        val col = Ui.screen(this, root)

        col.addView(Ui.title(this, "Enter album link"))
        val help = Ui.body(
            this,
            "Paste or type the Google Photos shared-album link (it starts with " +
                "https://photos.app.goo.gl/ or https://photos.google.com/share/).",
        )
        topMargin(help, 12)
        col.addView(help)

        val edit = Ui.field(this, "https://photos.app.goo.gl/…")
        edit.setSingleLine(true)
        edit.inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_VARIATION_URI or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        edit.setText(album())
        edit.setSelection(edit.text.length)
        col.addView(edit)

        col.addView(
            Ui.primary(this, "Save") {
                val url = edit.text.toString().trim()
                if (!isPhotosLink(url)) {
                    toast("That doesn't look like a Google Photos link")
                } else {
                    prefs().edit().putString(ConfigReceiver.KEY_ALBUM, url).apply()
                    Log.i(TAG, "album_url set via manual entry: $url")
                    hideKeyboard(edit)
                    toast("Album set ✓")
                    finish() // back to the Compose settings screen (sticky Done + preview)
                }
            },
        )
        col.addView(
            Ui.secondary(this, "Cancel") {
                hideKeyboard(edit)
                finish()
            },
        )

        // Pop the on-screen keyboard for the field.
        edit.requestFocus()
        edit.post {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(edit, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun hideKeyboard(v: View) {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(v.windowToken, 0)
    }

    // ------------------------------------------------------ Scanner

    private fun startScan() {
        if (checkSelfPermission(android.Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.CAMERA), REQ_CAMERA)
            return
        }
        showScanner()
    }

    override fun onRequestPermissionsResult(
        req: Int,
        perms: Array<out String>,
        results: IntArray,
    ) {
        if (req == REQ_CAMERA) {
            if (results.isNotEmpty() && results[0] == PackageManager.PERMISSION_GRANTED) {
                showScanner()
            } else {
                toast("Camera permission is needed to scan the QR code")
                finish()
            }
        }
    }

    private fun showScanner() {
        stopArmed = false
        showingStatus = false
        root.removeAllViews()

        val f = FrameLayout(this)
        f.setBackgroundColor(Color.BLACK)

        val surface = SurfaceView(this)
        this.surface = surface
        f.addView(surface, FrameLayout.LayoutParams(MATCH, MATCH))

        val boxSize = Ui.dp(this, 340f)

        // Black out everything except the centred box, so the camera preview only lights up
        // that window (less glare in the room) and the user's eye goes to the target. Drawn as
        // the view's *background* (always drawn) so it reliably composites over the camera
        // SurfaceView — the same path the blue box border uses.
        val mask = View(this)
        mask.background = object : Drawable() {
            private val paint = Paint().apply { color = Color.BLACK }
            override fun draw(canvas: Canvas) {
                val r = bounds
                val half = boxSize / 2
                val l = (r.centerX() - half).toFloat()
                val t = (r.centerY() - half).toFloat()
                val rt = (r.centerX() + half).toFloat()
                val bt = (r.centerY() + half).toFloat()
                canvas.drawRect(r.left.toFloat(), r.top.toFloat(), r.right.toFloat(), t, paint)
                canvas.drawRect(r.left.toFloat(), bt, r.right.toFloat(), r.bottom.toFloat(), paint)
                canvas.drawRect(r.left.toFloat(), t, l, bt, paint)
                canvas.drawRect(rt, t, r.right.toFloat(), bt, paint)
            }
            override fun setAlpha(alpha: Int) {}
            override fun setColorFilter(colorFilter: ColorFilter?) {}
            @Deprecated("required override", ReplaceWith("PixelFormat.TRANSLUCENT"))
            override fun getOpacity() = PixelFormat.TRANSLUCENT
        }
        f.addView(mask, FrameLayout.LayoutParams(MATCH, MATCH))

        // Centred target box (Portal blue) so the user knows where to aim the QR.
        val box = View(this)
        val border = Ui.roundRect(0x00000000, Ui.dp(this, 20f))
        border.setStroke(Ui.dp(this, 4f), Ui.BLUE)
        box.background = border
        val bp = FrameLayout.LayoutParams(boxSize, boxSize)
        bp.gravity = Gravity.CENTER
        f.addView(box, bp)

        val scanHint = TextView(this)
        this.scanHint = scanHint
        scanHint.text = "Make the QR fill your phone screen at full brightness, then hold it " +
            "about half a meter away — sharp and filling the blue box. Hold steady."
        scanHint.setTextColor(0xFFF0F0F0.toInt())
        scanHint.typeface = Ui.medium(this)
        scanHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        scanHint.gravity = Gravity.CENTER_HORIZONTAL
        scanHint.setLineSpacing(Ui.dp(this, 4f).toFloat(), 1f)
        scanHint.setShadowLayer(8f, 0f, 1f, Color.BLACK)
        val hp = FrameLayout.LayoutParams(Ui.dp(this, 620f), WRAP)
        hp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        hp.topMargin = Ui.dp(this, 72f) // clear the top system overlay
        f.addView(scanHint, hp)

        val typeLink = Ui.secondary(this, "Can't scan? Type the link") { showManualEntry() }
        val tp = FrameLayout.LayoutParams(WRAP, WRAP)
        tp.gravity = Gravity.BOTTOM or Gravity.START
        tp.bottomMargin = Ui.dp(this, 28f)
        tp.leftMargin = Ui.dp(this, 28f)
        f.addView(typeLink, tp)

        val cancel = Ui.secondary(this, "Cancel") { finish() }
        val cp = FrameLayout.LayoutParams(WRAP, WRAP)
        cp.gravity = Gravity.BOTTOM or Gravity.END
        cp.bottomMargin = Ui.dp(this, 28f)
        cp.rightMargin = Ui.dp(this, 28f)
        f.addView(cancel, cp)

        root.addView(f)

        surface.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(h: SurfaceHolder) {
                openCamera(h)
            }

            override fun surfaceChanged(h: SurfaceHolder, fmt: Int, w: Int, height: Int) {
            }

            override fun surfaceDestroyed(h: SurfaceHolder) {
                stopCamera()
            }
        })
    }

    private fun openCamera(holder: SurfaceHolder) {
        try {
            val id = pickCamera()
            val camera = Camera.open(id)
            this.camera = camera
            val pr = camera.parameters

            // Largest supported preview (cap ~1920 wide to bound per-frame CPU) — more pixels
            // give a small/distant QR enough modules to decode on this fixed-focus camera.
            var best: Camera.Size? = null
            for (s in pr.supportedPreviewSizes) {
                if (s.width <= 1920 && (best == null || s.width > best.width)) {
                    best = s
                }
            }
            if (best != null) {
                pr.setPreviewSize(best.width, best.height)
            }
            pr.previewFormat = ImageFormat.NV21
            // Camera is fixed-focus (no autofocus on Portal); a moderate zoom enlarges a
            // far-but-in-focus QR so it carries more pixels.
            if (pr.isZoomSupported) {
                val z = Math.max(1, pr.maxZoom / 3)
                pr.zoom = z
            }
            // A bright phone screen blows out to white if AE meters the whole dim room. Meter
            // the centre box (where the QR is) and bias exposure down so the QR is readable
            // immediately instead of after AE slowly adapts.
            if (pr.maxNumMeteringAreas >= 1) {
                val areas = ArrayList<Camera.Area>()
                areas.add(Camera.Area(Rect(-350, -350, 350, 350), 1000))
                pr.meteringAreas = areas
            }
            val step = pr.exposureCompensationStep
            var comp = if (step > 0) Math.round(-2.0f / step) else pr.minExposureCompensation
            comp = Math.max(pr.minExposureCompensation, Math.min(pr.maxExposureCompensation, comp))
            pr.exposureCompensation = comp

            // Autofocus makes a dense QR sharp — the single biggest factor for decoding. The
            // camera was *assumed* fixed-focus; prefer continuous focus when it's actually offered.
            val focusModes = pr.supportedFocusModes
            when {
                focusModes?.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE) == true ->
                    pr.focusMode = Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE
                focusModes?.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO) == true ->
                    pr.focusMode = Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO
                focusModes?.contains(Camera.Parameters.FOCUS_MODE_AUTO) == true ->
                    pr.focusMode = Camera.Parameters.FOCUS_MODE_AUTO
            }
            camera.parameters = pr
            Log.i(
                TAG,
                "QR cam caps: focusModes=" + focusModes + " using=" + pr.focusMode +
                    " preview=" + pr.previewSize.width + "x" + pr.previewSize.height + " maxPicture=" +
                    pr.supportedPictureSizes.maxByOrNull { it.width * it.height }
                        ?.let { "${it.width}x${it.height}" } +
                    " hFov=" + pr.horizontalViewAngle + " zoom=" + pr.zoom + "/" + pr.maxZoom,
            )

            val ps = camera.parameters.previewSize
            previewW = ps.width
            previewH = ps.height
            frameCount = 0

            camera.setDisplayOrientation(0) // preview is feedback only; decode uses raw data
            camera.setPreviewDisplay(holder)
            scanning = true
            camera.setPreviewCallback(previewCb)
            camera.startPreview()
            Log.i(
                TAG,
                "camera opened id=" + id + " preview=" + previewW + "x" + previewH +
                    " zoomSupported=" + pr.isZoomSupported,
            )
        } catch (e: Exception) {
            Log.e(TAG, "camera open failed", e)
            toast("Couldn't open the camera")
            finish()
        }
    }

    private val previewCb = Camera.PreviewCallback { data, _ ->
        if (scanning && data != null && previewW != 0) {
            frameCount++
            if (frameCount == 1) {
                Log.i(
                    TAG,
                    "QR scan: first frame " + previewW + "x" + previewH +
                        " bytes=" + data.size,
                )
            }
            if (frameCount and 1 == 0) {
                // decode every other frame to keep the multi-pass cost down
                return@PreviewCallback
            }
            val text = tryDecode(data, previewW, previewH)
            if (text != null) {
                scanning = false // stop processing further frames until we decide
                main.post { onQr(text) }
            } else if (frameCount % 30 == 1) {
                Log.i(TAG, "QR scan: " + frameCount + " frames, no QR decoded yet")
            }
        }
    }

    /**
     * Try to decode a QR from an NV21 luma plane. The Portal's front camera is
     * fixed-focus and may deliver mirrored frames, so we try: full frame normal,
     * full frame mirrored, then a centered crop (more relative resolution for a
     * small/central code) normal + mirrored. Returns the text or null.
     */
    private fun tryDecode(data: ByteArray, w: Int, h: Int): String? {
        decodeRegion(data, w, h, 0, 0, w, h, false)?.let { return it }
        decodeRegion(data, w, h, 0, 0, w, h, true)?.let { return it }
        // Centred crops: a QR aimed at the box covers only the middle of this ultra-wide
        // frame, so cropping in gives it more pixels per module. Try a couple of sizes.
        for (frac in floatArrayOf(0.6f, 0.4f)) {
            val side = (Math.min(w, h) * frac).toInt()
            val left = (w - side) / 2
            val top = (h - side) / 2
            decodeRegion(data, w, h, left, top, side, side, false)?.let { return it }
            decodeRegion(data, w, h, left, top, side, side, true)?.let { return it }
        }
        return null
    }

    private fun decodeRegion(
        data: ByteArray,
        w: Int,
        h: Int,
        left: Int,
        top: Int,
        rw: Int,
        rh: Int,
        mirror: Boolean,
    ): String? {
        val src = try {
            PlanarYUVLuminanceSource(data, w, h, left, top, rw, rh, mirror)
        } catch (e: Exception) {
            return null
        }
        // Hybrid binarizer is best for sharp images; the global-histogram one sometimes wins on
        // the soft, low-contrast frames this fixed-focus wide camera produces. Try both.
        for (bin in arrayOf(HybridBinarizer(src), GlobalHistogramBinarizer(src))) {
            try {
                return reader.decode(BinaryBitmap(bin), HINTS).text
            } catch (notFound: Exception) {
                // try the next binarizer
            } finally {
                reader.reset()
            }
        }
        return null
    }

    private fun onQr(text: String?) {
        val url = text?.trim() ?: ""
        if (!isPhotosLink(url)) {
            scanHint?.text = "That QR isn't a Google Photos album link — try again"
            scanning = true // keep scanning
            return
        }
        prefs().edit().putString(ConfigReceiver.KEY_ALBUM, url).apply()
        Log.i(TAG, "album_url set via QR: $url")
        stopCamera()
        scanHint?.text = "Album set ✓ — your photos will appear shortly"
        toast("Album set ✓")
        main.postDelayed({ finish() }, 1500)
    }

    private fun stopCamera() {
        scanning = false
        camera?.let { c ->
            try {
                c.setPreviewCallback(null)
                c.stopPreview()
                c.release()
            } catch (ignored: Exception) {
                // best-effort teardown
            }
            camera = null
        }
    }

    private fun toast(m: String) {
        Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val TAG = "PortalFrame"
        private const val REQ_CAMERA = 1
        private const val SCREENSAVER_COMPONENT =
            "com.portalhacks.frame/com.portalhacks.frame.FrameDreamService"

        private val DELAY_CHOICES = longArrayOf(4000L, 6000L, 10000L, 30000L, 60000L)
        private val FADE_CHOICES = longArrayOf(2000L, 1200L, 500L) // Slow, Normal, Fast
        private val FADE_LABELS = arrayOf("Slow", "Normal", "Fast")

        private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

        private val HINTS: Map<DecodeHintType, Any> =
            EnumMap<DecodeHintType, Any>(DecodeHintType::class.java).apply {
                put(DecodeHintType.TRY_HARDER, java.lang.Boolean.TRUE)
            }

        private fun cycle(choices: LongArray, cur: Long, fallback: Int): Long {
            for (i in choices.indices) {
                if (choices[i] == cur) {
                    return choices[(i + 1) % choices.size]
                }
            }
            return choices[fallback]
        }

        private fun fadeLabel(ms: Long): String {
            for (i in FADE_CHOICES.indices) {
                if (FADE_CHOICES[i] == ms) {
                    return FADE_LABELS[i]
                }
            }
            return "Normal"
        }

        private fun fmtDelay(ms: Long): String {
            val s = ms / 1000
            return if (s >= 60) "${s / 60}m" else "${s}s"
        }

        private fun pickCamera(): Int {
            val n = Camera.getNumberOfCameras()
            val info = Camera.CameraInfo()
            for (i in 0 until n) {
                Camera.getCameraInfo(i, info)
                if (info.facing == Camera.CameraInfo.CAMERA_FACING_BACK) {
                    return i
                }
            }
            return 0 // Portal has only a front camera, which faces the user holding the phone
        }

        private fun isPhotosLink(s: String): Boolean =
            s.startsWith("https://photos.app.goo.gl/") ||
                s.startsWith("https://photos.google.com/share/")
    }
}
