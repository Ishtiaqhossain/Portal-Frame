package com.portalhacks.frame

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicInteger

/**
 * The photos pushed onto this frame over the local network (the "AirDrop for Portal"
 * drop), kept on disk so they survive reboots and cache eviction — this is the "keep"
 * half of push-and-keep. Stored under [Context.getFilesDir]/uploads (persistent, unlike
 * the image cache in cacheDir), one file per photo. Each becomes a [Slide] whose id is
 * the absolute file path, which [ImageLoader] decodes directly.
 */
internal object LocalUploads {
    private const val TAG = "PortalFrame"
    private const val DIR = "uploads"
    private val seq = AtomicInteger(0)

    // Keep the store bounded so a long-running frame can't fill the device.
    private const val MAX_KEPT = 300
    private const val MAX_BYTES = 500L * 1024 * 1024

    /** The (created) directory holding kept uploads. */
    @JvmStatic
    fun dir(ctx: Context): File = File(ctx.filesDir, DIR).apply { mkdirs() }

    /** Kept photo files, newest first. */
    @JvmStatic
    fun files(ctx: Context): List<File> {
        val files = dir(ctx).listFiles { f -> f.isFile && f.length() > 0L && !f.name.endsWith(".tmp") }
            ?: return emptyList()
        return files.sortedByDescending { it.lastModified() }
    }

    /** Kept photos as slides, newest first (capture time = file mtime, for date captions). */
    @JvmStatic
    fun slides(ctx: Context): List<Slide> =
        files(ctx).map { Slide(it.absolutePath, null, it.lastModified(), false) }

    /** Number of kept photos. */
    @JvmStatic
    fun count(ctx: Context): Int =
        dir(ctx).listFiles { f -> f.isFile && !f.name.endsWith(".tmp") }?.size ?: 0

    /** Delete one kept photo. Returns true if it was removed. Serialized with [save]. */
    @JvmStatic
    @Synchronized
    fun delete(file: File): Boolean = file.delete()

    /** Delete every kept photo. Serialized with [save] so it can't race a concurrent upload. */
    @JvmStatic
    @Synchronized
    fun clearAll(ctx: Context) {
        dir(ctx).listFiles()?.forEach { it.delete() }
    }

    /**
     * Trim the oldest photos until the store is within both the count and byte caps, so
     * a frame left running for months can't fill the device. Newest photos are always kept.
     */
    private fun enforceCap(ctx: Context) {
        // Walk newest-first, keeping photos until either cap is reached; everything after that
        // point is the oldest and gets evicted. (The just-saved photo is newest, so it's safe.)
        val newestFirst = files(ctx)
        var keptCount = 0
        var keptBytes = 0L
        for (f in newestFirst) {
            keptCount++
            keptBytes += f.length()
            if (keptCount > MAX_KEPT || keptBytes > MAX_BYTES) {
                f.delete()
            }
        }
    }

    /**
     * Persist [bytes] as a new kept photo with extension [ext]. Writes to a temp file then
     * atomically renames so a half-written upload is never picked up. Returns the saved file,
     * or null on failure. The caller is responsible for having validated [bytes] as an image.
     */
    @JvmStatic
    @Synchronized
    fun save(ctx: Context, bytes: ByteArray, ext: String): File? {
        val dir = dir(ctx)
        val name = "img_${System.currentTimeMillis()}_${seq.incrementAndGet()}.$ext"
        val dest = File(dir, name)
        val tmp = File(dir, "$name.tmp")
        return try {
            FileOutputStream(tmp).use { it.write(bytes) }
            if (tmp.renameTo(dest)) {
                Log.i(TAG, "kept upload ${bytes.size} bytes -> $name")
                enforceCap(ctx)
                dest
            } else {
                tmp.delete()
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "save upload failed", e)
            tmp.delete()
            null
        }
    }
}
