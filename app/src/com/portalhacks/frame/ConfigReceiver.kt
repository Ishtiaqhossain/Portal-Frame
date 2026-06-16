package com.portalhacks.frame

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Lets shared albums (Google Photos or iCloud) be managed over ADB without rebuilding:
 *
 *   # add an album (repeat to add several)
 *   adb shell am broadcast -n com.portalhacks.frame/.ConfigReceiver \
 *       --es url "https://photos.app.goo.gl/XXXXXXXX"
 *   adb shell am broadcast -n com.portalhacks.frame/.ConfigReceiver \
 *       --es url "https://www.icloud.com/sharedalbum/#XXXXXXXX"
 *
 *   # remove one album / clear all (revert to bundled samples)
 *   adb shell am broadcast -n com.portalhacks.frame/.ConfigReceiver --es remove_url "https://…"
 *   adb shell am broadcast -n com.portalhacks.frame/.ConfigReceiver --es url ""
 */
class ConfigReceiver : BroadcastReceiver() {

    override fun onReceive(ctx: Context, intent: Intent?) {
        if (intent == null) {
            return
        }
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        // This receiver is exported (so albums can be managed over ADB), which means any
        // installed app could broadcast to it. `url` adds a recognised shared-album link
        // (empty clears ALL albums); `remove_url` removes one. Unrecognised links are
        // ignored so a hostile broadcast can't point the frame at an arbitrary URL.
        if (intent.hasExtra("url")) {
            val url = intent.getStringExtra("url")?.trim() ?: ""
            when {
                url.isEmpty() -> { Albums.clear(prefs); Log.i("PortalFrame", "albums cleared") }
                isAlbumUrl(url) -> if (Albums.add(prefs, url)) Log.i("PortalFrame", "album added: '$url'")
                else -> Log.w("PortalFrame", "ignoring unrecognised album_url")
            }
        }
        if (intent.hasExtra("remove_url")) {
            val url = intent.getStringExtra("remove_url")?.trim() ?: ""
            if (url.isNotEmpty()) {
                Albums.remove(prefs, url)
                Log.i("PortalFrame", "album removed: '$url'")
            }
        }
        for (pair in arrayOf("enable_url" to true, "disable_url" to false)) {
            if (intent.hasExtra(pair.first)) {
                val url = intent.getStringExtra(pair.first)?.trim() ?: ""
                if (url.isNotEmpty()) {
                    Albums.setEnabled(prefs, url, pair.second)
                    Log.i("PortalFrame", "album ${if (pair.second) "resumed" else "stopped"}: '$url'")
                }
            }
        }

        // Toggle the screensaver guard over ADB (keeps Frame as the dream even when the
        // launcher reclaims the slot on rotation). Needs WRITE_SECURE_SETTINGS granted:
        //   adb shell am broadcast -n com.portalhacks.frame/.ConfigReceiver --ez guard true
        if (intent.hasExtra("guard")) {
            val on = intent.getBooleanExtra("guard", true)
            prefs.edit().putBoolean(KEY_GUARD, on).apply()
            if (on) {
                Screensaver.claim(ctx)
                ScreensaverGuardService.start(ctx)
                Log.i("PortalFrame", "screensaver guard enabled")
            } else {
                ScreensaverGuardService.stop(ctx)
                Log.i("PortalFrame", "screensaver guard disabled")
            }
        }

        val ed = prefs.edit()
        var any = false
        for (e in BOOL_EXTRAS) {
            if (intent.hasExtra(e[0])) {
                val value = intent.getBooleanExtra(e[0], true)
                ed.putBoolean(e[1], value)
                Log.i("PortalFrame", "${e[1]} set to: $value")
                any = true
            }
        }
        if (any) {
            ed.apply()
        }
    }

    companion object {
        const val PREFS = "portalframe"
        const val KEY_ALBUM = "album_url" // legacy single album (migrated into KEY_ALBUMS)
        const val KEY_ALBUMS = "album_urls" // JSON array of configured album URLs
        const val KEY_ALBUMS_DISABLED = "album_urls_disabled" // JSON array of stopped album URLs
        const val KEY_GUARD = "screensaver_guard" // boolean: keep re-asserting Frame as the dream

        // Slideshow settings (written by PhotosActivity, read by SlideshowController).
        const val KEY_DELAY_MS = "delay_ms"     // ms each photo is held
        const val KEY_SHUFFLE = "shuffle"       // boolean: random order
        const val KEY_FADE_MS = "fade_ms"       // ms auto crossfade duration
        const val KEY_PAIRS = "pairs"           // boolean: pair two photos to fill the screen
        const val KEY_KEN_BURNS = "ken_burns"   // boolean: cinematic pan/zoom
        const val KEY_CLOCK = "clock"           // boolean: clock + weather overlay
        const val KEY_CLOCK_LOW_LIGHT = "clock_low_light" // boolean: clock-only in low light
        const val KEY_NIGHT = "night"           // boolean: warm night dimming
        const val KEY_ON_THIS_DAY = "on_this_day" // boolean: surface memories
        const val KEY_CAPTIONS = "captions"     // boolean: photo date captions
        const val KEY_FACE = "face_framing"     // boolean: face-aware Ken Burns target
        const val KEY_AMBIENT = "ambient_color" // boolean: per-photo color glow
        const val KEY_ENHANCE = "auto_enhance"  // boolean: on-device auto-levels + vibrance
        const val DEFAULT_DELAY_MS = 6000L
        const val DEFAULT_FADE_MS = 1200L
        const val DEFAULT_PAIRS = false
        const val DEFAULT_KEN_BURNS = true
        const val DEFAULT_CLOCK = true
        const val DEFAULT_CLOCK_LOW_LIGHT = true
        const val DEFAULT_NIGHT = true
        const val DEFAULT_ON_THIS_DAY = true
        const val DEFAULT_CAPTIONS = true
        const val DEFAULT_FACE = true
        const val DEFAULT_AMBIENT = true
        const val DEFAULT_ENHANCE = false

        // ADB-settable boolean extras (extra name -> pref key) for quick testing, e.g.
        //   adb shell am broadcast -n com.portalhacks.frame/.ConfigReceiver --ez ken_burns false
        private val BOOL_EXTRAS = arrayOf(
            arrayOf("shuffle", KEY_SHUFFLE), arrayOf("pairs", KEY_PAIRS), arrayOf("ken_burns", KEY_KEN_BURNS),
            arrayOf("clock", KEY_CLOCK), arrayOf("clock_low_light", KEY_CLOCK_LOW_LIGHT),
            arrayOf("night", KEY_NIGHT), arrayOf("on_this_day", KEY_ON_THIS_DAY),
            arrayOf("captions", KEY_CAPTIONS), arrayOf("face_framing", KEY_FACE), arrayOf("ambient_color", KEY_AMBIENT),
            arrayOf("auto_enhance", KEY_ENHANCE),
        )

        // Per-album photo caches are managed by AlbumCache (keyed by album URL).

        /** True for a recognised shared-album HTTPS link (Google Photos or iCloud). */
        fun isAlbumUrl(s: String?): Boolean = PhotoSources.matches(s)
    }
}
