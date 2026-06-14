package com.portalhacks.frame

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONException

/**
 * The user's configured shared albums (Google Photos / iCloud), stored as a JSON
 * array of URLs under [ConfigReceiver.KEY_ALBUMS]. Order is preservation order; the
 * slideshow plays photos from all of them merged together.
 *
 * Migrates the legacy single-album [ConfigReceiver.KEY_ALBUM] value on first read,
 * and keeps it in sync with the first album so any old reader still works.
 */
internal object Albums {

    /** Album URLs the slideshow should actually play (configured minus stopped). */
    @JvmStatic
    fun enabled(prefs: SharedPreferences): List<String> {
        val off = disabled(prefs)
        return list(prefs).filter { it !in off }
    }

    /** Whether [url] is currently playing (not stopped). */
    @JvmStatic
    fun isEnabled(prefs: SharedPreferences, url: String): Boolean = url !in disabled(prefs)

    /** Stop/resume [url] without removing it from the list. */
    @JvmStatic
    fun setEnabled(prefs: SharedPreferences, url: String, on: Boolean) {
        val off = disabled(prefs).toMutableSet()
        val changed = if (on) off.remove(url) else off.add(url)
        if (changed) saveDisabled(prefs, off)
    }

    private fun disabled(prefs: SharedPreferences): Set<String> =
        parse(prefs.getString(ConfigReceiver.KEY_ALBUMS_DISABLED, null) ?: "").toSet()

    private fun saveDisabled(prefs: SharedPreferences, urls: Set<String>) {
        val arr = JSONArray()
        for (u in urls) arr.put(u)
        prefs.edit().putString(ConfigReceiver.KEY_ALBUMS_DISABLED, arr.toString()).apply()
    }

    /** The configured album URLs (migrating the legacy single-album pref if needed). */
    @JvmStatic
    fun list(prefs: SharedPreferences): List<String> {
        val blob = prefs.getString(ConfigReceiver.KEY_ALBUMS, null)
            ?: run {
                val legacy = prefs.getString(ConfigReceiver.KEY_ALBUM, "") ?: ""
                val seeded = if (legacy.isNotEmpty()) listOf(legacy) else emptyList()
                save(prefs, seeded)
                return seeded
            }
        return parse(blob)
    }

    /** Append [url] if not already present. Returns true if it was added. */
    @JvmStatic
    fun add(prefs: SharedPreferences, url: String): Boolean {
        val cur = list(prefs).toMutableList()
        if (cur.contains(url)) return false
        cur.add(url)
        save(prefs, cur)
        return true
    }

    /** Remove [url] if present (and drop its cache + any stopped state). */
    @JvmStatic
    fun remove(prefs: SharedPreferences, url: String) {
        val cur = list(prefs).toMutableList()
        if (cur.remove(url)) {
            save(prefs, cur)
            setEnabled(prefs, url, true) // clear any stopped state
            AlbumCache.delete(prefs, url)
        }
    }

    /** Remove all albums (revert to the bundled samples). */
    @JvmStatic
    fun clear(prefs: SharedPreferences) {
        for (u in list(prefs)) AlbumCache.delete(prefs, u)
        save(prefs, emptyList())
        prefs.edit().remove(ConfigReceiver.KEY_ALBUMS_DISABLED).apply()
    }

    private fun save(prefs: SharedPreferences, urls: List<String>) {
        val arr = JSONArray()
        for (u in urls) arr.put(u)
        prefs.edit()
            .putString(ConfigReceiver.KEY_ALBUMS, arr.toString())
            .putString(ConfigReceiver.KEY_ALBUM, urls.firstOrNull() ?: "") // legacy mirror
            .apply()
    }

    private fun parse(blob: String): List<String> = try {
        val arr = JSONArray(blob)
        (0 until arr.length()).mapNotNull { i -> arr.optString(i, "").ifEmpty { null } }
    } catch (e: JSONException) {
        emptyList()
    }
}
