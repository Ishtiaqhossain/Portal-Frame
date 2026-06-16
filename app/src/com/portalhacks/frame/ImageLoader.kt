package com.portalhacks.frame

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
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

    fun load(id: String, reqW: Int, reqH: Int, cb: Callback) {
        val cached = mem.get(id)
        if (cached != null) {
            cb.onLoaded(cached)
            return
        }
        io.execute {
            val bmp = loadSync(id, reqW, reqH)
            if (bmp != null) {
                mem.put(id, bmp)
            }
            main.post { cb.onLoaded(bmp) }
        }
    }

    /** Warm the cache for an id without a UI callback. */
    fun prefetch(id: String, reqW: Int, reqH: Int) {
        if (mem.get(id) != null) {
            return
        }
        io.execute {
            val b = loadSync(id, reqW, reqH)
            if (b != null) {
                mem.put(id, b)
            }
        }
    }

    private fun loadSync(id: String, reqW: Int, reqH: Int): Bitmap? {
        return try {
            val raw = decodeRaw(id, reqW, reqH) ?: return null
            composeFill(raw, reqW, reqH)
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

    private fun decodeFile(path: String, reqW: Int, reqH: Int): Bitmap? {
        val o = BitmapFactory.Options()
        o.inJustDecodeBounds = true
        BitmapFactory.decodeFile(path, o)
        o.inSampleSize = sampleSize(o.outWidth, o.outHeight, reqW, reqH)
        o.inJustDecodeBounds = false
        return BitmapFactory.decodeFile(path, o)
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
         * Compose [src] into a screen-sized frame, center-cropped to fill it: the photo is zoomed
         * until it covers the whole frame, cropping the overflow on its longer axis. No letterbox
         * bars and no blurred bleed.
         */
        private fun composeFill(src: Bitmap?, screenW: Int, screenH: Int): Bitmap? {
            if (src == null || screenW <= 0 || screenH <= 0) {
                return src
            }
            val out = Bitmap.createBitmap(screenW, screenH, Bitmap.Config.ARGB_8888)
            val c = Canvas(out)
            val p = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
            drawComposed(c, p, src, 0, 0, screenW, screenH)
            if (src != out) {
                src.recycle()
            }
            return out
        }

        /**
         * Two photos sharing the screen, each center-cropped to fill its half, with a thin black
         * seam between. [stackVertical] splits top/bottom (landscape photos on a vertical screen);
         * otherwise side-by-side (portrait photos on a landscape screen). Recycles [a]/[b].
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
                drawComposed(c, p, a, 0, 0, screenW, half)
                drawComposed(c, p, b, 0, half + gap, screenW, screenH - half - gap)
                c.drawRect(0f, half.toFloat(), screenW.toFloat(), (half + gap).toFloat(), seam)
            } else {
                val gap = max(2, screenW / 300)
                val half = (screenW - gap) / 2
                drawComposed(c, p, a, 0, 0, half, screenH)
                drawComposed(c, p, b, half + gap, 0, screenW - half - gap, screenH)
                c.drawRect(half.toFloat(), 0f, (half + gap).toFloat(), screenH.toFloat(), seam)
            }
            a.recycle()
            b.recycle()
            return out
        }

        /**
         * Draw [src] into the rect ([left],[top],[w]×[h]), center-cropped to FILL it: the photo is
         * zoomed until it covers the whole rect, with the overflow on the longer axis cropped off.
         * No letterbox bars and no blurred bleed. Does not recycle [src].
         */
        private fun drawComposed(
            c: Canvas, p: Paint, src: Bitmap?,
            left: Int, top: Int, w: Int, h: Int
        ) {
            if (src == null || w <= 0 || h <= 0) {
                return
            }
            val dst = Rect(left, top, left + w, top + h)
            val crop = centerCropRect(src.width, src.height, w, h)
            c.drawBitmap(src, crop, dst, p)
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
