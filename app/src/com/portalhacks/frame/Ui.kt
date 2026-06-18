package com.portalhacks.frame

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import java.io.File
import kotlin.math.roundToInt

/**
 * Portal design-system helpers (see Meta's "portal" skill: design-guidelines.md /
 * compose-theme.md), translated for this dependency-free, view-based app.
 *
 * Dark-forced theme (Portal's white system-overlay pills stay legible, matches the
 * native launcher/Settings), Portal platform palette, bundled Inter typography. Used
 * by the QR setup screen ([PhotosActivity]) and the slideshow clock overlay
 * ([SlideshowController]); the Compose Settings UI has its own theme.
 */
internal object Ui {

    // ---- Palette: Portal platform tokens, dark theme (never pure #000/#FFF) ----
    const val BLUE = 0xFF1990FF.toInt()          // primary actions, selected
    const val BG = 0xFF1A1A1A.toInt()            // app background
    const val FIELD = 0xFF202020.toInt()         // input fields
    const val TEXT = 0xFFEDEDED.toInt()          // headings
    const val TEXT_MUTED = 0xFFBEC6DC.toInt()    // captions / secondary

    // ---- Inter typefaces (bundled in assets; graceful fallback) ----
    private var sRegular: Typeface? = null
    private var sMedium: Typeface? = null
    private var sBold: Typeface? = null

    fun regular(c: Context): Typeface {
        if (sRegular == null) sRegular = load(c, "fonts/inter_regular.ttf", "sans-serif")
        return sRegular!!
    }

    fun medium(c: Context): Typeface {
        if (sMedium == null) sMedium = load(c, "fonts/inter_medium.ttf", "sans-serif-medium")
        return sMedium!!
    }

    fun bold(c: Context): Typeface {
        if (sBold == null) sBold = load(c, "fonts/inter_bold.ttf", "sans-serif-medium")
        return sBold!!
    }

    private fun load(c: Context, asset: String, fallbackFamily: String): Typeface {
        return try {
            Typeface.createFromAsset(c.assets, asset)
        } catch (e: Exception) {
            Typeface.create(fallbackFamily, Typeface.NORMAL)
        }
    }

    private var sClock: Typeface? = null

    /**
     * The Portal system clock font — Meta's "Optimistic" (Display, Light), loaded
     * straight from /system/fonts (world-readable) so our clock matches the native
     * home/screensaver clock exactly. Falls back to the bundled regular face on
     * non-Portal devices.
     */
    fun clockFace(c: Context): Typeface {
        if (sClock == null) {
            sClock = loadFile("/system/fonts/Optimistic_Display_A_Lt.ttf", c)
        }
        return sClock!!
    }

    private fun loadFile(path: String, c: Context): Typeface {
        try {
            val f = File(path)
            if (f.exists()) {
                return Typeface.createFromFile(f)
            }
        } catch (ignored: Exception) {
        }
        return regular(c)
    }

    fun dp(c: Context, v: Float): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, v, c.resources.displayMetrics
        ).roundToInt()
    }

    /** Int convenience (Kotlin won't auto-widen Int→Float at call sites). */
    fun dp(c: Context, v: Int): Int = dp(c, v.toFloat())

    fun field(c: Context, hint: String): EditText {
        val e = EditText(c)
        e.hint = hint
        e.setHintTextColor(TEXT_MUTED)
        e.setTextColor(TEXT)
        e.typeface = regular(c)
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        e.background = roundRect(FIELD, dp(c, 14))
        val padH = dp(c, 18)
        val padV = dp(c, 16)
        e.setPadding(padH, padV, padH, padV)
        e.minHeight = dp(c, 64)
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.topMargin = dp(c, 16)
        e.layoutParams = lp
        return e
    }

    /** A filled crescent-moon bitmap (for the night weather glyph), [color]-tinted. */
    fun crescent(sizePx: Int, color: Int): Bitmap {
        val b = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val cv = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = color
        val r = sizePx / 2f
        cv.drawCircle(r, r, r, p)
        // Carve an offset disc to leave a crescent.
        p.xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        cv.drawCircle(r + r * 0.52f, r - r * 0.16f, r * 0.95f, p)
        p.xfermode = null
        return b
    }

    // ---- drawable helpers ----
    fun roundRect(color: Int, radiusPx: Int): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(color)
        g.cornerRadius = radiusPx.toFloat()
        return g
    }
}
