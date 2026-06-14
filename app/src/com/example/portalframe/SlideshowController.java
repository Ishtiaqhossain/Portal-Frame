package com.example.portalframe;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Crossfading slideshow. Items are image IDs — either bundled asset paths
 * ("slides/01.png") or remote URLs ("https://...") — loaded asynchronously via
 * {@link ImageLoader}. The source can be swapped at runtime with
 * {@link #setItems} (used to switch from bundled samples to a Google Photos
 * shared album once it loads).
 *
 * Touch handling is distance-based (works for slow finger swipes):
 *   - drag left  -> next image
 *   - drag right -> previous image
 *   - tap        -> dismiss (runs onDismiss)
 */
public class SlideshowController {

    private static final String TAG = "PortalFrame";
    private static final String SLIDES_DIR = "slides";
    private static final long SWIPE_FADE_MS = 300; // manual swipe fade
    private static final float SWIPE_MIN_DISTANCE = 60f;
    private static final float TAP_SLOP = 30f;
    private static final long TAP_TIMEOUT_MS = 350;
    private static final long LONG_PRESS_MS = 700; // hold to open Photos setup

    private final Context context;
    private final ImageLoader loader;
    private final ImageView back;
    private final ImageView front;
    private final TextView status;
    private final TextView info;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final int reqW;
    private final int reqH;

    // User-tunable settings (read from prefs in the constructor; see PhotosActivity).
    private final long intervalMs;  // time each slide is held
    private final long autoFadeMs;  // auto crossfade duration
    private final boolean shuffle;  // play photos in random order

    private List<Slide> items = new ArrayList<>();
    private boolean remote = false;
    private int index = 0;
    private boolean running = false;
    private long animGen = 0;
    private Runnable onDismiss;
    private Runnable onSettings;

    public SlideshowController(Context context, FrameLayout root, ImageLoader loader) {
        this.context = context;
        this.loader = loader;
        DisplayMetrics dm = context.getResources().getDisplayMetrics();
        this.reqW = dm.widthPixels > 0 ? dm.widthPixels : 1280;
        this.reqH = dm.heightPixels > 0 ? dm.heightPixels : 800;

        SharedPreferences prefs =
                context.getSharedPreferences(ConfigReceiver.PREFS, Context.MODE_PRIVATE);
        this.intervalMs = prefs.getLong(
                ConfigReceiver.KEY_DELAY_MS, ConfigReceiver.DEFAULT_DELAY_MS);
        this.autoFadeMs = prefs.getLong(
                ConfigReceiver.KEY_FADE_MS, ConfigReceiver.DEFAULT_FADE_MS);
        this.shuffle = prefs.getBoolean(ConfigReceiver.KEY_SHUFFLE, false);

        root.setBackgroundColor(Color.BLACK);
        back = newImageView();
        front = newImageView();
        front.setAlpha(0f);

        status = new TextView(context);
        status.setTextColor(Color.WHITE);
        status.setTextSize(13f);
        status.setShadowLayer(4f, 0f, 0f, Color.BLACK);
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        sp.gravity = Gravity.BOTTOM | Gravity.START;
        sp.leftMargin = 28;
        sp.bottomMargin = 28;
        status.setLayoutParams(sp);

        // Lower-right: photo date (and location when available).
        info = new TextView(context);
        info.setTextColor(Color.WHITE);
        info.setTextSize(19f);
        info.setShadowLayer(5f, 0f, 1f, Color.BLACK);
        FrameLayout.LayoutParams ip = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        ip.gravity = Gravity.BOTTOM | Gravity.END;
        ip.rightMargin = 28;
        ip.bottomMargin = 28;
        info.setLayoutParams(ip);

        root.addView(back);
        root.addView(front);
        root.addView(status);
        root.addView(info);
        root.addView(buildTouchOverlay());
    }

    public void setOnDismiss(Runnable onDismiss) {
        this.onDismiss = onDismiss;
    }

    /** Long-press anywhere on the slideshow runs this (used to open Photos setup). */
    public void setOnSettings(Runnable onSettings) {
        this.onSettings = onSettings;
    }

    public void setStatusHint(String text) {
        status.setText(text);
    }

    private ImageView newImageView() {
        ImageView iv = new ImageView(context);
        iv.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        return iv;
    }

