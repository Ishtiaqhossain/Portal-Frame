package com.portalhacks.frame

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.media.ExifInterface
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.LruCache
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.math.BigInteger
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Loads bitmaps off the main thread for both bundled assets ("slides/01.png")
 * and remote URLs ("https://..."). Remote bytes are cached on disk (so later
 * slideshow loops are instant and survive reboots) and decoded downsampled to
 * the screen size. A small in-memory LRU caches recently shown bitmaps.
 */
class ImageLoader(context: Context) {

    fun interface Callback {
        fun onLoaded(b: Bitmap?)
    }

    private val ctx: Context = context.applicationContext
    private val io: ExecutorService = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())
    private val mem: LruCache<String, Bitmap>
    private val cacheDir: File

    init {
        val maxKb = (Runtime.getRuntime().maxMemory() / 1024).toInt()
        mem = object : LruCache<String, Bitmap>(maxKb / 6) {
            override fun sizeOf(key: String, value: Bitmap): Int {
                return value.byteCount / 1024
            }
        }
        cacheDir = File(ctx.cacheDir, "photos")
        cacheDir.mkdirs()
    }

    /** Shared background pool — reused for album fetches too. */
    fun executor(): ExecutorService {
        return io
    }

    /** Stop the background pool. Call from an owning Activity's onDestroy so its two
     *  worker threads don't leak each time the screen is opened and closed. */
    fun shutdown() {
        io.shutdown()
    }

    fun load(id: String, reqW: Int, reqH: Int, zoomFill: Boolean, cb: Callback) {
        val key = fillKey(id, zoomFill)
        val cached = mem.get(key)
        if (cached != null) {
            cb.onLoaded(cached)
            return
        }
        io.execute {
            val bmp = loadSync(id, reqW, reqH, zoomFill)
            if (bmp != null) {
                mem.put(key, bmp)
            }
            main.post { cb.onLoaded(bmp) }
        }
    }

    /** Warm the cache for an id without a UI callback. */
    fun prefetch(id: String, reqW: Int, reqH: Int, zoomFill: Boolean) {
        val key = fillKey(id, zoomFill)
        if (mem.get(key) != null) {
            return
        }
        io.execute {
            val b = loadSync(id, reqW, reqH, zoomFill)
            if (b != null) {
                mem.put(key, b)
            }
        }
    }

    /** Cache key that distinguishes the two fill modes (zoom-crop vs whole photo + blur). */
    private fun fillKey(id: String, zoomFill: Boolean): String = if (zoomFill) "$id|z" else "$id|f"

    private fun loadSync(id: String, reqW: Int, reqH: Int, zoomFill: Boolean): Bitmap? {
        return try {
            val raw = decodeRaw(id, reqW, reqH) ?: return null
            composeFill(raw, reqW, reqH, zoomFill)
        } catch (e: Exception) {
            Log.e(TAG, "load failed $id", e)
            null
        }
    }

    /** Decode the source bitmap (downloading + disk-caching remote ids first). */
    @Throws(Exception::class)
    private fun decodeRaw(id: String, reqW: Int, reqH: Int): Bitmap? {
        if (id.startsWith("http")) {
            val f = File(cacheDir, md5(id) + ".img")
            if (!f.exists() || f.length() == 0L) {
                if (!download(id, f)) {
                    return null
                }
            }
            return decodeFile(f.absolutePath, reqW, reqH)
        }
        // Locally-pushed photos (the "AirDrop for Portal" drop) are absolute file paths
        // under filesDir/uploads — decode them straight off disk, honoring their EXIF
        // orientation (camera-roll photos store rotation in EXIF; the CDN images don't).
        if (id.startsWith("/")) {
            return decodeFile(id, reqW, reqH, applyExif = true)
        }
        return decodeAsset(id, reqW, reqH)
    }

    /**
     * Load two portrait photos composed side-by-side into one screen-sized frame
     * ("show pairs"). Cached in memory under a combined key; falls back to null on
     * failure so the caller can skip the pair.
     */
    fun loadPair(
        id1: String, id2: String,
        reqW: Int, reqH: Int, stackVertical: Boolean, cb: Callback
    ) {
        // A paired photo only gets half the screen, so it's always center-cropped to fill its
        // half — the "zoom to fill" setting applies only to single, full-screen photos.
        val key = "$id1|$id2|${if (stackVertical) "v" else "h"}"
        val cached = mem.get(key)
        if (cached != null) {
            cb.onLoaded(cached)
            return
        }
        io.execute {
            var out: Bitmap? = null
            var a: Bitmap? = null
            var b: Bitmap? = null
            try {
                // Each photo gets half the screen: half-height when stacked top/bottom (vertical
                // screen), half-width when side-by-side (landscape screen).
                if (stackVertical) {
                    a = decodeRaw(id1, reqW, reqH / 2)
                    b = decodeRaw(id2, reqW, reqH / 2)
                } else {
                    a = decodeRaw(id1, reqW / 2, reqH)
                    b = decodeRaw(id2, reqW / 2, reqH)
                }
                if (a != null && b != null) {
                    out = composePair(a, b, reqW, reqH, stackVertical) // recycles a and b
                    a = null
                    b = null
                }
            } catch (e: Exception) {
                Log.e(TAG, "loadPair failed $key", e)
            } finally {
                a?.recycle()
                b?.recycle()
            }
            val result = out
            if (result != null) {
                mem.put(key, result)
            }
            main.post { cb.onLoaded(result) }
        }
    }

    private fun decodeFile(path: String, reqW: Int, reqH: Int, applyExif: Boolean = false): Bitmap? {
        val o = BitmapFactory.Options()
        o.inJustDecodeBounds = true
        BitmapFactory.decodeFile(path, o)
        o.inSampleSize = sampleSize(o.outWidth, o.outHeight, reqW, reqH)
        o.inJustDecodeBounds = false
        val bmp = BitmapFactory.decodeFile(path, o) ?: return null
        return if (applyExif) applyExifOrientation(bmp, path) else bmp
    }

    /**
     * Rotate/flip [bmp] to match the photo's EXIF orientation. Phone camera-roll photos store
     * their rotation in EXIF (the raw pixels are unrotated) and BitmapFactory ignores it, so
     * without this a portrait photo pushed from a phone shows up sideways.
     */
    private fun applyExifOrientation(bmp: Bitmap, path: String): Bitmap {
        val orientation = try {
            ExifInterface(path).getAttributeInt(
                ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL,
            )
        } catch (e: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
            else -> return bmp // normal / undefined — nothing to do
        }
        return try {
            val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            if (rotated != bmp) bmp.recycle()
            rotated
        } catch (e: Throwable) {
            // Throwable, not Exception: createBitmap can throw OutOfMemoryError (an Error).
            // Fall back to the unrotated bitmap rather than crashing the process.
            bmp
        }
    }

    @Throws(Exception::class)
    private fun decodeAsset(assetPath: String, reqW: Int, reqH: Int): Bitmap? {
        val o = BitmapFactory.Options()
        o.inJustDecodeBounds = true
        var `in` = ctx.assets.open(assetPath)
        BitmapFactory.decodeStream(`in`, null, o)
        `in`.close()
        o.inSampleSize = sampleSize(o.outWidth, o.outHeight, reqW, reqH)
        o.inJustDecodeBounds = false
        `in` = ctx.assets.open(assetPath)
        val b = BitmapFactory.decodeStream(`in`, null, o)
        `in`.close()
        return b
    }

    private fun sampleSize(w: Int, h: Int, reqW: Int, reqH: Int): Int {
        if (reqW <= 0 || reqH <= 0 || w <= 0 || h <= 0) {
            return 1
        }
        var s = 1
        while (w / (s * 2) >= reqW && h / (s * 2) >= reqH) {
            s *= 2
        }
        return s
    }

    private fun download(urlStr: String?, dest: File): Boolean {
        if (urlStr == null || !urlStr.startsWith("https://")) {
            Log.w(TAG, "refusing non-HTTPS image URL")
            return false
        }
        var c: HttpURLConnection? = null
        try {
            c = URL(urlStr).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = true
            c.connectTimeout = 15000
            c.readTimeout = 20000
            c.setRequestProperty("User-Agent", UA)
            val code = c.responseCode
            if (code != 200) {
                Log.w(TAG, "image http $code for $urlStr")
                return false
            }
            val tmp = File(dest.absolutePath + ".tmp")
            val `in` = BufferedInputStream(c.inputStream)
            val out = FileOutputStream(tmp)
            val buf = ByteArray(8192)
            var n: Int
            var total = 0
            while (`in`.read(buf).also { n = it } != -1) {
                total += n
                if (total > MAX_IMAGE_BYTES) {
                    out.close()
                    `in`.close()
                    tmp.delete()
                    Log.w(TAG, "image exceeds $MAX_IMAGE_BYTES bytes: $urlStr")
                    return false
                }
                out.write(buf, 0, n)
            }
            out.flush()
            out.close()
            `in`.close()
            return tmp.renameTo(dest)
        } catch (e: Exception) {
            Log.e(TAG, "download failed $urlStr", e)
            return false
        } finally {
            c?.disconnect()
        }
    }

    companion object {
        private const val TAG = "PortalFrame"

        const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        // Cap a single image download so a hostile/oversized response can't fill the
        // cache disk or blow up decoding (a photo thumbnail is well under this).
        private const val MAX_IMAGE_BYTES = 30 * 1024 * 1024

        /**
         * Compose [src] into a screen-sized frame. [zoomFill] = center-crop to fill (zoom in,
         * cropping the overflow); otherwise the whole photo fit-centred over a blurred fill.
         */
        private fun composeFill(src: Bitmap?, screenW: Int, screenH: Int, zoomFill: Boolean): Bitmap? {
            if (src == null || screenW <= 0 || screenH <= 0) {
                return src
            }
            val out = Bitmap.createBitmap(screenW, screenH, Bitmap.Config.ARGB_8888)
            val c = Canvas(out)
            val p = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
            drawComposed(c, p, src, 0, 0, screenW, screenH, zoomFill)
            if (src != out) {
                src.recycle()
            }
            return out
        }

        /**
         * Two photos sharing the screen, each center-cropped to fill its half (a half-screen photo
         * is always zoomed to fill — the "zoom to fill" setting is only for single photos), with a
         * thin black seam between. [stackVertical] splits top/bottom (landscape photos on a vertical
         * screen); otherwise side-by-side. Recycles [a]/[b].
         */
        private fun composePair(
            a: Bitmap, b: Bitmap, screenW: Int, screenH: Int, stackVertical: Boolean
        ): Bitmap {
            val out = Bitmap.createBitmap(screenW, screenH, Bitmap.Config.ARGB_8888)
            val c = Canvas(out)
            val p = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
            val seam = Paint()
            seam.color = Color.BLACK
            if (stackVertical) {
                val gap = max(2, screenH / 300)
                val half = (screenH - gap) / 2
                drawComposed(c, p, a, 0, 0, screenW, half, true)
                drawComposed(c, p, b, 0, half + gap, screenW, screenH - half - gap, true)
                c.drawRect(0f, half.toFloat(), screenW.toFloat(), (half + gap).toFloat(), seam)
            } else {
                val gap = max(2, screenW / 300)
                val half = (screenW - gap) / 2
                drawComposed(c, p, a, 0, 0, half, screenH, true)
                drawComposed(c, p, b, half + gap, 0, screenW - half - gap, screenH, true)
                c.drawRect(half.toFloat(), 0f, (half + gap).toFloat(), screenH.toFloat(), seam)
            }
            a.recycle()
            b.recycle()
            return out
        }

        /**
         * Draw [src] into the rect ([left],[top],[w]×[h]). [zoomFill] = center-crop to FILL the
         * rect (zoom in, crop the overflow). Otherwise the whole photo is fit-centred (never
         * cropped) over a blurred, center-cropped fill behind a gentle scrim. Does not recycle [src].
         */
        private fun drawComposed(
            c: Canvas, p: Paint, src: Bitmap?,
            left: Int, top: Int, w: Int, h: Int, zoomFill: Boolean
        ) {
            if (src == null || w <= 0 || h <= 0) {
                return
            }
            val dst = Rect(left, top, left + w, top + h)
            if (zoomFill) {
                c.drawBitmap(src, centerCropRect(src.width, src.height, w, h), dst, p)
                return
            }
            // Blurred background: center-crop into a tiny bitmap, blur, draw upscaled.
            val bw = max(1, w / 16)
            val bh = max(1, h / 16)
            val small = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
            Canvas(small).drawBitmap(
                src, centerCropRect(src.width, src.height, bw, bh), Rect(0, 0, bw, bh), p
            )
            boxBlur(small, 3, 2)
            c.drawBitmap(small, Rect(0, 0, bw, bh), dst, p)
            small.recycle()

            val scrim = Paint()
            scrim.color = Color.argb(70, 0, 0, 0) // gentle scrim behind the photo
            c.drawRect(dst, scrim)

            // Sharp foreground: whole photo, fit-centered (no crop).
            val fc = fitCenterRect(src.width, src.height, w, h)
            fc.offset(left.toFloat(), top.toFloat())
            c.drawBitmap(src, null, fc, p)
        }

        private fun centerCropRect(sw: Int, sh: Int, dw: Int, dh: Int): Rect {
            val srcA = sw / sh.toFloat()
            val dstA = dw / dh.toFloat()
            val cw: Int
            val ch: Int
            val cx: Int
            val cy: Int
            if (srcA > dstA) {
                ch = sh
                cw = (sh * dstA).roundToInt()
                cx = (sw - cw) / 2
                cy = 0
            } else {
                cw = sw
                ch = (sw / dstA).roundToInt()
                cx = 0
                cy = (sh - ch) / 2
            }
            return Rect(cx, cy, cx + cw, cy + ch)
        }

        private fun fitCenterRect(sw: Int, sh: Int, dw: Int, dh: Int): RectF {
            val scale = min(dw / sw.toFloat(), dh / sh.toFloat())
            val w = sw * scale
            val h = sh * scale
            val left = (dw - w) / 2f
            val top = (dh - h) / 2f
            return RectF(left, top, left + w, top + h)
        }

        private fun boxBlur(bmp: Bitmap, radius: Int, passes: Int) {
            val w = bmp.width
            val h = bmp.height
            if (w <= 0 || h <= 0) {
                return
            }
            val a = IntArray(w * h)
            val b = IntArray(w * h)
            bmp.getPixels(a, 0, w, 0, 0, w, h)
            for (p in 0 until passes) {
                boxH(a, b, w, h, radius)
                boxV(b, a, w, h, radius)
            }
            bmp.setPixels(a, 0, w, 0, 0, w, h)
        }

        private fun boxH(`in`: IntArray, out: IntArray, w: Int, h: Int, r: Int) {
            for (y in 0 until h) {
                val row = y * w
                for (x in 0 until w) {
                    var aa = 0
                    var rr = 0
                    var gg = 0
                    var bb = 0
                    var cnt = 0
                    val x0 = max(0, x - r)
                    val x1 = min(w - 1, x + r)
                    for (xx in x0..x1) {
                        val c = `in`[row + xx]
                        aa += (c ushr 24) and 0xff
                        rr += (c shr 16) and 0xff
                        gg += (c shr 8) and 0xff
                        bb += c and 0xff
                        cnt++
                    }
                    out[row + x] =
                        ((aa / cnt) shl 24) or ((rr / cnt) shl 16) or ((gg / cnt) shl 8) or (bb / cnt)
                }
            }
        }

        private fun boxV(`in`: IntArray, out: IntArray, w: Int, h: Int, r: Int) {
            for (x in 0 until w) {
                for (y in 0 until h) {
                    var aa = 0
                    var rr = 0
                    var gg = 0
                    var bb = 0
                    var cnt = 0
                    val y0 = max(0, y - r)
                    val y1 = min(h - 1, y + r)
                    for (yy in y0..y1) {
                        val c = `in`[yy * w + x]
                        aa += (c ushr 24) and 0xff
                        rr += (c shr 16) and 0xff
                        gg += (c shr 8) and 0xff
                        bb += c and 0xff
                        cnt++
                    }
                    out[y * w + x] =
                        ((aa / cnt) shl 24) or ((rr / cnt) shl 16) or ((gg / cnt) shl 8) or (bb / cnt)
                }
            }
        }

        private fun md5(s: String): String {
            return try {
                val md = MessageDigest.getInstance("MD5")
                val d = md.digest(s.toByteArray(charset("UTF-8")))
                String.format("%032x", BigInteger(1, d))
            } catch (e: Exception) {
                Integer.toHexString(s.hashCode())
            }
        }
    }
}
