package com.portalhacks.frame

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ImageSpan
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.io.IOException
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.ArrayList
import java.util.Calendar
import java.util.Collections
import java.util.Date
import java.util.HashSet
import java.util.LinkedList
import java.util.Locale
import java.util.Random
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.max

/**
 * Crossfading slideshow. Items are image IDs — either bundled asset paths
 * ("slides/01.png") or remote URLs ("https://...") — loaded asynchronously via
 * [ImageLoader]. The source can be swapped at runtime with
 * [setItems] (used to switch from bundled samples to a Google Photos
 * shared album once it loads).
 *
 * Touch handling is distance-based (works for slow finger swipes):
 *   - drag left  -> next image
 *   - drag right -> previous image
 *   - tap        -> dismiss (runs onDismiss)
 */
class SlideshowController(
    private val context: Context,
    root: FrameLayout,
    private val loader: ImageLoader,
) {

    private val back: ImageView
    private val front: ImageView
    private val status: TextView
    private val info: TextView
    private val clock: TextView
    private val clockBox: LinearLayout
    private val bigClock: TextView // centered, larger clock for low-light mode
    private val bigDate: TextView
    private val clockOnlyBox: LinearLayout
    private val dateLine: TextView
    private val shimmer: ShimmerView
    private val timeFmt: DateFormat
    private val dateFmt = SimpleDateFormat("EEE, MMM d", Locale.getDefault())
    private val monthYearFmt = SimpleDateFormat("MMM yyyy", Locale.getDefault())
    private val fahrenheit = "US" == Locale.getDefault().country
    private val nightTint: View // warm overlay that fades in at night (Ambient-EQ-lite)
    private val ambientGlow: View // edge vignette tinted to the photo's mood color
    private var weather: Weather.Now? = null // current reading; null until loaded
    private val moonDrawable: Drawable // blue crescent, clear nights
    private val handler = Handler(Looper.getMainLooper())
    private var reqW: Int
    private var reqH: Int
    private var screenPortrait: Boolean // screen taller than wide → pair/stack opposite axis

    // Smart-shuffle memory: ids shown recently (most-recent first) so a reshuffle
    // doesn't immediately replay them, and the last id to avoid a back-to-back repeat.
    private val recentIds = LinkedList<String>()
    private var lastShownId: String? = null
    private var shimmerHidden = false

    // User-tunable settings (read from prefs in the constructor; see PhotosActivity).
    private val intervalMs: Long // time each slide is held
    private val autoFadeMs: Long // auto crossfade duration
    private val shuffle: Boolean // play photos in random order
    private val pairs: Boolean // pair two photos to fill the screen (side-by-side or stacked)
    private val kenBurns: Boolean // cinematic slow pan + zoom while held
    private val showClock: Boolean // clock + weather overlay
    private val nightMode: Boolean // warm night dimming
    private val onThisDay: Boolean // surface "N years ago today" memories
    private val captions: Boolean // photo date captions (lower-right)
    private val faceFraming: Boolean // bias Ken Burns toward detected faces
    private val ambientColor: Boolean // tint chrome to each photo's palette
    private val enhance: Boolean // on-device auto-levels + vibrance
    private val zoomFill: Boolean // single photos: crop to fill (vs whole photo + blurred fill).
                                  // Pairs always fill their half regardless.

    // Ken Burns animation state.
    private val rnd = Random()
    private var kbAnim: ValueAnimator? = null
    private var kbPath: KenBurns? = null
    private var enhanceFilter: ColorMatrixColorFilter? = null // per-slide, or null

    private var items: MutableList<Slide> = ArrayList()
    private var remote = false
    private var index = 0
    private var curIsPair = false // current frame shows a paired (two-photo) composite
    private var running = false
    private var animGen = 0L
    private var onDismiss: Runnable? = null
    private var onSettings: Runnable? = null
    private var clockOnly = false // low-light mode: black screen, clock only

    init {
        val dm = context.resources.displayMetrics
        reqW = if (dm.widthPixels > 0) dm.widthPixels else 1280
        reqH = if (dm.heightPixels > 0) dm.heightPixels else 800
        screenPortrait = reqH > reqW

        val prefs = context.getSharedPreferences(ConfigReceiver.PREFS, Context.MODE_PRIVATE)
        intervalMs = prefs.getLong(ConfigReceiver.KEY_DELAY_MS, ConfigReceiver.DEFAULT_DELAY_MS)
        autoFadeMs = prefs.getLong(ConfigReceiver.KEY_FADE_MS, ConfigReceiver.DEFAULT_FADE_MS)
        shuffle = prefs.getBoolean(ConfigReceiver.KEY_SHUFFLE, false)
        pairs = prefs.getBoolean(ConfigReceiver.KEY_PAIRS, ConfigReceiver.DEFAULT_PAIRS)
        kenBurns = prefs.getBoolean(ConfigReceiver.KEY_KEN_BURNS, ConfigReceiver.DEFAULT_KEN_BURNS)
        showClock = prefs.getBoolean(ConfigReceiver.KEY_CLOCK, ConfigReceiver.DEFAULT_CLOCK)
        nightMode = prefs.getBoolean(ConfigReceiver.KEY_NIGHT, ConfigReceiver.DEFAULT_NIGHT)
        onThisDay = prefs.getBoolean(ConfigReceiver.KEY_ON_THIS_DAY, ConfigReceiver.DEFAULT_ON_THIS_DAY)
        captions = prefs.getBoolean(ConfigReceiver.KEY_CAPTIONS, ConfigReceiver.DEFAULT_CAPTIONS)
        faceFraming = prefs.getBoolean(ConfigReceiver.KEY_FACE, ConfigReceiver.DEFAULT_FACE)
        ambientColor = prefs.getBoolean(ConfigReceiver.KEY_AMBIENT, ConfigReceiver.DEFAULT_AMBIENT)
        enhance = prefs.getBoolean(ConfigReceiver.KEY_ENHANCE, ConfigReceiver.DEFAULT_ENHANCE)
        zoomFill = prefs.getBoolean(ConfigReceiver.KEY_ZOOM_FILL, ConfigReceiver.DEFAULT_ZOOM_FILL)
        monthYearFmt.timeZone = TimeZone.getTimeZone("UTC")

        root.setBackgroundColor(Color.BLACK)
        back = newImageView()
        front = newImageView()
        front.alpha = 0f

        val margin = Ui.dp(context, 28f)
        // Height the clock box (and, matching it, the photo-date caption) sit off the
        // bottom — so "2 months ago" lines up with the clock's "Sun, Jun 14" date line.
        val clockBottom = Ui.dp(context, 95f)

        // Gradient scrims so the white system-overlay pills (top) and our caption
        // text (bottom) stay legible over bright photos — per the Portal design rules.
        val topScrim = View(context)
        topScrim.background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(0x99000000.toInt(), 0x00000000),
        )
        val tsp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, Ui.dp(context, 96f),
        )
        tsp.gravity = Gravity.TOP
        topScrim.layoutParams = tsp

        val bottomScrim = View(context)
        bottomScrim.background = GradientDrawable(
            GradientDrawable.Orientation.BOTTOM_TOP,
            intArrayOf(0xB3000000.toInt(), 0x00000000),
        )
        val bsp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, Ui.dp(context, 150f),
        )
        bsp.gravity = Gravity.BOTTOM
        bottomScrim.layoutParams = bsp

        // Loading / error hint — moved to the top so it doesn't fight the clock.
        status = TextView(context)
        status.setTextColor(Ui.TEXT_MUTED)
        status.typeface = Ui.medium(context)
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        status.setShadowLayer(6f, 0f, 1f, Color.BLACK)
        val sp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
        )
        sp.gravity = Gravity.TOP or Gravity.START
        sp.leftMargin = margin
        sp.topMargin = Ui.dp(context, 24f)
        status.layoutParams = sp

        // Lower-right: photo date (and location when available).
        info = TextView(context)
        info.setTextColor(0xFFF0F0F0.toInt())
        info.typeface = Ui.medium(context)
        info.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        info.setShadowLayer(8f, 0f, 1f, Color.BLACK)
        val ip = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
        )
        ip.gravity = Gravity.BOTTOM or Gravity.END
        ip.rightMargin = margin
        ip.bottomMargin = clockBottom // align with the clock's date line, not the screen edge
        info.layoutParams = ip

        // Bottom-left: a large clock with a "day, date · weather" line beneath it
        // (Nest/Portal photo-frame style). Time uses a clean, AM/PM-free format.
        timeFmt = SimpleDateFormat(
            if (android.text.format.DateFormat.is24HourFormat(context)) "H:mm" else "h:mm",
            Locale.getDefault(),
        )
        clock = TextView(context)
        clock.setTextColor(Color.WHITE)
        clock.typeface = Ui.clockFace(context) // match the Portal native clock
        clock.setTextSize(TypedValue.COMPLEX_UNIT_SP, 80f)
        clock.setShadowLayer(12f, 0f, 2f, Color.BLACK)
        clock.includeFontPadding = false
        val moonPx = Ui.dp(context, 22f)
        val md = BitmapDrawable(context.resources, Ui.crescent(moonPx, 0xFF5FA8FF.toInt()))
        md.setBounds(0, 0, moonPx, moonPx)
        moonDrawable = md
        dateLine = TextView(context)
        dateLine.setTextColor(0xFFF0F0F0.toInt())
        dateLine.typeface = Ui.medium(context)
        dateLine.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        dateLine.setShadowLayer(8f, 0f, 1f, Color.BLACK)
        clockBox = LinearLayout(context)
        clockBox.orientation = LinearLayout.VERTICAL
        clockBox.addView(clock)
        clockBox.addView(dateLine)
        val cbp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
        )
        cbp.gravity = Gravity.BOTTOM or Gravity.START
        cbp.leftMargin = margin
        cbp.bottomMargin = clockBottom // match the Portal home clock's height off the bottom
        clockBox.layoutParams = cbp

        // Warm overlay over the photo that fades in at night (Ambient-EQ-lite): the
        // image is dimmed by the window brightness and tinted cozy-warm here.
        nightTint = View(context)
        nightTint.setBackgroundColor(0xFFFF8A2A.toInt())
        nightTint.alpha = 0f
        nightTint.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        )

        // Edge vignette tinted to each photo's mood color (Ambilight-for-photos).
        ambientGlow = View(context)
        ambientGlow.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        )
        if (!ambientColor) {
            ambientGlow.visibility = View.GONE
        }

        // Shimmer over the dark first frame so it never looks "stuck" while loading.
        shimmer = ShimmerView(context)
        shimmer.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        )

        // Centered, larger, weather-less clock for low-light "clock only" mode — a touch
        // dimmer than the overlay clock. Hidden until setClockOnly(true).
        bigClock = TextView(context)
        bigClock.setTextColor(0xFFCFCFCF.toInt())
        bigClock.typeface = Ui.clockFace(context)
        bigClock.setTextSize(TypedValue.COMPLEX_UNIT_SP, 150f)
        bigClock.includeFontPadding = false
        bigClock.gravity = Gravity.CENTER_HORIZONTAL
        bigClock.setShadowLayer(16f, 0f, 2f, Color.BLACK)
        bigDate = TextView(context)
        bigDate.setTextColor(0xFF9AA0AE.toInt())
        bigDate.typeface = Ui.medium(context)
        bigDate.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
        bigDate.gravity = Gravity.CENTER_HORIZONTAL
        bigDate.setShadowLayer(8f, 0f, 1f, Color.BLACK)
        bigClock.setSingleLine(true)
        clockOnlyBox = LinearLayout(context)
        clockOnlyBox.orientation = LinearLayout.VERTICAL
        clockOnlyBox.addView(
            bigClock,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        val bdlp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        bdlp.topMargin = Ui.dp(context, 4f)
        clockOnlyBox.addView(bigDate, bdlp)
        // Full-width box so the centered clock never clips; vertically centred on screen.
        val colp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
        )
        colp.gravity = Gravity.CENTER_VERTICAL
        clockOnlyBox.layoutParams = colp
        clockOnlyBox.visibility = View.GONE

        if (!showClock) {
            clockBox.visibility = View.GONE
        }
        if (!captions) {
            info.visibility = View.GONE
        }

        root.addView(back)
        root.addView(front)
        root.addView(ambientGlow)
        root.addView(nightTint)
        root.addView(shimmer)
        root.addView(topScrim)
        root.addView(bottomScrim)
        root.addView(status)
        root.addView(info)
        root.addView(clockBox)
        root.addView(clockOnlyBox)
        root.addView(buildTouchOverlay())

        // Run clock/night + weather + shimmer from the start so they're alive even
        // during the initial "Loading…" wait before the first photo arrives.
        startClock()
        if (showClock) {
            startWeather()
        }
        shimmer.startSweep()
    }

    fun setOnDismiss(onDismiss: Runnable?) {
        this.onDismiss = onDismiss
    }

    /** Long-press anywhere on the slideshow runs this (used to open Photos setup). */
    fun setOnSettings(onSettings: Runnable?) {
        this.onSettings = onSettings
    }

    fun setStatusHint(text: String?) {
        status.text = text
    }

    private fun newImageView(): ImageView {
        val iv = ImageView(context)
        iv.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        )
        iv.scaleType = ImageView.ScaleType.CENTER_CROP
        return iv
    }

    private fun buildTouchOverlay(): View {
        val overlay = View(context)
        overlay.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        )
        overlay.isClickable = true
        overlay.isFocusable = true
        overlay.setOnTouchListener(object : View.OnTouchListener {
            private var downX = 0f
            private var downY = 0f
            private var downTime = 0L
            private var handled = false
            private var pendingLong: Runnable? = null

            private fun cancelLong() {
                pendingLong?.let {
                    handler.removeCallbacks(it)
                    pendingLong = null
                }
            }

            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = e.x
                        downY = e.y
                        downTime = e.eventTime
                        handled = false
                        v.parent?.requestDisallowInterceptTouchEvent(true)
                        cancelLong()
                        if (onSettings != null) {
                            val pl = Runnable {
                                pendingLong = null
                                handled = true // suppress the tap-dismiss on release
                                onSettings?.run()
                            }
                            pendingLong = pl
                            handler.postDelayed(pl, LONG_PRESS_MS)
                        }
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (!handled) {
                            val dx = e.x - downX
                            val dy = e.y - downY
                            if (abs(dx) > TAP_SLOP || abs(dy) > TAP_SLOP) {
                                cancelLong() // finger moved — not a long press
                            }
                            if (abs(dx) > SWIPE_MIN_DISTANCE && abs(dx) > abs(dy)) {
                                handled = true
                                if (dx < 0) {
                                    showNext()
                                } else {
                                    showPrevious()
                                }
                            }
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        cancelLong()
                        val dx = e.x - downX
                        val dy = e.y - downY
                        val dt = e.eventTime - downTime
                        if (!handled) {
                            if (abs(dx) > SWIPE_MIN_DISTANCE && abs(dx) > abs(dy)) {
                                if (dx < 0) {
                                    showNext()
                                } else {
                                    showPrevious()
                                }
                            } else if (abs(dx) < TAP_SLOP && abs(dy) < TAP_SLOP &&
                                dt < TAP_TIMEOUT_MS && onDismiss != null
                            ) {
                                onDismiss?.run()
                            }
                        }
                        return true
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        cancelLong()
                        return true
                    }
                    else -> return true
                }
            }
        })
        return overlay
    }

    fun start() {
        running = true
        startClock()
        if (showClock) {
            startWeather()
        }
        if (!shimmerHidden) {
            shimmer.startSweep()
        }
        if (items.isEmpty()) {
            items = assetItems()
            remote = false
            if (shuffle) {
                smartShuffle(items)
            }
        }
        if (onThisDay) {
            promoteOnThisDay(items) // no-op for bundled samples (no dates)
        }
        if (items.isEmpty()) {
            status.text = "No slides found in assets/$SLIDES_DIR"
            back.setBackgroundColor(Color.DKGRAY)
            return
        }
        index = 0
        Log.i(
            TAG,
            "Slideshow started with " + items.size +
                (if (remote) " album photos" else " bundled slides"),
        )
        showImmediate(0)
    }

    fun stop() {
        running = false
        handler.removeCallbacks(autoTick)
        handler.removeCallbacks(clockTick)
        handler.removeCallbacks(weatherTick)
        shimmer.stopSweep()
        front.animate().cancel()
        kbAnim?.let {
            it.cancel()
            kbAnim = null
        }
    }

    /**
     * Re-read the screen size/orientation after a device rotation. The host Activity handles
     * orientation config changes itself (it isn't recreated), so without this the controller keeps
     * the dimensions and pairing axis it captured at construction — leaving e.g. top/bottom stacks
     * on a now-landscape screen. Updates the Ken Burns target size and the pairing axis, then
     * re-renders the current slide so side-by-side ↔ top/bottom flips to match the new orientation.
     */
    fun onScreenConfigChanged() {
        val dm = context.resources.displayMetrics
        val w = if (dm.widthPixels > 0) dm.widthPixels else reqW
        val h = if (dm.heightPixels > 0) dm.heightPixels else reqH
        if (w == reqW && h == reqH) {
            return // no real dimension change
        }
        reqW = w
        reqH = h
        screenPortrait = reqH > reqW
        if (running && items.isNotEmpty() && index in items.indices) {
            showImmediate(index) // rebuild the current frame for the new orientation
        }
    }

    /**
     * Clear both image layers to black immediately. Called when the frame comes to
     * the foreground so a resumed/relaunched instance doesn't briefly show the
     * PREVIOUS run's last photo (retained in the ImageView) before the new first
     * frame loads. Bumps the anim generation so any in-flight load is discarded.
     */
    fun blank() {
        animGen++
        kbAnim?.let {
            it.cancel()
            kbAnim = null
        }
        front.animate().cancel()
        front.setImageDrawable(null)
        front.alpha = 0f
        front.scaleX = 1f
        front.scaleY = 1f
        front.translationX = 0f
        front.translationY = 0f
        back.setImageDrawable(null)
        back.scaleX = 1f
        back.scaleY = 1f
        back.translationX = 0f
        back.translationY = 0f
        back.colorFilter = null
        front.colorFilter = null
        info.text = ""
        ambientGlow.alpha = 0f
    }

    /**
     * Low-light "clock only" mode: a black screen showing just the clock (photos paused).
     * Mirrors the Portal night-mode "only show clock in low light" behaviour. Driven by
     * the ambient light sensor in [SlideshowComposeActivity].
     */
    fun setClockOnly(on: Boolean) {
        if (clockOnly == on) {
            return
        }
        clockOnly = on
        if (on) {
            handler.removeCallbacks(autoTick) // pause advancing
            shimmer.stopSweep()
            blank() // photos -> black
            clockBox.visibility = View.GONE // hide the bottom overlay clock
            clockOnlyBox.visibility = View.VISIBLE // big centered clock instead
            startClock() // ensure ticking + populate the big clock now
        } else {
            clockOnlyBox.visibility = View.GONE
            if (!shimmerHidden) {
                shimmer.startSweep()
            }
            clockBox.visibility = if (showClock) View.VISIBLE else View.GONE
            if (running && items.isNotEmpty()) {
                showImmediate(index) // resume photos + auto-advance
            }
        }
    }

    /** Swap the photo source at runtime (e.g. bundled samples -> album). */
    fun setItems(newItems: List<Slide>?) {
        if (newItems == null || newItems.isEmpty()) {
            return
        }
        items = ArrayList(newItems)
        if (shuffle) {
            smartShuffle(items)
        }
        if (onThisDay) {
            promoteOnThisDay(items)
        }
        remote = true
        if (clockOnly) {
            return // keep showing the clock; the new photos display when light returns
        }
        handler.removeCallbacks(autoTick)
        startClock()
        if (showClock) {
            startWeather()
        }
        if (!shimmerHidden) {
            shimmer.startSweep()
        }
        front.animate().cancel()
        front.alpha = 0f
        index = 0
        running = true
        Log.i(TAG, "Source switched to " + items.size + " album photos")
        showImmediate(0)
    }

    fun showNext() {
        if (items.isNotEmpty()) {
            transitionTo(nextStart(index, curIsPair), SWIPE_FADE_MS)
        }
    }

    fun showPrevious() {
        if (items.isNotEmpty()) {
            transitionTo((index - 1 + items.size) % items.size, SWIPE_FADE_MS)
        }
    }

    private fun scheduleAuto() {
        handler.removeCallbacks(autoTick)
        if (running && items.size > 1) {
            handler.postDelayed(autoTick, intervalMs)
        }
    }

    private val autoTick = Runnable {
        if (running && items.isNotEmpty()) {
            val step = if (curIsPair) 2 else 1
            val next: Int
            if (index + step >= items.size) {
                // Wrapped a full loop: reshuffle so the next loop differs and
                // doesn't open with a photo we just showed.
                if (shuffle && items.size > 2) {
                    smartShuffle(items)
                }
                next = 0
            } else {
                next = index + step
            }
            transitionTo(next, autoFadeMs)
        }
    }

    /** Show item i directly (no crossfade) — used for the first frame. */
    private fun showImmediate(i: Int) {
        val gen = ++animGen
        val j = pairWith(i)
        val isPair = j >= 0
        val cb = ImageLoader.Callback { b ->
            if (gen != animGen) {
                return@Callback
            }
            if (b != null) {
                back.setImageBitmap(b)
                front.setImageDrawable(null)
                front.alpha = 0f
                index = i
                curIsPair = isPair
                status.text = ""
                info.text = captionOf(i)
                noteShown(i)
                if (isPair) {
                    noteShown(j)
                }
                hideShimmer()
                kbPath = newKenBurnsPath(b)
                applyKenBurnsStart(back, kbPath)
                startKenBurnsOnBack(gen)
                updateAmbient(b)
                enhanceFilter = if (enhance) makeEnhance(b) else null
                back.colorFilter = enhanceFilter
            }
            prefetchNext(nextStart(i, isPair))
            scheduleAuto()
        }
        if (isPair) {
            loader.loadPair(items[i].id, items[j].id, reqW, reqH, screenPortrait, cb)
        } else {
            loader.load(items[i].id, reqW, reqH, zoomFill, cb)
        }
    }

    /** Crossfade to start item [next]; loads async, safe to call mid-fade. */
    private fun transitionTo(next: Int, fadeMs: Long) {
        if (items.isEmpty()) {
            return
        }
        handler.removeCallbacks(autoTick)
        val gen = ++animGen
        val j = pairWith(next)
        val isPair = j >= 0
        val cb = ImageLoader.Callback { bmp ->
            if (gen != animGen) {
                return@Callback // superseded by a newer request
            }
            if (bmp == null) {
                index = next
                curIsPair = isPair
                scheduleAuto()
                return@Callback
            }
            front.animate().cancel()
            front.setImageBitmap(bmp)
            front.alpha = 0f
            index = next
            curIsPair = isPair
            status.text = ""
            info.text = captionOf(next)
            noteShown(next)
            if (isPair) {
                noteShown(j)
            }
            hideShimmer()
            // Incoming image shows the path's START transform during the fade; when it
            // settles onto `back` we hand off at the same transform and animate to the end.
            kbPath = newKenBurnsPath(bmp)
            applyKenBurnsStart(front, kbPath)
            enhanceFilter = if (enhance) makeEnhance(bmp) else null
            front.colorFilter = enhanceFilter
            front.animate().alpha(1f).setDuration(fadeMs).withEndAction {
                if (gen != animGen) {
                    return@withEndAction
                }
                back.setImageBitmap(bmp)
                applyKenBurnsStart(back, kbPath)
                back.colorFilter = enhanceFilter
                front.alpha = 0f
                applyKenBurnsStart(front, null) // reset incoming view for reuse
                front.colorFilter = null
                startKenBurnsOnBack(gen)
                updateAmbient(bmp)
                prefetchNext(nextStart(next, isPair))
                scheduleAuto()
            }
        }
        if (isPair) {
            loader.loadPair(items[next].id, items[j].id, reqW, reqH, screenPortrait, cb)
        } else {
            loader.load(items[next].id, reqW, reqH, zoomFill, cb)
        }
    }

    /**
     * Index this slide pairs with, or -1 if it doesn't pair. We pair two consecutive photos whose
     * orientation is OPPOSITE the screen's, so together they fill it: portrait photos side-by-side
     * on a landscape screen, landscape photos stacked top/bottom on a vertical screen. A photo that
     * matches the screen orientation already fills it, so it's shown alone (full-screen).
     */
    private fun pairWith(start: Int): Int {
        if (!pairs || items.size < 2 || start < 0 || start >= items.size) {
            return -1
        }
        if (items[start].portrait == screenPortrait) {
            return -1 // already fills the screen on its own
        }
        val j = start + 1
        if (j >= items.size || items[j].portrait == screenPortrait) {
            return -1 // next can't pair (fills screen alone, or loop wrap)
        }
        return j
    }

    /** The start index after this one, stepping over a pair, wrapping to 0. */
    private fun nextStart(start: Int, isPair: Boolean): Int {
        val n = start + if (isPair) 2 else 1
        return if (n >= items.size) 0 else n
    }

    private fun prefetchNext(startIndex: Int) {
        if (items.size > 1 && startIndex >= 0 && startIndex < items.size) {
            loader.prefetch(items[startIndex].id, reqW, reqH, zoomFill)
        }
    }

    // ---------------------------------------------------------------- ambient color

    /** Tint the edge vignette to the photo's mood color (no-op when disabled). */
    private fun updateAmbient(bmp: Bitmap?) {
        if (!ambientColor || bmp == null) {
            return
        }
        val c = AmbientColor.extract(bmp)
        if (c == null) {
            ambientGlow.animate().alpha(0f).setDuration(600)
            return
        }
        val edge = (c and 0x00FFFFFF) or 0x70000000.toInt() // ~44% alpha at the edges
        val g = GradientDrawable()
        g.gradientType = GradientDrawable.RADIAL_GRADIENT
        g.gradientRadius = max(reqW, reqH) * 0.62f
        g.setGradientCenter(0.5f, 0.5f)
        g.colors = intArrayOf(0x00000000, 0x00000000, edge) // clear core -> color rim
        ambientGlow.background = g
        ambientGlow.animate().alpha(1f).setDuration(600)
    }

    // ---------------------------------------------------------------- auto-enhance

    /** Per-photo auto-levels + vibrance color filter (null when off / not needed). */
    private fun makeEnhance(bmp: Bitmap?): ColorMatrixColorFilter? {
        val cm = PhotoEnhance.compute(bmp) ?: return null
        return ColorMatrixColorFilter(cm)
    }

    // ---------------------------------------------------------------- Ken Burns

    /** Put a view at the start of the current path (or reset to identity if off). */
    private fun applyKenBurnsStart(v: View, p: KenBurns?) {
        if (p == null) {
            v.scaleX = 1f
            v.scaleY = 1f
            v.translationX = 0f
            v.translationY = 0f
        } else {
            p.applyAt(v, 0f)
        }
    }

    /** Animate the settled image ([back]) along the path over the hold time. */
    private fun startKenBurnsOnBack(gen: Long) {
        kbAnim?.let {
            it.cancel()
            kbAnim = null
        }
        val p = kbPath ?: return
        val a = ValueAnimator.ofFloat(0f, 1f)
        a.duration = max(intervalMs, 1200L) + autoFadeMs
        a.interpolator = LinearInterpolator()
        a.addUpdateListener { va ->
            if (gen != animGen) {
                va.cancel()
                return@addUpdateListener
            }
            p.applyAt(back, va.animatedValue as Float)
        }
        kbAnim = a
        a.start()
    }

    /** Build the path for the slide just loaded (face-biased when available). */
    private fun newKenBurnsPath(bmp: Bitmap?): KenBurns? {
        if (!kenBurns) {
            return null
        }
        val focus = if (faceFraming && bmp != null) FaceFocus.find(bmp) else null
        return KenBurns.random(reqW, reqH, rnd, focus)
    }

    /**
     * A slow zoom-in + gentle pan applied to the settled image while it's held — the cinematic
     * "Ken Burns" motion. Each photo STARTS at exactly minimum fill (scale 1.0, centred) so it's
     * first shown zoomed the minimum amount, then eases gently inward. The pan starts at zero and
     * grows with the scale, so the image always covers the view (no edge reveal). When a focal
     * point is given (e.g. a detected face), the drift eases toward it.
     */
    private class KenBurns private constructor(
        val s0: Float,
        val s1: Float,
        val tx0: Float,
        val tx1: Float,
        val ty0: Float,
        val ty1: Float,
    ) {

        fun applyAt(v: View, f: Float) {
            val s = s0 + (s1 - s0) * f
            v.scaleX = s
            v.scaleY = s
            v.translationX = tx0 + (tx1 - tx0) * f
            v.translationY = ty0 + (ty1 - ty0) * f
        }

        companion object {
            // The path always starts at 1.0 (exact minimum fill) and zooms gently toward a target
            // in this range — so a photo is first shown zoomed the minimum amount, not pre-zoomed.
            private const val END_ZOOM_MIN = 1.04f
            private const val END_ZOOM_MAX = 1.10f

            /**
             * @param focus optional {fx, fy} in [0,1] image space to drift toward; null = random.
             */
            fun random(w: Int, h: Int, r: Random, focus: FloatArray?): KenBurns {
                // Start at exact fill (scale 1.0, centred) and zoom IN to a gentle target. Pan
                // starts at zero (1.0 has no cover slack) and grows linearly with the scale toward
                // the end scale's slack — edge-safe at every point along the path.
                val s1 = END_ZOOM_MIN + r.nextFloat() * (END_ZOOM_MAX - END_ZOOM_MIN)
                val slackX = (s1 - 1f) / 2f * w * 0.9f // 90% of the end scale's cover slack
                val slackY = (s1 - 1f) / 2f * h * 0.9f
                val tx1: Float
                val ty1: Float
                if (focus != null) {
                    // Drift toward the focal point (e.g. a detected face) as it zooms in.
                    tx1 = clamp((0.5f - focus[0]) * 2f, -1f, 1f) * slackX
                    ty1 = clamp((0.5f - focus[1]) * 2f, -1f, 1f) * slackY
                } else {
                    tx1 = (r.nextFloat() * 2f - 1f) * slackX
                    ty1 = (r.nextFloat() * 2f - 1f) * slackY
                }
                return KenBurns(1f, s1, 0f, tx1, 0f, ty1)
            }

            private fun clamp(v: Float, lo: Float, hi: Float): Float =
                if (v < lo) lo else if (v > hi) hi else v
        }
    }

    private fun captionOf(i: Int): String {
        val s = items[i]
        if (s.caption != null) {
            return s.caption // explicit override (e.g. an "On this day" badge)
        }
        return if (s.timeMs == Slide.NO_DATE) "" else relativeTime(s.timeMs)
    }

    /** "Today" / "Yesterday" / "N days|weeks|months ago", or "MMM yyyy" past a year. */
    private fun relativeTime(timeMs: Long): String {
        val now = System.currentTimeMillis()
        val todayDays = (now + TimeZone.getDefault().getOffset(now)) / 86400000L
        val days = todayDays - timeMs / 86400000L // timeMs is already a UTC wall clock
        if (days <= 0) {
            return "Today"
        }
        if (days == 1L) {
            return "Yesterday"
        }
        if (days < 7) {
            return "$days days ago"
        }
        if (days < 45) {
            val w = Math.round(days / 7.0)
            return if (w <= 1) "1 week ago" else "$w weeks ago"
        }
        if (days < 365) {
            val m = Math.round(days / 30.0)
            return if (m <= 1) "1 month ago" else "$m months ago"
        }
        return monthYearFmt.format(Date(timeMs))
    }

    // ---------------------------------------------------------------- smart shuffle

    /** Remember a shown id so a reshuffle de-weights it (most-recent first). */
    private fun noteShown(i: Int) {
        if (items.isEmpty()) {
            return
        }
        val id = items[i].id
        lastShownId = id
        recentIds.remove(id)
        recentIds.addFirst(id)
        val cap = max(1, items.size / 3)
        while (recentIds.size > cap) {
            recentIds.removeLast()
        }
    }

    /**
     * Shuffle, then push recently shown photos toward the back (stable sort keeps
     * the shuffled order within each group) and make sure we don't open with the
     * photo just shown — so it never feels like "didn't I just see that?".
     */
    private fun smartShuffle(list: MutableList<Slide>) {
        Collections.shuffle(list)
        if (recentIds.isEmpty() && lastShownId == null) {
            return
        }
        val recent: Set<String> = HashSet(recentIds)
        Collections.sort(list) { a, b ->
            Integer.compare(
                if (recent.contains(a.id)) 1 else 0,
                if (recent.contains(b.id)) 1 else 0,
            )
        }
        if (list.size > 1 && lastShownId != null && list[0].id == lastShownId) {
            var swap = 1
            for (i in 1 until list.size) {
                if (!recent.contains(list[i].id)) {
                    swap = i
                    break
                }
            }
            val tmp = list[0]
            list[0] = list[swap]
            list[swap] = tmp
        }
    }

    // ---------------------------------------------------------------- On this day

    /**
     * Move photos taken on today's month+day in a past year to the front (most
     * recent memory first), re-captioned "N years ago today ✨". No-op for items
     * without a capture date (bundled samples).
     */
    private fun promoteOnThisDay(list: MutableList<Slide>) {
        val now = Calendar.getInstance()
        val curMonth = now.get(Calendar.MONTH)
        val curDay = now.get(Calendar.DAY_OF_MONTH)
        val curYear = now.get(Calendar.YEAR)

        // timeMs is the capture wall-clock expressed in UTC, so read it back in UTC.
        val c = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        val memories = ArrayList<Slide>()
        val it = list.iterator()
        while (it.hasNext()) {
            val s = it.next()
            if (s.timeMs == Slide.NO_DATE) {
                continue
            }
            c.timeInMillis = s.timeMs
            if (c.get(Calendar.MONTH) != curMonth || c.get(Calendar.DAY_OF_MONTH) != curDay) {
                continue
            }
            val yearsAgo = curYear - c.get(Calendar.YEAR)
            if (yearsAgo < 1) {
                continue // taken today this year, not a memory
            }
            val badge = if (yearsAgo == 1) {
                "1 year ago today ✨"
            } else {
                "$yearsAgo years ago today ✨"
            }
            memories.add(Slide(s.id, badge, s.timeMs, s.portrait))
            it.remove()
        }
        if (memories.isEmpty()) {
            return
        }
        Collections.sort(memories) { a, b ->
            java.lang.Long.compare(b.timeMs, a.timeMs) // most recent first
        }
        list.addAll(0, memories)
        Log.i(TAG, "On this day: promoted " + memories.size + " memory photo(s)")
    }

    // ---------------------------------------------------------------- clock + date

    private val clockTick = object : Runnable {
        override fun run() {
            updateClock()
            handler.postDelayed(this, 60000 - System.currentTimeMillis() % 60000)
        }
    }

    private fun startClock() {
        handler.removeCallbacks(clockTick)
        updateClock()
        handler.postDelayed(clockTick, 60000 - System.currentTimeMillis() % 60000)
    }

    private fun updateClock() {
        val c = Calendar.getInstance()
        nightTint.alpha = if (nightMode) {
            warmthForHour(c.get(Calendar.HOUR_OF_DAY) + c.get(Calendar.MINUTE) / 60f)
        } else {
            0f
        }
        val time = timeFmt.format(c.time)
        val date = dateFmt.format(c.time)
        // Centered low-light clock (no weather) — kept current even when the overlay is off.
        bigClock.text = time
        bigDate.text = date
        if (!showClock) {
            return // night tint still updates above; the overlay clock/weather text is off
        }
        clock.text = time
        val w = weather
        if (w == null) {
            dateLine.text = date
        } else if (w.moon) {
            // Clear night: draw a blue crescent (color emoji can't be tinted) + temp.
            val sb = SpannableStringBuilder("$date   ")
            val s = sb.length
            sb.append(" ")
            sb.setSpan(
                ImageSpan(moonDrawable, ImageSpan.ALIGN_CENTER),
                s, s + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            sb.append("  ").append(w.temp.toString()).append("°")
            dateLine.text = sb
        } else {
            dateLine.text = date + "   " + w.label()
        }
    }

    // ---------------------------------------------------------------- weather

    private val weatherTick = object : Runnable {
        override fun run() {
            refreshWeather()
            handler.postDelayed(this, WEATHER_INTERVAL_MS)
        }
    }

    private fun startWeather() {
        handler.removeCallbacks(weatherTick)
        // Fetch immediately if we have nothing yet; otherwise keep the periodic cadence.
        handler.postDelayed(weatherTick, if (weather == null) 0 else WEATHER_INTERVAL_MS)
    }

    private fun refreshWeather() {
        loader.executor().execute {
            val now = Weather.fetch(fahrenheit) ?: return@execute
            handler.post {
                weather = now
                updateClock()
            }
        }
    }

    // ---------------------------------------------------------------- loading shimmer

    private fun hideShimmer() {
        if (shimmerHidden) {
            return
        }
        shimmerHidden = true
        shimmer.animate().alpha(0f).setDuration(400).withEndAction {
            shimmer.stopSweep()
            shimmer.visibility = View.GONE
        }
    }

    /** A soft diagonal light band sweeping across a dark surface — a calm "loading". */
    private class ShimmerView(c: Context) : View(c) {
        private val paint = Paint()
        private val anim: ValueAnimator
        private var pos = -0.3f // sweep centre as a fraction of width

        init {
            setBackgroundColor(0xFF1A1A1A.toInt())
            anim = ValueAnimator.ofFloat(-0.3f, 1.3f)
            anim.duration = 1600
            anim.repeatCount = ValueAnimator.INFINITE
            anim.interpolator = LinearInterpolator()
            anim.addUpdateListener { a ->
                pos = a.animatedValue as Float
                invalidate()
            }
        }

        fun startSweep() {
            if (!anim.isStarted) {
                anim.start()
            }
        }

        fun stopSweep() {
            anim.cancel()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width
            val h = height
            if (w == 0 || h == 0) {
                return
            }
            val band = w * 0.35f
            val cx = pos * w
            paint.shader = LinearGradient(
                cx - band, 0f, cx + band, 0f,
                intArrayOf(0x00FFFFFF, 0x14FFFFFF, 0x00FFFFFF),
                floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP,
            )
            canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
        }
    }

    private fun assetItems(): MutableList<Slide> {
        val names = ArrayList<String>()
        try {
            val am = context.assets
            val list = am.list(SLIDES_DIR)
            if (list != null) {
                for (n in list) {
                    val lower = n.lowercase()
                    if (lower.endsWith(".png") || lower.endsWith(".jpg") ||
                        lower.endsWith(".jpeg") || lower.endsWith(".webp")
                    ) {
                        names.add("$SLIDES_DIR/$n")
                    }
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "Failed to list slides", e)
        }
        Collections.sort(names)
        val found = ArrayList<Slide>()
        for (n in names) {
            found.add(Slide(n, null))
        }
        return found
    }

    companion object {
        private const val TAG = "PortalFrame"
        private const val SLIDES_DIR = "slides"
        private const val SWIPE_FADE_MS = 300L // manual swipe fade
        private const val SWIPE_MIN_DISTANCE = 60f
        private const val TAP_SLOP = 30f
        private const val TAP_TIMEOUT_MS = 350L
        private const val LONG_PRESS_MS = 700L // hold to open Photos setup
        private const val WEATHER_INTERVAL_MS = 30 * 60 * 1000L // refresh weather

        /**
         * Warm-overlay strength by time of day (Ambient-EQ-lite): none in daylight,
         * easing in 20:00→23:00, full overnight, easing out 06:00→08:00.
         */
        private fun warmthForHour(h: Float): Float {
            val max = 0.14f
            if (h >= 8f && h < 20f) {
                return 0f
            }
            if (h >= 20f && h < 23f) {
                return max * (h - 20f) / 3f
            }
            if (h >= 23f || h < 6f) {
                return max
            }
            return max * (8f - h) / 2f // 06:00–08:00
        }
    }
}
