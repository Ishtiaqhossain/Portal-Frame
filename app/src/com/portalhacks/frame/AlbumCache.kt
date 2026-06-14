package com.portalhacks.frame

import android.content.SharedPreferences
import android.text.TextUtils
import android.util.Log
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Persists the fetched Google Photos album (photo list + title) to the shared
 * `portalframe` prefs so both the slideshow (`SlideshowComposeActivity`) and the
 * settings preview (`SettingsActivity`) can start from it without re-fetching.
 *
 * Stored as JSON (no raw newlines/tabs) so the value survives SharedPreferences'
 * XML round-trip intact — a delimiter-based blob got corrupted by control-char
 * escaping and produced phantom entries.
 */
internal object AlbumCache {
    private const val TAG = "PortalFrame"

    /** Persist [photos] (and [title]) as the cache for [url]. */
    @JvmStatic
    fun write(prefs: SharedPreferences, url: String, photos: List<Slide>, title: String?) {
        val arr = JSONArray()
        for (s in photos) {
            val o = JSONObject()
            try {
                o.put("u", s.id)
                o.put("c", s.caption ?: "")
                o.put("t", s.timeMs)
                o.put("pt", s.portrait)
            } catch (ignored: JSONException) {
                continue
            }
            arr.put(o)
        }
        prefs.edit()
            .putString(ConfigReceiver.KEY_PHOTO_CACHE, arr.toString())
            .putString(ConfigReceiver.KEY_PHOTO_CACHE_URL, url)
            .putString(ConfigReceiver.KEY_ALBUM_TITLE, title ?: "")
            .apply()
        Log.i(TAG, "persisted ${photos.size} photos to cache")
    }

    /** The cached photos for [url], or `null` if the cache is empty/for another album. */
    @JvmStatic
    fun read(prefs: SharedPreferences, url: String?): List<Slide>? {
        val cachedUrl = prefs.getString(ConfigReceiver.KEY_PHOTO_CACHE_URL, "")
        if (url == null || url != cachedUrl) {
            return null // cache belongs to a different album
        }
        val blob = prefs.getString(ConfigReceiver.KEY_PHOTO_CACHE, "")
        if (TextUtils.isEmpty(blob)) {
            return null
        }
        val out = ArrayList<Slide>()
        try {
            val arr = JSONArray(blob)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val id = o.optString("u", "")
                val caption = o.optString("c", "")
                val t = o.optLong("t", Slide.NO_DATE)
                val portrait = o.optBoolean("pt", false)
                if (id.isNotEmpty()) {
                    out.add(Slide(id, caption.ifEmpty { null }, t, portrait))
                }
            }
        } catch (e: JSONException) {
            Log.w(TAG, "bad photo cache, ignoring", e)
            return null
        }
        Log.i(TAG, "read ${out.size} photos from cache")
        return out
    }

    /** The cached album title for [url] (may be empty), or `null` if no matching cache. */
    @JvmStatic
    fun title(prefs: SharedPreferences, url: String?): String? {
        val cachedUrl = prefs.getString(ConfigReceiver.KEY_PHOTO_CACHE_URL, "")
        if (url == null || url != cachedUrl) {
            return null
        }
        return prefs.getString(ConfigReceiver.KEY_ALBUM_TITLE, "")
    }

    /** The first cached photo id (URL) for [url], or `null` if none cached. */
    @JvmStatic
    fun firstId(prefs: SharedPreferences, url: String?): String? {
        val photos = read(prefs, url)
        return if (photos.isNullOrEmpty()) null else photos[0].id
    }
}
