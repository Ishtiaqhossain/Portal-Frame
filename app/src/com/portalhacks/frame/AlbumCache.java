package com.portalhacks.frame;

import android.content.SharedPreferences;
import android.text.TextUtils;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Persists the fetched Google Photos album (photo list + title) to the shared
 * {@code portalframe} prefs so both the slideshow ({@link MainActivity}) and the
 * settings preview ({@code SettingsActivity}) can start from it without re-fetching.
 *
 * Stored as JSON (no raw newlines/tabs) so the value survives SharedPreferences'
 * XML round-trip intact — a delimiter-based blob got corrupted by control-char
 * escaping and produced phantom entries.
 */
final class AlbumCache {
    private static final String TAG = "PortalFrame";

    private AlbumCache() {}

    /** Persist {@code photos} (and {@code title}) as the cache for {@code url}. */
    static void write(SharedPreferences prefs, String url, List<Slide> photos, String title) {
        JSONArray arr = new JSONArray();
        for (Slide s : photos) {
            JSONObject o = new JSONObject();
            try {
                o.put("u", s.id);
                o.put("c", s.caption == null ? "" : s.caption);
                o.put("t", s.timeMs);
                o.put("pt", s.portrait);
            } catch (JSONException ignored) {
                continue;
            }
            arr.put(o);
        }
        prefs.edit()
                .putString(ConfigReceiver.KEY_PHOTO_CACHE, arr.toString())
                .putString(ConfigReceiver.KEY_PHOTO_CACHE_URL, url)
                .putString(ConfigReceiver.KEY_ALBUM_TITLE, title == null ? "" : title)
                .apply();
        Log.i(TAG, "persisted " + photos.size() + " photos to cache");
    }

    /** The cached photos for {@code url}, or {@code null} if the cache is empty/for another album. */
    static List<Slide> read(SharedPreferences prefs, String url) {
        String cachedUrl = prefs.getString(ConfigReceiver.KEY_PHOTO_CACHE_URL, "");
        if (url == null || !url.equals(cachedUrl)) {
            return null; // cache belongs to a different album
        }
        String blob = prefs.getString(ConfigReceiver.KEY_PHOTO_CACHE, "");
        if (TextUtils.isEmpty(blob)) {
            return null;
        }
        List<Slide> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(blob);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                String id = o.optString("u", "");
                String caption = o.optString("c", "");
                long t = o.optLong("t", Slide.NO_DATE);
                boolean portrait = o.optBoolean("pt", false);
                if (!id.isEmpty()) {
                    out.add(new Slide(id, caption.isEmpty() ? null : caption, t, portrait));
                }
            }
        } catch (JSONException e) {
            Log.w(TAG, "bad photo cache, ignoring", e);
            return null;
        }
        Log.i(TAG, "read " + out.size() + " photos from cache");
        return out;
    }

    /** The cached album title for {@code url} (may be empty), or {@code null} if no matching cache. */
    static String title(SharedPreferences prefs, String url) {
        String cachedUrl = prefs.getString(ConfigReceiver.KEY_PHOTO_CACHE_URL, "");
        if (url == null || !url.equals(cachedUrl)) {
            return null;
        }
        return prefs.getString(ConfigReceiver.KEY_ALBUM_TITLE, "");
    }

    /** The first cached photo id (URL) for {@code url}, or {@code null} if none cached. */
    static String firstId(SharedPreferences prefs, String url) {
        List<Slide> photos = read(prefs, url);
        return (photos == null || photos.isEmpty()) ? null : photos.get(0).id;
    }
}
