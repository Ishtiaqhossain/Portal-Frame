package com.portalhacks.frame;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Lets the Google Photos shared-album URL be set over ADB without rebuilding:
 *
 *   adb shell am broadcast -n com.portalhacks.frame/.ConfigReceiver \
 *       --es url "https://photos.app.goo.gl/XXXXXXXX"
 *
 * Clear it (revert to bundled samples) with:
 *   adb shell am broadcast -n com.portalhacks.frame/.ConfigReceiver --es url ""
 */
public class ConfigReceiver extends BroadcastReceiver {

    static final String PREFS = "portalframe";
    static final String KEY_ALBUM = "album_url";

    // Slideshow settings (written by PhotosActivity, read by SlideshowController).
    static final String KEY_DELAY_MS = "delay_ms";   // ms each photo is held
    static final String KEY_SHUFFLE = "shuffle";     // boolean: random order
    static final String KEY_FADE_MS = "fade_ms";     // ms auto crossfade duration
    static final String KEY_PAIRS = "pairs";         // boolean: pair portraits side-by-side
    static final String KEY_KEN_BURNS = "ken_burns"; // boolean: cinematic pan/zoom
    static final String KEY_CLOCK = "clock";         // boolean: clock + weather overlay
    static final String KEY_NIGHT = "night";         // boolean: warm night dimming
    static final String KEY_ON_THIS_DAY = "on_this_day"; // boolean: surface memories
    static final String KEY_CAPTIONS = "captions";   // boolean: photo date captions
    static final String KEY_FACE = "face_framing";   // boolean: face-aware Ken Burns target
    static final String KEY_AMBIENT = "ambient_color"; // boolean: per-photo color glow
    static final String KEY_ENHANCE = "auto_enhance"; // boolean: on-device auto-levels + vibrance
    static final long DEFAULT_DELAY_MS = 6000L;
    static final long DEFAULT_FADE_MS = 1200L;
    static final boolean DEFAULT_PAIRS = true;
    static final boolean DEFAULT_KEN_BURNS = true;
    static final boolean DEFAULT_CLOCK = true;
    static final boolean DEFAULT_NIGHT = true;
    static final boolean DEFAULT_ON_THIS_DAY = true;
    static final boolean DEFAULT_CAPTIONS = true;
    static final boolean DEFAULT_FACE = true;
    static final boolean DEFAULT_AMBIENT = true;
    static final boolean DEFAULT_ENHANCE = true;

    // ADB-settable boolean extras (extra name -> pref key) for quick testing, e.g.
    //   adb shell am broadcast -n com.portalhacks.frame/.ConfigReceiver --ez ken_burns false
    private static final String[][] BOOL_EXTRAS = {
            {"shuffle", KEY_SHUFFLE}, {"pairs", KEY_PAIRS}, {"ken_burns", KEY_KEN_BURNS},
            {"clock", KEY_CLOCK}, {"night", KEY_NIGHT}, {"on_this_day", KEY_ON_THIS_DAY},
            {"captions", KEY_CAPTIONS}, {"face_framing", KEY_FACE}, {"ambient_color", KEY_AMBIENT},
            {"auto_enhance", KEY_ENHANCE},
    };

    // Cached photo list so the screensaver starts straight from the album (no
    // bundled-sample flash). KEY_PHOTO_CACHE_URL records which album it's for.
    // v3: bumped for the new per-photo schema (capture time + portrait flag) and
    // the album title; captions are now derived at display time, not cached.
    static final String KEY_PHOTO_CACHE = "photo_cache_v3";
    static final String KEY_PHOTO_CACHE_URL = "photo_cache_url_v3";
    static final String KEY_ALBUM_TITLE = "album_title_v3";

    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (intent == null) {
            return;
        }
        android.content.SharedPreferences.Editor ed =
                ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
        boolean any = false;
        if (intent.hasExtra("url")) {
            String url = intent.getStringExtra("url");
            url = url == null ? "" : url.trim();
            // This receiver is exported (so the album can be set over ADB), which means
            // any installed app could broadcast to it. Only persist an empty value
            // (clears the album, reverting to the bundled samples) or a real Google
            // Photos HTTPS link; ignore anything else so a hostile broadcast can't point
            // the frame at an arbitrary URL.
            if (url.isEmpty() || isAlbumUrl(url)) {
                ed.putString(KEY_ALBUM, url);
                Log.i("PortalFrame", "album_url set to: '" + url + "'");
                any = true;
            } else {
                Log.w("PortalFrame", "ignoring non-Google-Photos album_url");
            }
        }
        for (String[] e : BOOL_EXTRAS) {
            if (intent.hasExtra(e[0])) {
                boolean val = intent.getBooleanExtra(e[0], true);
                ed.putBoolean(e[1], val);
                Log.i("PortalFrame", e[1] + " set to: " + val);
                any = true;
            }
        }
        if (any) {
            ed.apply();
        }
    }

    /** True for a Google Photos shared-album HTTPS link (the only URLs we'll fetch). */
    static boolean isAlbumUrl(String s) {
        return s != null
                && (s.startsWith("https://photos.app.goo.gl/")
                || s.startsWith("https://photos.google.com/share/"));
    }
}
