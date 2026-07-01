package com.portalhacks.frame

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
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
import kotlin.math.min

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
    private val clockOnlyScrim: View // opaque black backing so the low-light clock never shows over a photo
    private val dateLine: TextView
    private val clockEditHint: TextView // "drag/pinch/tap" hint shown while editing the clock
    private val noteBox: TextView // sticky-note overlay (top-right); hidden when empty
    private val binBox: LinearLayout // trash target shown while dragging the note
    private val binGlyph: TextView // the bin icon inside binBox (tinted on hover)
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

    // Clock widget transform (long-press the clock to edit; drag to move, pinch to resize). dx/dy
    // are translation as a fraction of screen W/H; scale is a size multiplier. Persisted to prefs.
    private var editingClock = false
    private var clockDx = 0f
    private var clockDy = 0f
    private var clockScale = 1f

    // Sticky-note state. noteText is the manually-set note (over ADB / Settings; "" = none). When
    // it's empty and fortune mode is on, the note instead shows fortuneLine — a fetched
    // "fortune cookie" wisdom line. dx/dy are the note's position as a fraction of screen W/H from
    // its top-right anchor (drag to move). Vars so they update live; draggingNote is true between
    // grabbing the note and releasing it.
    private var noteText = ""
    private var noteDx = 0f
    private var noteDy = 0f
    private var draggingNote = false
    // Master switch for the whole sticky-note + fortune overlay (off unless the user opts in).
    // When false the note never shows and the fortune line is never fetched.
    private val notesEnabled: Boolean
    private val fortuneEnabled: Boolean
    private var fortuneLine: String? = null

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
        clockDx = prefs.getFloat(ConfigReceiver.KEY_CLOCK_DX, ConfigReceiver.DEFAULT_CLOCK_DX)
        clockDy = prefs.getFloat(ConfigReceiver.KEY_CLOCK_DY, ConfigReceiver.DEFAULT_CLOCK_DY)
        clockScale = prefs.getFloat(ConfigReceiver.KEY_CLOCK_SCALE, ConfigReceiver.DEFAULT_CLOCK_SCALE)
        noteText = prefs.getString(ConfigReceiver.KEY_NOTE, "") ?: ""
        noteDx = prefs.getFloat(ConfigReceiver.KEY_NOTE_DX, 0f)
        noteDy = prefs.getFloat(ConfigReceiver.KEY_NOTE_DY, 0f)
        notesEnabled = prefs.getBoolean(ConfigReceiver.KEY_NOTES, ConfigReceiver.DEFAULT_NOTES)
        fortuneEnabled = prefs.getBoolean(ConfigReceiver.KEY_FORTUNE, ConfigReceiver.DEFAULT_FORTUNE)
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
        dateLine.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
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

        // Instruction shown only while the clock is being moved/resized (long-press to enter).
        clockEditHint = TextView(context)
        clockEditHint.text = "Drag to move · pinch to resize · tap to finish"
        clockEditHint.setTextColor(0xFFF0F0F0.toInt())
        clockEditHint.typeface = Ui.medium(context)
        clockEditHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        clockEditHint.gravity = Gravity.CENTER_HORIZONTAL
        clockEditHint.setShadowLayer(8f, 0f, 1f, Color.BLACK)
        clockEditHint.visibility = View.GONE
        val cehp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
        )
        cehp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        cehp.topMargin = Ui.dp(context, 40f)
        clockEditHint.layoutParams = cehp

        // Sticky note: a post-it-style card pinned top-right — warm paper with a vertical
        // gradient, a folded dog-ear corner, a strip of "tape" across the top and a soft drop
        // shadow, all drawn by StickyNoteBg. Slightly tilted so it reads as stuck onto the
        // frame. Drag it to move; drop it on the bin to dismiss. Hidden when empty.
        noteBox = TextView(context)
        noteBox.setTextColor(0xFF3B3526.toInt()) // dark ink on amber paper
        noteBox.typeface = Ui.medium(context)
        noteBox.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
        noteBox.setLineSpacing(Ui.dp(context, 3f).toFloat(), 1f)
        val notePadH = Ui.dp(context, 24f)
        // Extra top padding leaves room for the tape; extra bottom-right for the folded corner.
        noteBox.setPadding(notePadH, Ui.dp(context, 26f), notePadH + Ui.dp(context, 6f), Ui.dp(context, 24f))
        noteBox.maxWidth = Ui.dp(context, 300f)
        noteBox.background = StickyNoteBg(
            top = 0xFFFFEFA8.toInt(), // lighter amber at the top edge
            bottom = 0xFFFFD24A.toInt(), // deeper amber toward the bottom
            fold = 0xFFE8B838.toInt(), // underside of the folded corner
            tape = 0x33FFFFFF, // matte translucent tape
            radiusPx = Ui.dp(context, 5f).toFloat(),
            foldPx = Ui.dp(context, 26f).toFloat(),
        )
        noteBox.elevation = Ui.dp(context, 12f).toFloat() // drop shadow
        noteBox.rotation = -3f // casual "stuck on" tilt
        val np = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
        )
        np.gravity = Gravity.TOP or Gravity.END
        np.topMargin = Ui.dp(context, 104f) // clear the Portal system pills + top scrim
        np.rightMargin = Ui.dp(context, 44f)
        noteBox.layoutParams = np

        // Trash target: a translucent dark pill with a bin glyph, centred near the bottom.
        // Hidden until the note is grabbed; it enlarges and turns red while the note hovers it.
        binGlyph = TextView(context)
        binGlyph.text = "🗑" // 🗑
        binGlyph.setTextSize(TypedValue.COMPLEX_UNIT_SP, 34f)
        binGlyph.gravity = Gravity.CENTER
        val binLabel = TextView(context)
        binLabel.text = "Drop to remove"
        binLabel.setTextColor(0xFFEDEDED.toInt())
        binLabel.typeface = Ui.medium(context)
        binLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        binLabel.gravity = Gravity.CENTER
        binBox = LinearLayout(context)
        binBox.orientation = LinearLayout.VERTICAL
        binBox.gravity = Gravity.CENTER_HORIZONTAL
        val binPadH = Ui.dp(context, 26f)
        binBox.setPadding(binPadH, Ui.dp(context, 16f), binPadH, Ui.dp(context, 14f))
        binBox.background = binPill(false)
        binBox.elevation = Ui.dp(context, 8f).toFloat()
        binBox.addView(binGlyph)
        binBox.addView(
            binLabel,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = Ui.dp(context, 2f) },
        )
        binBox.visibility = View.GONE
        val binp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
        )
        binp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        binp.bottomMargin = Ui.dp(context, 56f)
        binBox.layoutParams = binp

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
        // Full-screen black backing for low-light mode. Sits above the photos but below the clock
        // text, so even a stray late photo decode can never show through behind the clock — the
        // invariant is "clock-only clock visible => screen is dark".
        clockOnlyScrim = View(context)
        clockOnlyScrim.setBackgroundColor(Color.BLACK)
        clockOnlyScrim.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT,
        )
        clockOnlyScrim.visibility = View.GONE

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
        root.addView(clockOnlyScrim)
        root.addView(clockOnlyBox)
        root.addView(clockEditHint)
        root.addView(noteBox)
        root.addView(binBox)
        root.addView(buildTouchOverlay())
        applyNote()
        clockBox.post { applyClockTransformNow() } // apply saved position/size once laid out

        // Run clock/night + weather + shimmer from the start so they're alive even
        // during the initial "Loading…" wait before the first photo arrives.
        startClock()
        if (showClock) {
            startWeather()
        }
        startFortune()
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

    /** Update the sticky-note text live (e.g. after it changed over ADB / in Settings). */
    fun setNote(text: String?) {
        noteText = text ?: ""
        applyNote()
    }

    /**
     * Show the note when there's something to say and we're not in low-light clock-only mode. A
     * manually-set note wins; otherwise, in fortune mode, the fetched wisdom line is shown.
     */
    private fun applyNote() {
        if (!notesEnabled) {
            noteBox.visibility = View.GONE
            return
        }
        val manual = noteText.trim()
        val text = when {
            manual.isNotEmpty() -> manual
            fortuneEnabled -> fortuneLine?.trim().orEmpty()
            else -> ""
        }
        if (text.isEmpty() || clockOnly) {
            noteBox.visibility = View.GONE
        } else {
            noteBox.text = text
            noteBox.visibility = View.VISIBLE
            noteBox.translationX = noteDx * reqW
            noteBox.translationY = noteDy * reqH
        }
    }

    // ------------------------------------------------ sticky-note drag / dismiss

    /** True if (x,y) in root coords falls on the note (padded a little for an easy grab). */
    private fun isOnNote(x: Float, y: Float): Boolean {
        if (noteBox.visibility != View.VISIBLE || noteBox.width == 0) return false
        val pad = Ui.dp(context, 10f)
        val l = noteBox.left + noteBox.translationX - pad
        val t = noteBox.top + noteBox.translationY - pad
        val r = noteBox.left + noteBox.translationX + noteBox.width + pad
        val b = noteBox.top + noteBox.translationY + noteBox.height + pad
        return x >= l && x <= r && y >= t && y <= b
    }

    /** Move the note by a touch delta, clamped so its centre stays on screen. */
    private fun dragNoteBy(dx: Float, dy: Float) {
        val baseCx = noteBox.left + noteBox.width / 2f
        val baseCy = noteBox.top + noteBox.height / 2f
        val edge = Ui.dp(context, 8f).toFloat()
        val w = if (reqW > 0) reqW.toFloat() else noteBox.rootView.width.toFloat()
        val h = if (reqH > 0) reqH.toFloat() else noteBox.rootView.height.toFloat()
        val tx = (noteBox.translationX + dx).coerceIn(edge - baseCx, w - edge - baseCx)
        val ty = (noteBox.translationY + dy).coerceIn(edge - baseCy, h - edge - baseCy)
        noteBox.translationX = tx
        noteBox.translationY = ty
        if (w > 0) noteDx = tx / w
        if (h > 0) noteDy = ty / h
    }

    private fun persistNotePos() {
        context.getSharedPreferences(ConfigReceiver.PREFS, Context.MODE_PRIVATE).edit()
            .putFloat(ConfigReceiver.KEY_NOTE_DX, noteDx)
            .putFloat(ConfigReceiver.KEY_NOTE_DY, noteDy)
            .apply()
    }

    /** Reveal/hide the trash target while the note is being dragged. */
    private fun showBin() {
        binBox.scaleX = 1f
        binBox.scaleY = 1f
        binBox.background = binPill(false)
        binBox.alpha = 0f
        binBox.visibility = View.VISIBLE
        binBox.animate().alpha(1f).setDuration(140).start()
    }

    private fun hideBin() {
        binBox.animate().alpha(0f).setDuration(140).withEndAction {
            binBox.visibility = View.GONE
        }.start()
        noteBox.alpha = 1f
    }

    /** True if the note's centre is over the bin (with a generous catch radius). */
    private fun noteOverBin(): Boolean {
        if (binBox.width == 0) return false
        val ncx = noteBox.left + noteBox.translationX + noteBox.width / 2f
        val ncy = noteBox.top + noteBox.translationY + noteBox.height / 2f
        val bcx = binBox.left + binBox.width / 2f
        val bcy = binBox.top + binBox.height / 2f
        val r = binBox.width / 2f + Ui.dp(context, 28f)
        return Math.hypot((ncx - bcx).toDouble(), (ncy - bcy).toDouble()) <= r
    }

    /** Update the bin's hover affordance (enlarge + redden, fade the note) as it's dragged over. */
    private fun updateBinHover() {
        val over = noteOverBin()
        val scale = if (over) 1.22f else 1f
        binBox.scaleX = scale
        binBox.scaleY = scale
        binBox.background = binPill(over)
        noteBox.alpha = if (over) 0.5f else 1f
    }

    /** Clear the note (text + position) and animate it away. */
    private fun dismissNote() {
        noteText = ""
        fortuneLine = null // a dismissed fortune stays gone until the next refresh brings a new one
        noteDx = 0f
        noteDy = 0f
        context.getSharedPreferences(ConfigReceiver.PREFS, Context.MODE_PRIVATE).edit()
            .putString(ConfigReceiver.KEY_NOTE, "")
            .putFloat(ConfigReceiver.KEY_NOTE_DX, 0f)
            .putFloat(ConfigReceiver.KEY_NOTE_DY, 0f)
            .apply()
        noteBox.animate().alpha(0f).scaleX(0.4f).scaleY(0.4f).setDuration(200).withEndAction {
            noteBox.visibility = View.GONE
            noteBox.alpha = 1f
            noteBox.scaleX = 1f
            noteBox.scaleY = 1f
            noteBox.translationX = 0f
            noteBox.translationY = 0f
        }.start()
    }

    /** Pill background for the bin; reddened while the note hovers it. */
    private fun binPill(hover: Boolean): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(if (hover) 0xE6D23B3B.toInt() else 0xCC1E1E1E.toInt())
        g.cornerRadius = Ui.dp(context, 20f).toFloat()
        return g
    }

    // ------------------------------------------------ clock move/resize

    /** Re-read the clock transform from prefs and apply it (e.g. after a Settings "reset"). */
    fun applyClockTransform() {
        val p = context.getSharedPreferences(ConfigReceiver.PREFS, Context.MODE_PRIVATE)
        clockDx = p.getFloat(ConfigReceiver.KEY_CLOCK_DX, ConfigReceiver.DEFAULT_CLOCK_DX)
        clockDy = p.getFloat(ConfigReceiver.KEY_CLOCK_DY, ConfigReceiver.DEFAULT_CLOCK_DY)
        clockScale = p.getFloat(ConfigReceiver.KEY_CLOCK_SCALE, ConfigReceiver.DEFAULT_CLOCK_SCALE)
        clockBox.post { applyClockTransformNow() }
    }

    private fun applyClockTransformNow() {
        clockBox.pivotX = clockBox.width / 2f
        clockBox.pivotY = clockBox.height / 2f
        clockBox.scaleX = clockScale
        clockBox.scaleY = clockScale
        clockBox.translationX = clockDx * reqW
        clockBox.translationY = clockDy * reqH
    }

    private fun persistClockTransform() {
        context.getSharedPreferences(ConfigReceiver.PREFS, Context.MODE_PRIVATE).edit()
            .putFloat(ConfigReceiver.KEY_CLOCK_DX, clockDx)
            .putFloat(ConfigReceiver.KEY_CLOCK_DY, clockDy)
            .putFloat(ConfigReceiver.KEY_CLOCK_SCALE, clockScale)
            .apply()
    }

    /** True if (x,y) in root coords falls on the clock (its scaled bounds, padded for easy grab). */
    private fun isOnClock(x: Float, y: Float): Boolean {
        if (clockBox.width == 0) return false
        val pad = Ui.dp(context, 24f)
        val cx = clockBox.left + clockBox.translationX + clockBox.width / 2f
        val cy = clockBox.top + clockBox.translationY + clockBox.height / 2f
        val halfW = clockBox.width / 2f * clockScale + pad
        val halfH = clockBox.height / 2f * clockScale + pad
        return x >= cx - halfW && x <= cx + halfW && y >= cy - halfH && y <= cy + halfH
    }

    private fun enterClockEdit() {
        editingClock = true
        clockBox.background = Ui.roundRect(0x33000000, Ui.dp(context, 14f)).apply {
            setStroke(Ui.dp(context, 2f), Ui.BLUE)
        }
        clockEditHint.visibility = View.VISIBLE
    }

    private fun exitClockEdit() {
        editingClock = false
        clockBox.background = null
        clockEditHint.visibility = View.GONE
        persistClockTransform()
    }

    /** Move the clock by a touch delta, clamped so its centre stays on screen. */
    private fun dragClockBy(dx: Float, dy: Float) {
        val baseCx = clockBox.left + clockBox.width / 2f
        val baseCy = clockBox.top + clockBox.height / 2f
        val edge = Ui.dp(context, 8f).toFloat()
        val w = if (reqW > 0) reqW.toFloat() else clockBox.rootView.width.toFloat()
        val h = if (reqH > 0) reqH.toFloat() else clockBox.rootView.height.toFloat()
        val tx = (clockBox.translationX + dx).coerceIn(edge - baseCx, w - edge - baseCx)
        val ty = (clockBox.translationY + dy).coerceIn(edge - baseCy, h - edge - baseCy)
        clockBox.translationX = tx
        clockBox.translationY = ty
        if (w > 0) clockDx = tx / w
        if (h > 0) clockDy = ty / h
    }

    private fun applyClockScale() {
        clockBox.pivotX = clockBox.width / 2f
        clockBox.pivotY = clockBox.height / 2f
        clockBox.scaleX = clockScale
        clockBox.scaleY = clockScale
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
            private var lastX = 0f
            private var lastY = 0f
            private var handled = false
            private var moved = false
            private var pinching = false
            private var pinchStartDist = 0f
            private var pinchBaseScale = 1f
            private var pendingLong: Runnable? = null

            private fun cancelLong() {
                pendingLong?.let {
                    handler.removeCallbacks(it)
                    pendingLong = null
                }
            }

            private fun twoPointerDist(e: MotionEvent): Float {
                if (e.pointerCount < 2) return 0f
                return Math.hypot(
                    (e.getX(0) - e.getX(1)).toDouble(), (e.getY(0) - e.getY(1)).toDouble(),
                ).toFloat()
            }

            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = e.x; downY = e.y; lastX = e.x; lastY = e.y
                        downTime = e.eventTime
                        handled = false
                        moved = false
                        pinching = false
                        v.parent?.requestDisallowInterceptTouchEvent(true)
                        cancelLong()
                        if (!editingClock && isOnNote(e.x, e.y)) {
                            // Touch started on the note → drag it directly (no long-press); the
                            // bin appears so it can be dropped there to dismiss.
                            draggingNote = true
                            showBin()
                            return true
                        }
                        if (!editingClock) {
                            // Long-press ON the clock → grab it for edit; elsewhere → settings.
                            val onClock = isOnClock(e.x, e.y)
                            if (onClock || onSettings != null) {
                                val pl = Runnable {
                                    pendingLong = null
                                    handled = true // suppress the tap-dismiss on release
                                    if (onClock) enterClockEdit() else onSettings?.run()
                                }
                                pendingLong = pl
                                handler.postDelayed(pl, LONG_PRESS_MS)
                            }
                        }
                        return true
                    }
                    MotionEvent.ACTION_POINTER_DOWN -> {
                        if (editingClock) {
                            cancelLong()
                            pinching = true
                            pinchStartDist = twoPointerDist(e)
                            pinchBaseScale = clockScale
                        }
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (draggingNote) {
                            dragNoteBy(e.x - lastX, e.y - lastY)
                            lastX = e.x; lastY = e.y
                            updateBinHover()
                            return true
                        }
                        if (editingClock) {
                            if (pinching && e.pointerCount >= 2) {
                                val d = twoPointerDist(e)
                                if (pinchStartDist > 0f) {
                                    clockScale = (pinchBaseScale * d / pinchStartDist)
                                        .coerceIn(CLOCK_SCALE_MIN, CLOCK_SCALE_MAX)
                                    applyClockScale()
                                }
                            } else {
                                dragClockBy(e.x - lastX, e.y - lastY)
                                lastX = e.x; lastY = e.y
                                if (abs(e.x - downX) > TAP_SLOP || abs(e.y - downY) > TAP_SLOP) moved = true
                            }
                        } else if (!handled) {
                            val dx = e.x - downX
                            val dy = e.y - downY
                            if (abs(dx) > TAP_SLOP || abs(dy) > TAP_SLOP) {
                                cancelLong() // finger moved — not a long press
                            }
                            if (abs(dx) > SWIPE_MIN_DISTANCE && abs(dx) > abs(dy)) {
                                handled = true
                                if (dx < 0) showNext() else showPrevious()
                            }
                        }
                        return true
                    }
                    MotionEvent.ACTION_POINTER_UP -> {
                        if (editingClock && pinching) {
                            persistClockTransform()
                            pinching = false
                            // Continue dragging with whichever pointer remains (avoid a jump).
                            val rem = if (e.actionIndex == 0) 1 else 0
                            if (e.pointerCount > rem) { lastX = e.getX(rem); lastY = e.getY(rem) }
                            moved = true
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        cancelLong()
                        if (draggingNote) {
                            draggingNote = false
                            if (noteOverBin()) dismissNote() else persistNotePos()
                            hideBin()
                            return true
                        }
                        if (editingClock) {
                            val dt = e.eventTime - downTime
                            if (!moved && !pinching && abs(e.x - downX) < TAP_SLOP &&
                                abs(e.y - downY) < TAP_SLOP && dt < TAP_TIMEOUT_MS
                            ) {
                                exitClockEdit() // a clean tap finishes editing
                            } else {
                                persistClockTransform() // a drag/pinch ended — stay in edit mode
                            }
                            pinching = false
                        } else if (!handled) {
                            val dx = e.x - downX
                            val dy = e.y - downY
                            val dt = e.eventTime - downTime
                            if (abs(dx) > SWIPE_MIN_DISTANCE && abs(dx) > abs(dy)) {
                                if (dx < 0) showNext() else showPrevious()
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
                        if (draggingNote) { draggingNote = false; persistNotePos(); hideBin() }
                        if (editingClock) { pinching = false; persistClockTransform() }
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
        startFortune()
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
        handler.removeCallbacks(fortuneTick)
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
        applyNote() // hide the note in clock-only mode, restore it otherwise
        if (on) {
            handler.removeCallbacks(autoTick) // pause advancing
            shimmer.stopSweep()
            blank() // photos -> black
            clockBox.visibility = View.GONE // hide the bottom overlay clock
            clockOnlyScrim.visibility = View.VISIBLE // black backing covers any photo underneath
            clockOnlyBox.visibility = View.VISIBLE // big centered clock instead
            startClock() // ensure ticking + populate the big clock now
        } else {
            clockOnlyBox.visibility = View.GONE
            clockOnlyScrim.visibility = View.GONE
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
        startFortune()
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

    /**
     * Replace the photo set and immediately show [showId] — the just-pushed photo — so a
     * photo dropped from a phone appears on the frame right away (the "AirDrop" tada),
     * instead of waiting for the rotation to reach it. Falls back to the first item if
     * [showId] isn't present.
     */
    fun setItemsShowing(newItems: List<Slide>?, showId: String, instant: Boolean = false) {
        if (newItems.isNullOrEmpty()) {
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
        // Set index/running BEFORE the clock-only return so that when light returns,
        // setClockOnly(false) calls showImmediate(index) with a valid index for the new list.
        val target = items.indexOfFirst { it.id == showId }.let { if (it < 0) 0 else it }
        index = target
        running = true
        if (clockOnly) {
            return // keep showing the clock; the new photo displays when light returns
        }
        handler.removeCallbacks(autoTick)
        startClock()
        if (showClock) {
            startWeather()
        }
        startFortune()
        if (!shimmerHidden) {
            shimmer.startSweep()
        }
        if (instant) {
            // Resume: show the photo straight away (no fade-from-black gap).
            showImmediate(target)
        } else {
            // Live in-room push: crossfade from the current photo into the just-pushed one.
            transitionTo(target, autoFadeMs)
        }
    }

    /** The id of the photo currently displayed, or null if there isn't one. */
    fun currentId(): String? = items.getOrNull(index)?.id

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
        // Run the pan/zoom over the hold (+ the outgoing fade), but cap it: with long "time per
        // photo" values (up to a day) an uncapped animator would run a multi-hour ValueAnimator at
        // ~60fps. Past the cap the motion has finished and the image simply holds at its end frame.
        a.duration = min(max(intervalMs, 1200L) + autoFadeMs, KEN_BURNS_MAX_MS)
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
        trimLeftBearing(clock)
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
        trimLeftBearing(dateLine)
    }

    /**
     * Remove a TextView's first-glyph left side-bearing by setting a matching negative left padding,
     * so the visible text starts flush at the view's left edge. Applied to the clock and the date
     * line (which use different fonts) so their left edges line up exactly.
     */
    private fun trimLeftBearing(tv: TextView) {
        val s = tv.text?.toString() ?: return
        if (s.isEmpty()) return
        val r = Rect()
        tv.paint.getTextBounds(s, 0, 1, r)
        tv.setPadding(-r.left, 0, 0, 0)
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

    private val fortuneTick = object : Runnable {
        override fun run() {
            refreshFortune()
            handler.postDelayed(this, FORTUNE_INTERVAL_MS)
        }
    }

    /** Begin (or re-arm) the periodic fortune refresh — only when fortune mode is active. */
    private fun startFortune() {
        if (!notesEnabled || !fortuneEnabled) {
            return
        }
        handler.removeCallbacks(fortuneTick)
        handler.postDelayed(fortuneTick, if (fortuneLine == null) 0 else FORTUNE_INTERVAL_MS)
    }

    private fun refreshFortune() {
        // Skip while a manual note is showing — it takes precedence over the fortune.
        if (!fortuneEnabled || noteText.isNotBlank()) {
            return
        }
        loader.executor().execute {
            val line = Fortune.fetch() ?: Fortune.bundled()
            handler.post {
                fortuneLine = line
                applyNote()
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

    /**
     * A post-it look: a rounded paper rect with a top→bottom amber [top]→[bottom] gradient, a
     * folded dog-ear at the bottom-right ([fold] = its underside shade), and a translucent
     * [tape] strip across the top. Implements [getOutline] so the host View still casts a
     * rounded drop shadow under its elevation.
     */
    private class StickyNoteBg(
        private val top: Int,
        private val bottom: Int,
        private val fold: Int,
        private val tape: Int,
        private val radiusPx: Float,
        private val foldPx: Float,
    ) : Drawable() {
        private val paperPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val foldPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fold }
        private val foldShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x22000000 }
        private val tapePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = tape }
        private val body = Path()
        private val flap = Path()

        override fun onBoundsChange(b: Rect) {
            paperPaint.shader = LinearGradient(
                0f, b.top.toFloat(), 0f, b.bottom.toFloat(), top, bottom, Shader.TileMode.CLAMP,
            )
            val l = b.left.toFloat()
            val t = b.top.toFloat()
            val r = b.right.toFloat()
            val bot = b.bottom.toFloat()
            val rad = radiusPx
            val f = foldPx
            // Paper outline: rounded TL/TR/BL corners, with the BR corner chamfered for the fold.
            body.reset()
            body.moveTo(l + rad, t)
            body.lineTo(r - rad, t)
            body.quadTo(r, t, r, t + rad)
            body.lineTo(r, bot - f)
            body.lineTo(r - f, bot)
            body.lineTo(l + rad, bot)
            body.quadTo(l, bot, l, bot - rad)
            body.lineTo(l, t + rad)
            body.quadTo(l, t, l + rad, t)
            body.close()
            // The folded-up corner triangle (the lifted flap, showing the paper's underside).
            flap.reset()
            flap.moveTo(r - f, bot)
            flap.lineTo(r, bot - f)
            flap.lineTo(r - f, bot - f)
            flap.close()
        }

        override fun draw(canvas: Canvas) {
            val b = bounds
            canvas.drawPath(body, paperPaint)
            // A faint shadow under the diagonal fold edge gives the corner depth.
            canvas.drawPath(flap, foldShadow)
            canvas.drawPath(flap, foldPaint)
            // Tape: a short translucent strip, slightly tilted, centred over the top edge.
            val cx = b.exactCenterX()
            val tapeW = (b.width() * 0.34f).coerceAtMost(foldPx * 4f)
            val tapeH = foldPx * 0.62f
            val cy = b.top + tapeH * 0.55f
            canvas.save()
            canvas.rotate(-7f, cx, cy)
            canvas.drawRoundRect(
                cx - tapeW / 2f, cy - tapeH / 2f, cx + tapeW / 2f, cy + tapeH / 2f,
                radiusPx * 0.6f, radiusPx * 0.6f, tapePaint,
            )
            canvas.restore()
        }

        override fun getOutline(outline: Outline) {
            outline.setRoundRect(bounds, radiusPx)
            outline.alpha = 1f
        }

        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: ColorFilter?) {}
        @Deprecated("required override", ReplaceWith("PixelFormat.TRANSLUCENT"))
        override fun getOpacity() = PixelFormat.TRANSLUCENT
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
        // Cap the Ken Burns animation length so long "time per photo" values (up to a day) don't
        // run a multi-hour ValueAnimator; past this the motion holds at its end frame.
        private const val KEN_BURNS_MAX_MS = 30_000L
        private const val CLOCK_SCALE_MIN = 0.5f // pinch-to-resize bounds for the clock widget
        private const val CLOCK_SCALE_MAX = 3.0f
        private const val SWIPE_FADE_MS = 300L // manual swipe fade
        private const val SWIPE_MIN_DISTANCE = 60f
        private const val TAP_SLOP = 30f
        private const val TAP_TIMEOUT_MS = 350L
        private const val LONG_PRESS_MS = 700L // hold to open Photos setup
        private const val WEATHER_INTERVAL_MS = 30 * 60 * 1000L // refresh weather
        private const val FORTUNE_INTERVAL_MS = 60 * 60 * 1000L // a fresh wisdom line each hour

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
