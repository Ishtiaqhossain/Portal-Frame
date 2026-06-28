package com.portalhacks.frame

import android.app.Activity
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
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
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
import java.util.Locale

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
    private var previewW = 0
    private var previewH = 0 // cached so we don't hit getParameters() per frame
    private var frameCount = 0

    // Closed-loop exposure. This camera's AE can't be set directly (EV is ignored, AE can't be
    // disabled), but ZOOM moves it: filling the frame with the bright phone screen makes the AE
    // meter the screen and expose DOWN, out of clipping. So we measure each frame's central
    // brightness and drive the zoom toward a target — blown out → zoom in (more fill → darker),
    // too dark → zoom out. This self-tunes to any phone brightness, even when it can't be lowered.
    private var maxZoomIdx = 0
    private var curZoom = 0
    private var zoomSettle = 0 // frames to let the AE settle after a zoom change before re-judging
    private var autoExpZoom = true // false when a fixed `zoompct` override pins the level

    // Per-device QR tuning — picked once. The Portal models' front cameras and screen orientations
    // differ enough that one device's optimization regresses another (the Portal+ portrait
    // closed-loop zoom + rotated decode passes broke the Portal Go's landscape fixed-focus lens).
    private val tuning = QrTuning.forThisDevice()

    /**
     * Per-device QR-scanner knobs, isolated so an optimization for one Portal can't regress another.
     */
    private class QrTuning(
        /** Pin digital zoom at this % of max and skip the closed-loop controller; 0 = run the loop. */
        val fixedZoomPct: Int,
        /** Starting zoom (% of max) for the closed-loop controller; used only when [fixedZoomPct] is 0. */
        val loopStartZoomPct: Int,
        /** Also try 90°/270° luma rotations when decoding — needed for portrait-mounted screens. */
        val decodeRotations: Boolean,
    ) {
        companion object {
            fun forThisDevice(): QrTuning {
                val id = (Build.DEVICE + " " + Build.MODEL + " " + Build.PRODUCT).lowercase(Locale.US)
                return when {
                    // Portal Go (codename "terry"): landscape screen, fixed-focus ultra-wide front
                    // camera. A pinned high zoom both enlarges the QR and forces the AE to expose for
                    // the bright screen; the screen is always landscape, so rotated decode passes only
                    // burn frame budget. This is the original known-good recipe (commit 6f2e712).
                    id.contains("terry") || id.contains("portalgo") ->
                        QrTuning(fixedZoomPct = 90, loopStartZoomPct = 90, decodeRotations = false)
                    // Portal / Portal+ (can be mounted portrait) and any unknown device: the adaptive
                    // closed-loop zoom + rotated decode passes added for the vertical screen.
                    else ->
                        QrTuning(fixedZoomPct = 0, loopStartZoomPct = 70, decodeRotations = true)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Pan the screen up when the on-screen keyboard appears so the link field stays visible.
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN)
        root = FrameLayout(this)
        root.setBackgroundColor(Ui.BG)
        setContentView(root)
        // The Compose SettingsActivity hands off here purely for the camera scanner /
        // manual-entry panel (it owns all other settings).
        startScan()
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

    // ---------------------------------------------------------------- prefs

    private fun prefs(): SharedPreferences =
        getSharedPreferences(ConfigReceiver.PREFS, MODE_PRIVATE)

    private fun topMargin(v: View, dp: Int) {
        var lp = v.layoutParams as? LinearLayout.LayoutParams
        if (lp == null) {
            lp = LinearLayout.LayoutParams(MATCH, WRAP)
        }
        lp.topMargin = Ui.dp(this, dp.toFloat())
        v.layoutParams = lp
    }

    /** A centered section heading for the scanner screen. */
    private fun sectionHeading(text: String): TextView {
        val t = TextView(this)
        t.text = text
        t.setTextColor(0xFFF0F0F0.toInt())
        t.typeface = Ui.bold(this)
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        t.gravity = Gravity.CENTER_HORIZONTAL
        t.setShadowLayer(8f, 0f, 1f, Color.BLACK)
        return t
    }

    // ------------------------------------------------ Manual entry

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

        // Below the box: two clearly-titled sections — how to scan, and a manual paste fallback —
        // with a wide gap between them so they read as distinct choices.
        val belowBox = LinearLayout(this)
        belowBox.orientation = LinearLayout.VERTICAL
        belowBox.gravity = Gravity.CENTER_HORIZONTAL

        // Section 1: scanning (labels the scan window above) + instructions.
        belowBox.addView(sectionHeading("Scan a QR code"), LinearLayout.LayoutParams(MATCH, WRAP))

        val subtitle = TextView(this)
        this.scanHint = subtitle // reused for "that QR isn't…/Album added ✓" feedback
        // The lens is fixed-focus, so slowly moving the QR toward/away sweeps it through the focus
        // (and the zoom sweep's exposure levels) until a frame lands — hence the back-and-forth tip.
        subtitle.text = DEFAULT_SCAN_HINT
        subtitle.setTextColor(0xFFD2D2D2.toInt())
        subtitle.typeface = Ui.medium(this)
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        subtitle.gravity = Gravity.CENTER_HORIZONTAL
        subtitle.setLineSpacing(Ui.dp(this, 3f).toFloat(), 1f)
        subtitle.setShadowLayer(8f, 0f, 1f, Color.BLACK)
        val subLp = LinearLayout.LayoutParams(MATCH, WRAP)
        subLp.topMargin = Ui.dp(this, 8f)
        belowBox.addView(subtitle, subLp)

        // Section 2: manual entry, set apart by a wide gap.
        val manualHeading = sectionHeading("Or enter the link manually")
        val manualHeadingLp = LinearLayout.LayoutParams(MATCH, WRAP)
        manualHeadingLp.topMargin = Ui.dp(this, 56f)
        belowBox.addView(manualHeading, manualHeadingLp)

        val edit = Ui.field(this, "Paste a Google Photos or iCloud link")
        edit.setSingleLine(true)
        edit.inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_VARIATION_URI or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        belowBox.addView(edit)
        val done = pillButton("Done", Ui.BLUE, 0xFFF0F0F0.toInt()) { addTypedAlbum(edit) }
        val doneLp = LinearLayout.LayoutParams(WRAP, WRAP)
        doneLp.topMargin = Ui.dp(this, 14f)
        doneLp.gravity = Gravity.END
        done.layoutParams = doneLp
        belowBox.addView(done)

        val belowLp = FrameLayout.LayoutParams(colW, WRAP)
        belowLp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        belowLp.topMargin = boxTop + boxSize + Ui.dp(this, 24f)
        f.addView(belowBox, belowLp)

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
            // actually moves exposure on this HAL (EV is ignored). The previewCb runs a closed loop
            // on top of this; here we just set the starting zoom. zoompct pins it (testing).
            if (pr.isZoomSupported) {
                maxZoomIdx = pr.maxZoom
                // A `zoompct` intent extra overrides the profile (testing); otherwise the device's
                // QrTuning decides: a pinned fixed zoom (Portal Go) or the closed-loop's start (others).
                val override = intent?.getIntExtra("zoompct", 0) ?: 0
                val pinPct = if (override > 0) override else tuning.fixedZoomPct
                if (pinPct > 0) {
                    autoExpZoom = false // pinned level — the closed loop in previewCb stays off
                    curZoom = Math.max(0, Math.min(maxZoomIdx, maxZoomIdx * pinPct / 100))
                } else {
                    autoExpZoom = true
                    // Start fairly tight: a bright screen needs heavy fill to expose down, and the
                    // loop backs the zoom off again if that overshoots too dark.
                    curZoom = Math.max(0, Math.min(maxZoomIdx, maxZoomIdx * tuning.loopStartZoomPct / 100))
                }
                pr.zoom = curZoom
                zoomSettle = AE_SETTLE_FRAMES
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
            // Closed-loop exposure: nudge the zoom toward a target brightness so the AE exposes the
            // bright screen down out of clipping. Hold a few frames after each change for the AE to
            // re-settle before judging again.
            if (autoExpZoom) {
                if (zoomSettle > 0) {
                    zoomSettle--
                } else {
                    val luma = centerLuma(data, previewW, previewH)
                    val moved = when {
                        luma > EXP_BRIGHT && curZoom < maxZoomIdx -> {
                            curZoom = Math.min(maxZoomIdx, curZoom + zoomStep()); true
                        }
                        luma < EXP_DARK && curZoom > 0 -> {
                            curZoom = Math.max(0, curZoom - zoomStep()); true
                        }
                        else -> false
                    }
                    if (moved) {
                        applyZoom(curZoom)
                        zoomSettle = AE_SETTLE_FRAMES
                    }
                    if (frameCount % 30 == 1) {
                        Log.i(TAG, "QR exp: centerLuma=$luma zoom=$curZoom/$maxZoomIdx")
                    }
                }
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
     * needs), then, only if that fails and the device can be mounted portrait ([QrTuning.decodeRotations]),
     * re-decode the luma rotated upright, trying both 90° and 270° to cover either portrait mount.
     * Skipping the rotations on a landscape-only device (Portal Go) cuts decode work ~3× per frame,
     * which keeps the frame rate up so sharp frames actually get a decode pass.
     */
    private fun tryDecode(data: ByteArray, w: Int, h: Int): String? {
        decodeAllRegions(data, w, h)?.let { return it }
        if (tuning.decodeRotations) {
            decodeAllRegions(rotateLuma(data, w, h, true), h, w)?.let { return it }
            decodeAllRegions(rotateLuma(data, w, h, false), h, w)?.let { return it }
        }
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

    /** One exposure-loop zoom step ≈ 10% of the zoom range. */
    private fun zoomStep(): Int = Math.max(1, maxZoomIdx / 10)

    /**
     * Mean luma of the central half of the NV21 frame (where the centred QR/screen sits), sampled
     * sparsely. Drives the exposure loop: this region tracks the screen, so a high value means the
     * screen is blown out and the zoom should fill more to make the AE expose down.
     */
    private fun centerLuma(data: ByteArray, w: Int, h: Int): Int {
        val x0 = w / 4
        val x1 = w * 3 / 4
        val y0 = h / 4
        val y1 = h * 3 / 4
        var sum = 0L
        var n = 0
        var y = y0
        while (y < y1) {
            val row = y * w
            var x = x0
            while (x < x1) {
                sum += (data[row + x].toInt() and 0xFF)
                n++
                x += 8
            }
            y += 8
        }
        return if (n > 0) (sum / n).toInt() else 0
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
        private const val AE_SETTLE_FRAMES = 3 // frames to let the AE settle after a zoom change
        private const val EXP_BRIGHT = 165 // center luma above this = blown out → zoom in (AE down)
        private const val EXP_DARK = 95 // center luma below this = too dark → zoom out
        private const val DEFAULT_SCAN_HINT =
            "Point the QR at the camera and slowly move it back and forth a few times until it " +
                "scans. Or paste the link below."

        private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

        private val HINTS: Map<DecodeHintType, Any> =
            EnumMap<DecodeHintType, Any>(DecodeHintType::class.java).apply {
                put(DecodeHintType.TRY_HARDER, java.lang.Boolean.TRUE)
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