    private View buildTouchOverlay() {
        View overlay = new View(context);
        overlay.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        overlay.setClickable(true);
        overlay.setFocusable(true);
        overlay.setOnTouchListener(new View.OnTouchListener() {
            private float downX, downY;
            private long downTime;
            private boolean handled;
            private Runnable pendingLong;

            private void cancelLong() {
                if (pendingLong != null) {
                    handler.removeCallbacks(pendingLong);
                    pendingLong = null;
                }
            }

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = e.getX();
                        downY = e.getY();
                        downTime = e.getEventTime();
                        handled = false;
                        if (v.getParent() != null) {
                            v.getParent().requestDisallowInterceptTouchEvent(true);
                        }
                        cancelLong();
                        if (onSettings != null) {
                            pendingLong = new Runnable() {
                                @Override
                                public void run() {
                                    pendingLong = null;
                                    handled = true; // suppress the tap-dismiss on release
                                    onSettings.run();
                                }
                            };
                            handler.postDelayed(pendingLong, LONG_PRESS_MS);
                        }
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        if (!handled) {
                            float dx = e.getX() - downX;
                            float dy = e.getY() - downY;
                            if (Math.abs(dx) > TAP_SLOP || Math.abs(dy) > TAP_SLOP) {
                                cancelLong(); // finger moved — not a long press
                            }
                            if (Math.abs(dx) > SWIPE_MIN_DISTANCE
                                    && Math.abs(dx) > Math.abs(dy)) {
                                handled = true;
                                if (dx < 0) {
                                    showNext();
                                } else {
                                    showPrevious();
                                }
                            }
                        }
                        return true;
                    case MotionEvent.ACTION_UP: {
                        cancelLong();
                        float dx = e.getX() - downX;
                        float dy = e.getY() - downY;
                        long dt = e.getEventTime() - downTime;
                        if (!handled) {
                            if (Math.abs(dx) > SWIPE_MIN_DISTANCE
                                    && Math.abs(dx) > Math.abs(dy)) {
                                if (dx < 0) {
                                    showNext();
                                } else {
                                    showPrevious();
                                }
                            } else if (Math.abs(dx) < TAP_SLOP && Math.abs(dy) < TAP_SLOP
                                    && dt < TAP_TIMEOUT_MS && onDismiss != null) {
                                onDismiss.run();
                            }
                        }
                        return true;
                    }
                    case MotionEvent.ACTION_CANCEL:
                        cancelLong();
                        return true;
                    default:
                        return true;
                }
            }
        });
        return overlay;
    }

    public void start() {
        running = true;
        if (items.isEmpty()) {
            items = assetItems();
            remote = false;
            if (shuffle) {
                java.util.Collections.shuffle(items);
            }
        }
        if (items.isEmpty()) {
            status.setText("No slides found in assets/" + SLIDES_DIR);
            back.setBackgroundColor(Color.DKGRAY);
            return;
        }
        index = 0;
        Log.i(TAG, "Slideshow started with " + items.size()
                + (remote ? " album photos" : " bundled slides"));
        showImmediate(0);
    }

    public void stop() {
        running = false;
        handler.removeCallbacks(autoTick);
        front.animate().cancel();
    }

    /** Swap the photo source at runtime (e.g. bundled samples -> album). */
    public void setItems(List<Slide> newItems) {
        if (newItems == null || newItems.isEmpty()) {
            return;
        }
        items = new ArrayList<>(newItems);
        if (shuffle) {
            java.util.Collections.shuffle(items);
        }
        remote = true;
        handler.removeCallbacks(autoTick);
        front.animate().cancel();
        front.setAlpha(0f);
        index = 0;
        running = true;
        Log.i(TAG, "Source switched to " + items.size() + " album photos");
        showImmediate(0);
    }

    public void showNext() {
        if (!items.isEmpty()) {
            transitionTo((index + 1) % items.size(), SWIPE_FADE_MS);
        }
    }

    public void showPrevious() {
        if (!items.isEmpty()) {
            transitionTo((index - 1 + items.size()) % items.size(), SWIPE_FADE_MS);
        }
    }

    private void scheduleAuto() {
        handler.removeCallbacks(autoTick);
        if (running && items.size() > 1) {
            handler.postDelayed(autoTick, intervalMs);
        }
    }

    private final Runnable autoTick = new Runnable() {
        @Override
        public void run() {
            if (running && !items.isEmpty()) {
                transitionTo((index + 1) % items.size(), autoFadeMs);
            }
        }
    };

    /** Show item i directly (no crossfade) — used for the first frame. */
    private void showImmediate(final int i) {
        final long gen = ++animGen;
        loader.load(items.get(i).id, reqW, reqH, new ImageLoader.Callback() {
            @Override
            public void onLoaded(Bitmap b) {
                if (gen != animGen) {
                    return;
                }
                if (b != null) {
                    back.setImageBitmap(b);
                    front.setImageDrawable(null);
                    front.setAlpha(0f);
                    index = i;
                    status.setText("");
                    info.setText(captionOf(i));
                }
                prefetchNext(i);
                scheduleAuto();
            }
        });
    }

    /** Crossfade to item {@code next}; loads async, safe to call mid-fade. */
    private void transitionTo(final int next, final long fadeMs) {
        if (items.isEmpty()) {
            return;
        }
        handler.removeCallbacks(autoTick);
        final long gen = ++animGen;
        loader.load(items.get(next).id, reqW, reqH, new ImageLoader.Callback() {
            @Override
            public void onLoaded(final Bitmap bmp) {
                if (gen != animGen) {
                    return; // superseded by a newer request
                }
                if (bmp == null) {
                    index = next;
                    scheduleAuto();
                    return;
                }
                front.animate().cancel();
                front.setImageBitmap(bmp);
                front.setAlpha(0f);
                index = next;
                status.setText("");
                info.setText(captionOf(next));
                front.animate().alpha(1f).setDuration(fadeMs).withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        if (gen != animGen) {
                            return;
                        }
                        back.setImageBitmap(bmp);
                        front.setAlpha(0f);
                        prefetchNext(next);
                        scheduleAuto();
                    }
                });
            }
        });
    }

    private void prefetchNext(int from) {
        if (items.size() > 1) {
            loader.prefetch(items.get((from + 1) % items.size()).id, reqW, reqH);
        }
    }

    private String captionOf(int i) {
        String cap = items.get(i).caption;
        return cap == null ? "" : cap;
    }

    private List<Slide> assetItems() {
        List<String> names = new ArrayList<>();
        try {
            AssetManager am = context.getAssets();
            String[] list = am.list(SLIDES_DIR);
            if (list != null) {
                for (String n : list) {
                    String lower = n.toLowerCase();
                    if (lower.endsWith(".png") || lower.endsWith(".jpg")
                            || lower.endsWith(".jpeg") || lower.endsWith(".webp")) {
                        names.add(SLIDES_DIR + "/" + n);
                    }
                }
            }
        } catch (IOException e) {
            Log.e(TAG, "Failed to list slides", e);
        }
        java.util.Collections.sort(names);
        List<Slide> found = new ArrayList<>();
        for (String n : names) {
            found.add(new Slide(n, null));
        }
        return found;
    }
}
