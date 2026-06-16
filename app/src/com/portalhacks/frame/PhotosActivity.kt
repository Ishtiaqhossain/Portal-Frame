package com.portalhacks.frame

import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Configuration
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
import android.view.Surface
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
 *  - Scanner: live camera QR scan (validated, added to the album list via [Albums]).
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
    private var cameraId = 0 // remembered so onConfigurationChanged can re-orient the preview
    private var surface: SurfaceView? = null
    private var scanHint: TextView? = null

    @Volatile
    private var scanning = false
    private var stopArmed = false // "Stop showing photos" two-tap confirm
    private var showingStatus = false // re-check screensaver state on return
    private var previewW = 0
    private var previewH = 0 // cached so we don't hit getParameters() per frame
    private var frameCount = 0

    // Zoom sweep: cycle a few zoom levels so the AE auto-lands on the one that exposes the QR
    // correctly for whatever phone screen is in front of the camera. Zoom is the only working
    // exposure lever on this HAL (EV is ignored, AE can't be disabled), and the ideal level
    // depends on the phone's screen brightness — so we sweep instead of betting one constant.
    private var zoomLevels: IntArray = intArrayOf()
    private var zoomIdx = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Pan the screen up when the on-screen keyboard appears so the link field stays visible.
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN)
        root = FrameLayout(this)
        root.setBackgroundColor(Ui.BG)
        setContentView(root)
        // The Compose SettingsActivity hands off here for the camera / manual flows.
        val gotoExtra = intent?.getStringExtra("goto")
        when (gotoExtra) {
            "scan" -> startScan()
            "manual" -> startScan() // manual entry now lives inside the scanner panel
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

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // The activity handles orientation changes itself (configChanges in the manifest), so the
        // camera isn't torn down when the Portal screen is rotated — but its preview keeps the
        // orientation it had when it opened, leaving the live preview sideways. Re-apply the
        // display orientation and aspect for the new rotation. (Decode is rotation-agnostic; this
        // is purely the on-screen preview.) Stop/start around setDisplayOrientation since some
        // HALs reject it mid-preview.
        val c = camera ?: return
        try {
            c.stopPreview()
            val orientation = previewDisplayOrientation(cameraId)
            c.setDisplayOrientation(orientation)
            if (previewW != 0 && previewH != 0) {
                sizePreviewToAspect(previewW, previewH, orientation)
            }
            c.startPreview()
        } catch (e: Exception) {
            Log.e(TAG, "re-orient on rotation failed", e)
        }
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

    private fun album(): String = Albums.list(prefs()).firstOrNull() ?: ""

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

        col.addView(Ui.title(this, if (hasAlbum) "Your photos" else "Show your photos"))

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
                "Add a Google Photos or iCloud shared album to show your own photos.",
            )
            topMargin(none, 6)
            albumCard.addView(none)
        }
        albumCard.addView(
            Ui.primary(this, if (hasAlbum) "Change album" else "Add album") { startScan() },
        )
        albumCard.addView(
            Ui.outline(this, "Enter link manually") { startScan() },
        )
        if (hasAlbum) {
            val stop = Ui.destructive(this, "Stop showing photos", null)
            stop.setOnClickListener {
                if (!stopArmed) {
                    stopArmed = true
                    stop.text = "Tap again to confirm"
                } else {
                    Albums.clear(prefs())
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
    /**
     * "Done" on the add screen: add the pasted link (if any) and close. An empty field just
     * closes (e.g. after a QR scan already added one); an invalid link warns and stays.
     */
    private fun addTypedAlbum(edit: EditText) {
        val url = edit.text.toString().trim()
        if (url.isEmpty()) {
            finish()
            return
        }
        if (!isPhotosLink(url)) {
            toast("That doesn't look like a Google Photos or iCloud link")
            return
        }
        Albums.add(prefs(), url)
        Log.i(TAG, "album added via manual entry: $url")
        hideKeyboard(edit)
        toast("Album added ✓")
        finish() // back to the Compose settings screen
    }

    /** A compact, content-width filled button (not full-bleed like the card buttons). */
    private fun pillButton(label: String, fill: Int, textColor: Int, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = label
        b.isAllCaps = false
        b.typeface = Ui.medium(this)
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        b.setTextColor(textColor)
        val h = Ui.dp(this, 56f)
        b.minHeight = h
        b.minimumHeight = h
        b.stateListAnimator = null
        val padH = Ui.dp(this, 36f)
        b.setPadding(padH, 0, padH, 0)
        b.background = Ui.roundRect(fill, Ui.dp(this, 14f))
        b.setOnClickListener { onClick() }
        return b
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

        val boxSize = Ui.dp(this, 300f)
        // Keep the target window near the TOP of the screen, close to the physical camera. On the
        // Portal+ held vertically the lens sits at the top centre, so a centred window leaves the
        // user aiming far below the camera. The mask cut-out and the blue box share this top so
        // they stay aligned; everything else reflows around it (title above, hints below).
        val boxTop = Ui.dp(this, 64f)
        val boxCenterY = boxTop + boxSize / 2

        // Black out everything except the target window, so the camera preview only lights up
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
                val t = (boxCenterY - half).toFloat()
                val rt = (r.centerX() + half).toFloat()
                val bt = (boxCenterY + half).toFloat()
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

        // Top-aligned target box (Portal blue) so the user knows where to aim the QR.
        val box = View(this)
        val border = Ui.roundRect(0x00000000, Ui.dp(this, 20f))
        border.setStroke(Ui.dp(this, 4f), Ui.BLUE)
        box.background = border
        val bp = FrameLayout.LayoutParams(boxSize, boxSize)
        bp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        bp.topMargin = boxTop
        f.addView(box, bp)

        // Centred max-width columns so nothing spans the whole screen.
        val colW = Math.min(Ui.dp(this, 640f), resources.displayMetrics.widthPixels - Ui.dp(this, 48f))

        // Title sits in the slim strip above the top-aligned window.
        val title = TextView(this)
        title.text = "Add an album"
        title.setTextColor(0xFFF0F0F0.toInt())
        title.typeface = Ui.bold(this)
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
        title.gravity = Gravity.CENTER_HORIZONTAL
        title.setShadowLayer(8f, 0f, 1f, Color.BLACK)
        val titleLp = FrameLayout.LayoutParams(colW, WRAP)
        titleLp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        titleLp.topMargin = Ui.dp(this, 16f)
        f.addView(title, titleLp)

        // Instructions / scan feedback go just BELOW the window so the window stays high.
        val subtitle = TextView(this)
        this.scanHint = subtitle // reused for "that QR isn't…/Album added ✓" feedback
        // This lens is fixed-focus at infinity, so a QR is only sharp held back at ~arm's length;
        // up close it blurs and the modules merge. The zoom sweep then enlarges the (now distant)
        // code so it still spans enough pixels to decode.
        subtitle.text = DEFAULT_SCAN_HINT
        subtitle.setTextColor(0xFFD2D2D2.toInt())
        subtitle.typeface = Ui.medium(this)
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        subtitle.gravity = Gravity.CENTER_HORIZONTAL
        subtitle.setLineSpacing(Ui.dp(this, 3f).toFloat(), 1f)
        subtitle.setShadowLayer(8f, 0f, 1f, Color.BLACK)
        val subLp = FrameLayout.LayoutParams(colW, WRAP)
        subLp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        subLp.topMargin = boxTop + boxSize + Ui.dp(this, 24f)
        f.addView(subtitle, subLp)

        // Bottom: paste a link + a compact Done button.
        val bottomCol = LinearLayout(this)
        bottomCol.orientation = LinearLayout.VERTICAL
        val edit = Ui.field(this, "Paste a Google Photos or iCloud link")
        edit.setSingleLine(true)
        edit.inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_VARIATION_URI or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        bottomCol.addView(edit)
        val done = pillButton("Done", Ui.BLUE, 0xFFF0F0F0.toInt()) { addTypedAlbum(edit) }
        val doneLp = LinearLayout.LayoutParams(WRAP, WRAP)
        doneLp.topMargin = Ui.dp(this, 14f)
        doneLp.gravity = Gravity.END
        done.layoutParams = doneLp
        bottomCol.addView(done)
        val bottomLp = FrameLayout.LayoutParams(colW, WRAP)
        bottomLp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        bottomLp.bottomMargin = Ui.dp(this, 40f)
        f.addView(bottomCol, bottomLp)

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
            cameraId = id
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
            val previewSize = best
            pr.previewFormat = ImageFormat.NV21
            // Portal Go's front camera is fixed-focus (HAL confirms focus-mode-values="fixed",
            // afAvailableModes=[OFF]), hyperfocal ~1.16m, and ultra-wide (~96° hFOV) at only
            // 1280x720 — so a QR aimed at the box lands in the centre as just a few dozen pixels.
            //
            // Zoom does double duty here. Obviously it enlarges the QR. Less obviously it fixes the
            // exposure: at low zoom the dark room fills most of this ultra-wide frame, so the AE
            // brightens for the room and blows the screen out. Zoom in hard and the bright screen
            // *fills the frame*, so the AE meters the screen and exposes down — the one lever that
            // actually moves exposure on this HAL (EV is ignored). zoompct is tunable for testing.
            if (pr.isZoomSupported) {
                // A fixed `zoompct` override pins one level (for on-device testing); otherwise
                // sweep a few levels at runtime so the scanner self-tunes the exposure to the
                // phone in front of it rather than relying on a single hardcoded guess.
                // Span wide→tight so the sweep self-tunes across mounts: the high steps (80/100)
                // fill the frame for the close-held Go (exposure), the lower steps (20/50) keep an
                // arm's-length QR in frame on the Portal+ where the lens is fixed at infinity.
                val fixed = intent?.getIntExtra("zoompct", 0) ?: 0
                val pcts = if (fixed > 0) intArrayOf(fixed) else intArrayOf(20, 50, 80, 100)
                zoomLevels = pcts
                    .map { Math.max(1, Math.min(pr.maxZoom, pr.maxZoom * it / 100)) }
                    .distinct()
                    .toIntArray()
                zoomIdx = 0
                pr.zoom = zoomLevels.first()
            }
            // Force the highest fixed frame rate the camera offers. A fixed high FPS caps the
            // exposure *time* the AE is allowed to integrate, so it physically can't over-expose
            // the bright screen by holding the shutter open for the dark room. (Tunable: hifps=0
            // leaves the default auto range.)
            if (intent?.getIntExtra("hifps", 1) != 0) {
                val ranges = pr.supportedPreviewFpsRange // each is [min,max] in fps*1000
                // Prefer the range with the highest minimum (most aggressive exposure-time cap),
                // breaking ties on the highest max.
                val best = ranges?.maxWithOrNull(
                    compareBy({ it[0] }, { it[1] }),
                )
                if (best != null) {
                    pr.setPreviewFpsRange(best[0], best[1])
                }
                Log.i(TAG, "QR cam fps ranges=" + ranges?.joinToString { it.contentToString() })
            }
            // Exposure controls: bias dark so the bright phone screen doesn't blow out as the room
            // (now larger in the wider, low-zoom frame) pulls the AE brighter. Measured safe here —
            // EV -9 left luma mean ~75 (not crushed). Barcode scene mode is preferred when offered
            // (it drives metering itself, so it's used alone); otherwise centre-meter + bias dark.
            val sceneModes = pr.supportedSceneModes
            val usingBarcodeScene =
                sceneModes?.contains(Camera.Parameters.SCENE_MODE_BARCODE) == true
            if (usingBarcodeScene) {
                pr.sceneMode = Camera.Parameters.SCENE_MODE_BARCODE
            } else {
                if (pr.maxNumMeteringAreas >= 1) {
                    val areas = ArrayList<Camera.Area>()
                    areas.add(Camera.Area(Rect(-350, -350, 350, 350), 1000))
                    pr.meteringAreas = areas
                }
                if (pr.minExposureCompensation < 0) {
                    pr.exposureCompensation = pr.minExposureCompensation
                }
            }
            // Phone displays flicker (PWM dimming / panel refresh); let the camera cancel the
            // rolling bands that would otherwise streak across — and corrupt — the code. Harmless
            // alongside either path above (the HAL ignores it if a scene mode already drives it).
            val antibanding = pr.supportedAntibanding
            if (antibanding?.contains(Camera.Parameters.ANTIBANDING_AUTO) == true) {
                pr.antibanding = Camera.Parameters.ANTIBANDING_AUTO
            }

            // The Portal Go camera is genuinely fixed-focus — focus-mode-values reports only
            // "fixed", so none of the autofocus modes below are ever offered. We still probe for
            // them defensively (other Portal models / future firmware may differ); on Go this
            // when-block is a no-op and focus stays at the lens's hyperfocal (~1.16m).
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
                    " sceneMode=" + pr.sceneMode + " ev=" + pr.exposureCompensation +
                    "/[" + pr.minExposureCompensation + ".." + pr.maxExposureCompensation + "]" +
                    " antibanding=" + pr.antibanding +
                    " preview=" + pr.previewSize.width + "x" + pr.previewSize.height + " maxPicture=" +
                    pr.supportedPictureSizes.maxByOrNull { it.width * it.height }
                        ?.let { "${it.width}x${it.height}" } +
                    " hFov=" + pr.horizontalViewAngle + " zoom=" + pr.zoom + "/" + pr.maxZoom +
                    " fpsRange=" + IntArray(2).also { pr.getPreviewFpsRange(it) }.contentToString(),
            )

            val ps = camera.parameters.previewSize
            previewW = ps.width
            previewH = ps.height
            frameCount = 0

            // Orient the *preview* upright for however the Portal screen is mounted (Portal+ can
            // be vertical/portrait, Portal Go is landscape). Feedback only — decode reads raw NV21
            // (sensor-native, unaffected) and tries every rotation — but a sideways, squashed
            // preview makes the QR impossible to aim, so match the display and fit the aspect.
            val orientation = previewDisplayOrientation(id)
            camera.setDisplayOrientation(orientation)
            if (previewSize != null) {
                sizePreviewToAspect(previewSize.width, previewSize.height, orientation)
            }
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

    /**
     * Degrees to rotate the camera preview so it appears upright on the current display, from the
     * device's rotation and the camera's mounted sensor orientation (standard Android formula).
     * Front-facing cameras are mirrored by the framework, so they subtract rather than add.
     */
    private fun previewDisplayOrientation(cameraId: Int): Int {
        val info = Camera.CameraInfo()
        Camera.getCameraInfo(cameraId, info)
        val degrees = when (windowManager.defaultDisplay.rotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        return if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
            (360 - (info.orientation + degrees) % 360) % 360
        } else {
            (info.orientation - degrees + 360) % 360
        }
    }

    /**
     * Size the preview SurfaceView to the camera's aspect ratio, FITTING the whole frame inside
     * the screen (letterboxed) rather than cropping to fill. Fitting keeps the field of view
     * identical in portrait and landscape — covering would center-crop the 16:9 sensor frame much
     * harder on a portrait screen, making it look zoomed in vertical though the camera isn't. A
     * 90°/270° rotation swaps width/height. Decode is unaffected — it reads the raw frame.
     */
    private fun sizePreviewToAspect(previewW: Int, previewH: Int, orientation: Int) {
        val s = surface ?: return
        val rotated = orientation == 90 || orientation == 270
        val aspectW = if (rotated) previewH else previewW
        val aspectH = if (rotated) previewW else previewH
        if (aspectW == 0 || aspectH == 0) return
        val dm = resources.displayMetrics
        val scale = Math.min(
            dm.widthPixels.toFloat() / aspectW,
            dm.heightPixels.toFloat() / aspectH,
        )
        s.layoutParams = FrameLayout.LayoutParams(
            (aspectW * scale).toInt(),
            (aspectH * scale).toInt(),
        ).apply { gravity = Gravity.CENTER }
        s.requestLayout()
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
            // Step the zoom sweep periodically, holding each level long enough for the AE to
            // settle before we judge it (zoom is this HAL's only exposure control).
            if (zoomLevels.size > 1 && frameCount % ZOOM_STEP_FRAMES == 0) {
                zoomIdx = (zoomIdx + 1) % zoomLevels.size
                applyZoom(zoomLevels[zoomIdx])
            }
            // Decode every frame: the Camera1 callback naturally drops frames while we're busy,
            // so this self-throttles to whatever the CPU sustains rather than fixed half-rate.
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
     * Try to decode a QR from an NV21 luma plane.
     *
     * The camera sensor always delivers a landscape frame regardless of how the Portal screen is
     * mounted, so on a vertically-held device the QR — upright to the user — sits rotated 90° in
     * the frame. We decode the frame as-is first (the fast path, and what a landscape screen
     * needs), then, only if that fails, re-decode the luma rotated upright, trying both 90° and
     * 270° to cover either portrait mount.
     */
    private fun tryDecode(data: ByteArray, w: Int, h: Int): String? {
        decodeAllRegions(data, w, h)?.let { return it }
        decodeAllRegions(rotateLuma(data, w, h, true), h, w)?.let { return it }
        decodeAllRegions(rotateLuma(data, w, h, false), h, w)?.let { return it }
        return null
    }

    /**
     * Decode attempts over one (already-oriented) luma frame: full frame, then centred crops,
     * each tried normal + mirrored (the front camera may deliver mirrored frames). Cropping in
     * gives a small/distant QR more pixels per module.
     */
    private fun decodeAllRegions(data: ByteArray, w: Int, h: Int): String? {
        decodeRegion(data, w, h, 0, 0, w, h, false)?.let { return it }
        decodeRegion(data, w, h, 0, 0, w, h, true)?.let { return it }
        for (frac in floatArrayOf(0.6f, 0.4f)) {
            val side = (Math.min(w, h) * frac).toInt()
            val left = (w - side) / 2
            val top = (h - side) / 2
            decodeRegion(data, w, h, left, top, side, side, false)?.let { return it }
            decodeRegion(data, w, h, left, top, side, side, true)?.let { return it }
        }
        return null
    }

    /**
     * Rotate just the NV21 luma (Y) plane 90° — clockwise if [cw], else counter-clockwise —
     * returning a w*h Y-only buffer with the dimensions swapped (caller passes h as the new
     * width, w as the new height). Chroma is dropped: [PlanarYUVLuminanceSource] reads only luma.
     */
    private fun rotateLuma(data: ByteArray, w: Int, h: Int, cw: Boolean): ByteArray {
        val out = ByteArray(w * h)
        if (cw) {
            for (y in 0 until h) {
                val row = y * w
                for (x in 0 until w) out[x * h + (h - 1 - y)] = data[row + x]
            }
        } else {
            for (y in 0 until h) {
                val row = y * w
                for (x in 0 until w) out[(w - 1 - x) * h + y] = data[row + x]
            }
        }
        return out
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
        // the soft, low-contrast frames this fixed-focus wide camera produces. And a blown-out
        // bright screen often binarizes as a negative, so try the inverted luma too. Each combo
        // is cheap relative to grabbing another frame; the first to decode wins.
        for (lum in arrayOf(src, src.invert())) {
            for (bin in arrayOf(HybridBinarizer(lum), GlobalHistogramBinarizer(lum))) {
                try {
                    return reader.decode(BinaryBitmap(bin), HINTS).text
                } catch (notFound: Exception) {
                    // try the next binarizer / inversion
                } finally {
                    reader.reset()
                }
            }
        }
        return null
    }

    /** Re-apply just the zoom level mid-stream (cheap next to a full camera reconfigure). */
    private fun applyZoom(z: Int) {
        try {
            val c = camera ?: return
            val p = c.parameters
            if (p.isZoomSupported && p.zoom != z) {
                p.zoom = z
                c.parameters = p
            }
        } catch (ignored: Exception) {
            // best-effort; a dropped zoom step just retries on the next cycle
        }
    }

    private fun onQr(text: String?) {
        val url = text?.trim() ?: ""
        if (!isPhotosLink(url)) {
            scanHint?.text = "That QR isn't a Google Photos or iCloud album link — try again"
            scanning = true // keep scanning
            return
        }
        Albums.add(prefs(), url)
        Log.i(TAG, "album added via QR: $url")
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
        private const val ZOOM_STEP_FRAMES = 12 // frames held at each zoom level before stepping
        private const val DEFAULT_SCAN_HINT =
            "Hold the QR back at about arm's length and fill the box with it — up close it blurs. " +
                "Or paste the link below."
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

        private fun isPhotosLink(s: String): Boolean = PhotoSources.matches(s)
    }
}
