package com.example.portalframe;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * Full-screen interactive slideshow.
 *
 * When a Google Photos album is configured we start straight from it — the last
 * fetched photo list is cached in prefs and the images are disk-cached by
 * {@link ImageLoader}, so the first frame is an album photo (no bundled-sample
 * flash). A fresh fetch then runs in the background, and the album is
 * re-checked periodically so newly added photos appear during a long session.
 * Bundled samples are only shown when no album is configured.
 */
public class MainActivity extends Activity {

    private static final String TAG = "PortalFrame";
    private static final long REFRESH_INTERVAL_MS = 20 * 60 * 1000L; // 20 min
    private static final long DIM_INTERVAL_MS = 5 * 60 * 1000L;       // re-check brightness

    private ImageLoader loader;
    private SlideshowController controller;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private String albumUrl = "";
    private List<String> currentIds = new ArrayList<>(); // ids currently shown

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);

        FrameLayout root = new FrameLayout(this);
        setContentView(root);

        loader = new ImageLoader(this);
        controller = new SlideshowController(this, root, loader);
        controller.setOnDismiss(new Runnable() {
            @Override
            public void run() {
                Intent home = new Intent(Intent.ACTION_MAIN);
                home.addCategory(Intent.CATEGORY_HOME);
                home.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(home);
                finish();
            }
        });
        // Portal's launcher won't show sideloaded app icons, so long-press the
        // slideshow to reach the Photos setup screen (pick album / settings).
        controller.setOnSettings(new Runnable() {
            @Override
            public void run() {
                startActivity(new Intent(MainActivity.this, SettingsActivity.class));
            }
        });
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Clear any photo retained from a previous run so re-entering the frame
        // doesn't flash the old image before the first new frame loads.
        controller.blank();
        startDimming(); // ease screen brightness down at night, up in the morning
        SharedPreferences prefs = getSharedPreferences(ConfigReceiver.PREFS, MODE_PRIVATE);
        albumUrl = prefs.getString(ConfigReceiver.KEY_ALBUM, "");

        if (albumUrl == null || albumUrl.isEmpty()) {
            // No album configured: show the bundled samples.
            controller.start();
            return;
        }

        // Album configured: start straight from the cached album if we have it
        // (disk-cached images make the first photo appear near-instantly);
        // otherwise show a black "Loading…" screen — never the samples.
        List<Slide> cached = readCachedAlbum(prefs, albumUrl);
        if (cached != null && !cached.isEmpty()) {
            currentIds = idsOf(cached);
            controller.setItems(cached);
        } else {
            currentIds = new ArrayList<>();
            controller.setStatusHint("Loading Google Photos…");
        }

        // Refresh now, then keep checking periodically while we're on screen.
        fetchAndApply(cached == null || cached.isEmpty());
        handler.removeCallbacks(refreshTick);
        handler.postDelayed(refreshTick, REFRESH_INTERVAL_MS);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(refreshTick);
        handler.removeCallbacks(dimTick);
        controller.stop();
    }

    // --- night dimming -------------------------------------------------------

    private final Runnable dimTick = new Runnable() {
        @Override
        public void run() {
            applyBrightness();
            handler.postDelayed(this, DIM_INTERVAL_MS);
        }
    };

    private void startDimming() {
        handler.removeCallbacks(dimTick);
        applyBrightness();
        handler.postDelayed(dimTick, DIM_INTERVAL_MS);
    }

    /** Set this window's brightness from the time of day (doesn't touch system settings). */
    private void applyBrightness() {
        Calendar c = Calendar.getInstance();
        float h = c.get(Calendar.HOUR_OF_DAY) + c.get(Calendar.MINUTE) / 60f;
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = brightnessForHour(h);
        getWindow().setAttributes(lp);
    }

    /**
     * Full brightness through the day, eased down to a soft glow overnight so the
     * frame isn't a lighthouse in a dark room. Ramps 21:00→23:00 down and
     * 06:00→08:00 up; deep night 23:00→06:00.
     */
    private static float brightnessForHour(float h) {
        final float DAY = 1.0f, NIGHT = 0.07f;
        if (h >= 8f && h < 21f) {
            return DAY;
        }
        if (h >= 21f && h < 23f) {
            return lerp(DAY, NIGHT, (h - 21f) / 2f);
        }
        if (h >= 23f || h < 6f) {
            return NIGHT;
        }
        return lerp(NIGHT, DAY, (h - 6f) / 2f); // 06:00–08:00
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private final Runnable refreshTick = new Runnable() {
        @Override
        public void run() {
            fetchAndApply(false);
            handler.postDelayed(this, REFRESH_INTERVAL_MS);
        }
    };

    /**
     * Fetch the album in the background; on success persist it and apply it only
     * if the photo set actually changed (avoids a redundant restart/flicker).
     */
    private void fetchAndApply(final boolean showHint) {
        final String url = albumUrl;
        if (url == null || url.isEmpty()) {
            return;
        }
        loader.executor().execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final GooglePhotosSource.Album album = GooglePhotosSource.fetch(url);
                    final List<Slide> photos = album.slides;
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            // album may have changed while fetching
                            if (!url.equals(albumUrl)) {
                                return;
                            }
                            if (photos.isEmpty()) {
                                if (showHint) {
                                    controller.setStatusHint(
                                            "Album returned no photos (check sharing/link)");
                                }
                                return;
                            }
                            persistAlbum(url, photos, album.title);
                            List<String> newIds = idsOf(photos);
                            if (!newIds.equals(currentIds)) {
                                currentIds = newIds;
                                controller.setItems(photos);
                            }
                        }
                    });
                } catch (final Exception e) {
                    Log.e(TAG, "album fetch failed", e);
                    if (showHint) {
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                controller.setStatusHint("Couldn't load album — retrying later");
                            }
                        });
                    }
                }
            }
        });
    }

    // --- photo-list cache (prefs) -------------------------------------------

    private static List<String> idsOf(List<Slide> slides) {
        List<String> ids = new ArrayList<>(slides.size());
        for (Slide s : slides) {
            ids.add(s.id);
        }
        return ids;
    }

    // JSON (no raw newlines/tabs) so the value survives SharedPreferences' XML
    // round-trip intact — a delimiter-based blob got corrupted by control-char
    // escaping and produced phantom entries.
    private void persistAlbum(String url, List<Slide> photos, String title) {
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
        getSharedPreferences(ConfigReceiver.PREFS, MODE_PRIVATE).edit()
                .putString(ConfigReceiver.KEY_PHOTO_CACHE, arr.toString())
                .putString(ConfigReceiver.KEY_PHOTO_CACHE_URL, url)
                .putString(ConfigReceiver.KEY_ALBUM_TITLE, title == null ? "" : title)
                .apply();
        Log.i(TAG, "persisted " + photos.size() + " photos to cache");
    }

    private List<Slide> readCachedAlbum(SharedPreferences prefs, String url) {
        String cachedUrl = prefs.getString(ConfigReceiver.KEY_PHOTO_CACHE_URL, "");
        if (!url.equals(cachedUrl)) {
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
}
