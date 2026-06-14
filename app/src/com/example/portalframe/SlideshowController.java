package com.example.portalframe;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.TimeZone;

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
    private static final long WEATHER_INTERVAL_MS = 30 * 60 * 1000L; // refresh weather

    private final Context context;
    private final ImageLoader loader;
    private final ImageView back;
    private final ImageView front;
    private final TextView status;
    private final TextView info;
    private final TextView clock;
    private final TextView dateLine;
    private final ShimmerView shimmer;
    private final java.text.DateFormat timeFmt;
    private final java.text.SimpleDateFormat dateFmt =
            new java.text.SimpleDateFormat("EEE, MMM d", java.util.Locale.getDefault());
    private final java.text.SimpleDateFormat monthYearFmt =
            new java.text.SimpleDateFormat("MMM yyyy", java.util.Locale.getDefault());
    private final boolean fahrenheit =
            "US".equals(java.util.Locale.getDefault().getCountry());
    private final View nightTint;   // warm overlay that fades in at night (Ambient-EQ-lite)
    private final View ambientGlow; // edge vignette tinted to the photo's mood color
    private Weather.Now weather; // current reading; null until loaded
    private final android.graphics.drawable.Drawable moonDrawable; // blue crescent, clear nights
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final int reqW;
    private final int reqH;

    // Smart-shuffle memory: ids shown recently (most-recent first) so a reshuffle
    // doesn't immediately replay them, and the last id to avoid a back-to-back repeat.
    private final LinkedList<String> recentIds = new LinkedList<>();
    private String lastShownId;
    private boolean shimmerHidden;

    // User-tunable settings (read from prefs in the constructor; see PhotosActivity).
    private final long intervalMs;  // time each slide is held
    private final long autoFadeMs;  // auto crossfade duration
    private final boolean shuffle;  // play photos in random order
    private final boolean pairs;    // pair portrait photos side-by-side
    private final boolean kenBurns; // cinematic slow pan + zoom while held
    private final boolean showClock;// clock + weather overlay
    private final boolean nightMode;// warm night dimming
    private final boolean onThisDay;// surface "N years ago today" memories
    private final boolean captions; // photo date captions (lower-right)
    private final boolean faceFraming;  // bias Ken Burns toward detected faces
    private final boolean ambientColor; // tint chrome to each photo's palette
    private final boolean enhance;      // on-device auto-levels + vibrance

    // Ken Burns animation state.
    private final java.util.Random rnd = new java.util.Random();
    private ValueAnimator kbAnim;
    private KenBurns kbPath;
    private android.graphics.ColorMatrixColorFilter enhanceFilter; // per-slide, or null

    private List<Slide> items = new ArrayList<>();
    private boolean remote = false;
    private int index = 0;
    private boolean curIsPair = false; // current frame shows a portrait pair
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
        this.pairs = prefs.getBoolean(ConfigReceiver.KEY_PAIRS, ConfigReceiver.DEFAULT_PAIRS);
        this.kenBurns = prefs.getBoolean(ConfigReceiver.KEY_KEN_BURNS, ConfigReceiver.DEFAULT_KEN_BURNS);
        this.showClock = prefs.getBoolean(ConfigReceiver.KEY_CLOCK, ConfigReceiver.DEFAULT_CLOCK);
        this.nightMode = prefs.getBoolean(ConfigReceiver.KEY_NIGHT, ConfigReceiver.DEFAULT_NIGHT);
        this.onThisDay = prefs.getBoolean(ConfigReceiver.KEY_ON_THIS_DAY, ConfigReceiver.DEFAULT_ON_THIS_DAY);
        this.captions = prefs.getBoolean(ConfigReceiver.KEY_CAPTIONS, ConfigReceiver.DEFAULT_CAPTIONS);
        this.faceFraming = prefs.getBoolean(ConfigReceiver.KEY_FACE, ConfigReceiver.DEFAULT_FACE);
        this.ambientColor = prefs.getBoolean(ConfigReceiver.KEY_AMBIENT, ConfigReceiver.DEFAULT_AMBIENT);
        this.enhance = prefs.getBoolean(ConfigReceiver.KEY_ENHANCE, ConfigReceiver.DEFAULT_ENHANCE);
        monthYearFmt.setTimeZone(TimeZone.getTimeZone("UTC"));

        root.setBackgroundColor(Color.BLACK);
        back = newImageView();
        front = newImageView();
        front.setAlpha(0f);

        int margin = Ui.dp(context, 28);

        // Gradient scrims so the white system-overlay pills (top) and our caption
        // text (bottom) stay legible over bright photos — per the Portal design rules.
        View topScrim = new View(context);
        topScrim.setBackground(new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x99000000, 0x00000000}));
        FrameLayout.LayoutParams tsp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, Ui.dp(context, 96));
        tsp.gravity = Gravity.TOP;
        topScrim.setLayoutParams(tsp);

        View bottomScrim = new View(context);
        bottomScrim.setBackground(new GradientDrawable(
                GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{0xB3000000, 0x00000000}));
        FrameLayout.LayoutParams bsp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, Ui.dp(context, 150));
        bsp.gravity = Gravity.BOTTOM;
        bottomScrim.setLayoutParams(bsp);

        // Loading / error hint — moved to the top so it doesn't fight the clock.
        status = new TextView(context);
        status.setTextColor(Ui.TEXT_MUTED);
        status.setTypeface(Ui.medium(context));
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        status.setShadowLayer(6f, 0f, 1f, Color.BLACK);
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        sp.gravity = Gravity.TOP | Gravity.START;
        sp.leftMargin = margin;
        sp.topMargin = Ui.dp(context, 24);
        status.setLayoutParams(sp);

        // Lower-right: photo date (and location when available).
        info = new TextView(context);
        info.setTextColor(0xFFF0F0F0);
        info.setTypeface(Ui.medium(context));
        info.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        info.setShadowLayer(8f, 0f, 1f, Color.BLACK);
        FrameLayout.LayoutParams ip = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        ip.gravity = Gravity.BOTTOM | Gravity.END;
        ip.rightMargin = margin;
        ip.bottomMargin = margin;
        info.setLayoutParams(ip);

        // Bottom-left: a large clock with a "day, date · weather" line beneath it
        // (Nest/Portal photo-frame style). Time uses a clean, AM/PM-free format.
        this.timeFmt = new java.text.SimpleDateFormat(
                android.text.format.DateFormat.is24HourFormat(context) ? "H:mm" : "h:mm",
                java.util.Locale.getDefault());
        clock = new TextView(context);
        clock.setTextColor(Color.WHITE);
        clock.setTypeface(Ui.clockFace(context)); // match the Portal native clock
        clock.setTextSize(TypedValue.COMPLEX_UNIT_SP, 80);
        clock.setShadowLayer(12f, 0f, 2f, Color.BLACK);
        clock.setIncludeFontPadding(false);
        int moonPx = Ui.dp(context, 22);
        android.graphics.drawable.BitmapDrawable md = new android.graphics.drawable.BitmapDrawable(
                context.getResources(), Ui.crescent(moonPx, 0xFF5FA8FF));
        md.setBounds(0, 0, moonPx, moonPx);
        moonDrawable = md;
        dateLine = new TextView(context);
        dateLine.setTextColor(0xFFF0F0F0);
        dateLine.setTypeface(Ui.medium(context));
        dateLine.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        dateLine.setShadowLayer(8f, 0f, 1f, Color.BLACK);
        LinearLayout clockBox = new LinearLayout(context);
        clockBox.setOrientation(LinearLayout.VERTICAL);
        clockBox.addView(clock);
        clockBox.addView(dateLine);
        FrameLayout.LayoutParams cbp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        cbp.gravity = Gravity.BOTTOM | Gravity.START;
        cbp.leftMargin = margin;
        cbp.bottomMargin = Ui.dp(context, 95); // match the Portal home clock's height off the bottom
        clockBox.setLayoutParams(cbp);

        // Warm overlay over the photo that fades in at night (Ambient-EQ-lite): the
        // image is dimmed by the window brightness and tinted cozy-warm here.
        nightTint = new View(context);
        nightTint.setBackgroundColor(0xFFFF8A2A);
        nightTint.setAlpha(0f);
        nightTint.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        // Edge vignette tinted to each photo's mood color (Ambilight-for-photos).
        ambientGlow = new View(context);
        ambientGlow.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        if (!ambientColor) {
            ambientGlow.setVisibility(View.GONE);
        }

        // Shimmer over the dark first frame so it never looks "stuck" while loading.
        shimmer = new ShimmerView(context);
        shimmer.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        if (!showClock) {
            clockBox.setVisibility(View.GONE);
        }
        if (!captions) {
            info.setVisibility(View.GONE);
        }

        root.addView(back);
        root.addView(front);
        root.addView(ambientGlow);
        root.addView(nightTint);
        root.addView(shimmer);
        root.addView(topScrim);
        root.addView(bottomScrim);
        root.addView(status);
        root.addView(info);
        root.addView(clockBox);
        root.addView(buildTouchOverlay());

        // Run clock/night + weather + shimmer from the start so they're alive even
        // during the initial "Loading…" wait before the first photo arrives.
        startClock();
        if (showClock) {
            startWeather();
        }
        shimmer.startSweep();
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
        startClock();
        if (showClock) {
            startWeather();
        }
        if (!shimmerHidden) {
            shimmer.startSweep();
        }
        if (items.isEmpty()) {
            items = assetItems();
            remote = false;
            if (shuffle) {
                smartShuffle(items);
            }
        }
        if (onThisDay) {
            promoteOnThisDay(items); // no-op for bundled samples (no dates)
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
        handler.removeCallbacks(clockTick);
        handler.removeCallbacks(weatherTick);
        shimmer.stopSweep();
        front.animate().cancel();
        if (kbAnim != null) {
            kbAnim.cancel();
            kbAnim = null;
        }
    }

    /**
     * Clear both image layers to black immediately. Called when the frame comes to
     * the foreground so a resumed/relaunched instance doesn't briefly show the
     * PREVIOUS run's last photo (retained in the ImageView) before the new first
     * frame loads. Bumps the anim generation so any in-flight load is discarded.
     */
    public void blank() {
        animGen++;
        if (kbAnim != null) {
            kbAnim.cancel();
            kbAnim = null;
        }
        front.animate().cancel();
        front.setImageDrawable(null);
        front.setAlpha(0f);
        front.setScaleX(1f);
        front.setScaleY(1f);
        front.setTranslationX(0f);
        front.setTranslationY(0f);
        back.setImageDrawable(null);
        back.setScaleX(1f);
        back.setScaleY(1f);
        back.setTranslationX(0f);
        back.setTranslationY(0f);
        back.setColorFilter(null);
        front.setColorFilter(null);
        info.setText("");
        if (ambientGlow != null) {
            ambientGlow.setAlpha(0f);
        }
    }

    /** Swap the photo source at runtime (e.g. bundled samples -> album). */
    public void setItems(List<Slide> newItems) {
        if (newItems == null || newItems.isEmpty()) {
            return;
        }
        items = new ArrayList<>(newItems);
        if (shuffle) {
            smartShuffle(items);
        }
        if (onThisDay) {
            promoteOnThisDay(items);
        }
        remote = true;
        handler.removeCallbacks(autoTick);
        startClock();
        if (showClock) {
            startWeather();
        }
        if (!shimmerHidden) {
            shimmer.startSweep();
        }
        front.animate().cancel();
        front.setAlpha(0f);
        index = 0;
        running = true;
        Log.i(TAG, "Source switched to " + items.size() + " album photos");
        showImmediate(0);
    }

    public void showNext() {
        if (!items.isEmpty()) {
            transitionTo(nextStart(index, curIsPair), SWIPE_FADE_MS);
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
            if (!running || items.isEmpty()) {
                return;
            }
            int step = curIsPair ? 2 : 1;
            int next;
            if (index + step >= items.size()) {
                // Wrapped a full loop: reshuffle so the next loop differs and
                // doesn't open with a photo we just showed.
                if (shuffle && items.size() > 2) {
                    smartShuffle(items);
                }
                next = 0;
            } else {
                next = index + step;
            }
            transitionTo(next, autoFadeMs);
        }
    };

    /** Show item i directly (no crossfade) — used for the first frame. */
    private void showImmediate(final int i) {
        final long gen = ++animGen;
        final int j = pairWith(i);
        final boolean isPair = j >= 0;
        ImageLoader.Callback cb = new ImageLoader.Callback() {
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
                    curIsPair = isPair;
                    status.setText("");
                    info.setText(captionOf(i));
                    noteShown(i);
                    if (isPair) {
                        noteShown(j);
                    }
                    hideShimmer();
                    kbPath = newKenBurnsPath(b);
                    applyKenBurnsStart(back, kbPath);
                    startKenBurnsOnBack(gen);
                    updateAmbient(b);
                    enhanceFilter = enhance ? makeEnhance(b) : null;
                    back.setColorFilter(enhanceFilter);
                }
                prefetchNext(nextStart(i, isPair));
                scheduleAuto();
            }
        };
        if (isPair) {
            loader.loadPair(items.get(i).id, items.get(j).id, reqW, reqH, cb);
        } else {
            loader.load(items.get(i).id, reqW, reqH, cb);
        }
    }

    /** Crossfade to start item {@code next}; loads async, safe to call mid-fade. */
    private void transitionTo(final int next, final long fadeMs) {
        if (items.isEmpty()) {
            return;
        }
        handler.removeCallbacks(autoTick);
        final long gen = ++animGen;
        final int j = pairWith(next);
        final boolean isPair = j >= 0;
        ImageLoader.Callback cb = new ImageLoader.Callback() {
            @Override
            public void onLoaded(final Bitmap bmp) {
                if (gen != animGen) {
                    return; // superseded by a newer request
                }
                if (bmp == null) {
                    index = next;
                    curIsPair = isPair;
                    scheduleAuto();
                    return;
                }
                front.animate().cancel();
                front.setImageBitmap(bmp);
                front.setAlpha(0f);
                index = next;
                curIsPair = isPair;
                status.setText("");
                info.setText(captionOf(next));
                noteShown(next);
                if (isPair) {
                    noteShown(j);
                }
                hideShimmer();
                // Incoming image shows the path's START transform during the fade; when it
                // settles onto `back` we hand off at the same transform and animate to the end.
                kbPath = newKenBurnsPath(bmp);
                applyKenBurnsStart(front, kbPath);
                enhanceFilter = enhance ? makeEnhance(bmp) : null;
                front.setColorFilter(enhanceFilter);
                front.animate().alpha(1f).setDuration(fadeMs).withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        if (gen != animGen) {
                            return;
                        }
                        back.setImageBitmap(bmp);
                        applyKenBurnsStart(back, kbPath);
                        back.setColorFilter(enhanceFilter);
                        front.setAlpha(0f);
                        applyKenBurnsStart(front, null); // reset incoming view for reuse
                        front.setColorFilter(null);
                        startKenBurnsOnBack(gen);
                        updateAmbient(bmp);
                        prefetchNext(nextStart(next, isPair));
                        scheduleAuto();
                    }
                });
            }
        };
        if (isPair) {
            loader.loadPair(items.get(next).id, items.get(j).id, reqW, reqH, cb);
        } else {
            loader.load(items.get(next).id, reqW, reqH, cb);
        }
    }

    /** Index this slide pairs with (the next portrait), or -1 if it doesn't pair. */
    private int pairWith(int start) {
        if (!pairs || items.size() < 2 || start < 0 || start >= items.size()) {
            return -1;
        }
        if (!items.get(start).portrait) {
            return -1;
        }
        int j = start + 1;
        if (j >= items.size() || !items.get(j).portrait) {
            return -1; // don't pair across the loop wrap
        }
        return j;
    }

    /** The start index after this one, stepping over a pair, wrapping to 0. */
    private int nextStart(int start, boolean isPair) {
        int n = start + (isPair ? 2 : 1);
        return n >= items.size() ? 0 : n;
    }

    private void prefetchNext(int startIndex) {
        if (items.size() > 1 && startIndex >= 0 && startIndex < items.size()) {
            loader.prefetch(items.get(startIndex).id, reqW, reqH);
        }
    }

    // ---------------------------------------------------------------- ambient color

    /** Tint the edge vignette to the photo's mood color (no-op when disabled). */
    private void updateAmbient(Bitmap bmp) {
        if (!ambientColor || bmp == null) {
            return;
        }
        Integer c = AmbientColor.extract(bmp);
        if (c == null) {
            ambientGlow.animate().alpha(0f).setDuration(600);
            return;
        }
        int edge = (c & 0x00FFFFFF) | 0x70000000; // ~44% alpha at the edges
        GradientDrawable g = new GradientDrawable();
        g.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        g.setGradientRadius(Math.max(reqW, reqH) * 0.62f);
        g.setGradientCenter(0.5f, 0.5f);
        g.setColors(new int[]{0x00000000, 0x00000000, edge}); // clear core -> color rim
        ambientGlow.setBackground(g);
        ambientGlow.animate().alpha(1f).setDuration(600);
    }

    // ---------------------------------------------------------------- auto-enhance

    /** Per-photo auto-levels + vibrance color filter (null when off / not needed). */
    private android.graphics.ColorMatrixColorFilter makeEnhance(Bitmap bmp) {
        android.graphics.ColorMatrix cm = PhotoEnhance.compute(bmp);
        return cm == null ? null : new android.graphics.ColorMatrixColorFilter(cm);
    }

    // ---------------------------------------------------------------- Ken Burns

    /** Put a view at the start of the current path (or reset to identity if off). */
    private void applyKenBurnsStart(View v, KenBurns p) {
        if (p == null) {
            v.setScaleX(1f);
            v.setScaleY(1f);
            v.setTranslationX(0f);
            v.setTranslationY(0f);
        } else {
            p.applyAt(v, 0f);
        }
    }

    /** Animate the settled image ({@code back}) along the path over the hold time. */
    private void startKenBurnsOnBack(final long gen) {
        if (kbAnim != null) {
            kbAnim.cancel();
            kbAnim = null;
        }
        if (kbPath == null) {
            return;
        }
        final KenBurns p = kbPath;
        ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
        a.setDuration(Math.max(intervalMs, 1200L) + autoFadeMs);
        a.setInterpolator(new LinearInterpolator());
        a.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator va) {
                if (gen != animGen) {
                    va.cancel();
                    return;
                }
                p.applyAt(back, (Float) va.getAnimatedValue());
            }
        });
        kbAnim = a;
        a.start();
    }

    /** Build the path for the slide just loaded (face-biased when available). */
    private KenBurns newKenBurnsPath(Bitmap bmp) {
        if (!kenBurns) {
            return null;
        }
        float[] focus = (faceFraming && bmp != null) ? FaceFocus.find(bmp) : null;
        return KenBurns.random(reqW, reqH, rnd, focus);
    }

    /**
     * A slow zoom + gentle pan applied to the settled image while it's held — the
     * cinematic "Ken Burns" motion. The path is edge-safe: pan is bounded by the
     * slack of the smallest scale on the path, so the scaled image always covers
     * the view (no black/blur reveal). When a focal point is given (e.g. a detected
     * face), the motion eases toward it.
     */
    private static final class KenBurns {
        private static final float ZOOM_MIN = 1.08f;
        private static final float ZOOM_MAX = 1.18f;
        final float s0, s1, tx0, tx1, ty0, ty1;

        private KenBurns(float s0, float s1, float tx0, float tx1, float ty0, float ty1) {
            this.s0 = s0;
            this.s1 = s1;
            this.tx0 = tx0;
            this.tx1 = tx1;
            this.ty0 = ty0;
            this.ty1 = ty1;
        }

        /**
         * @param focus optional {fx, fy} in [0,1] image space to drift toward; null = random.
         */
        static KenBurns random(int w, int h, java.util.Random r, float[] focus) {
            float s0 = ZOOM_MIN + r.nextFloat() * (ZOOM_MAX - ZOOM_MIN);
            float s1 = ZOOM_MIN + r.nextFloat() * (ZOOM_MAX - ZOOM_MIN);
            float minS = Math.min(s0, s1);
            float slackX = (minS - 1f) / 2f * w * 0.9f; // 90% of cover slack, for safety
            float slackY = (minS - 1f) / 2f * h * 0.9f;
            float tx0, tx1, ty0, ty1;
            if (focus != null) {
                // End centred on the focal point (translate so it moves toward centre);
                // start from a gentle offset on the opposite side for visible motion.
                float ex = clamp((0.5f - focus[0]) * 2f, -1f, 1f) * slackX;
                float ey = clamp((0.5f - focus[1]) * 2f, -1f, 1f) * slackY;
                tx1 = ex;
                ty1 = ey;
                tx0 = clamp(ex * -0.4f + (r.nextFloat() - 0.5f) * slackX * 0.5f, -slackX, slackX);
                ty0 = clamp(ey * -0.4f + (r.nextFloat() - 0.5f) * slackY * 0.5f, -slackY, slackY);
                // bias toward zooming IN on the face
                if (s1 < s0) {
                    float t = s0; s0 = s1; s1 = t;
                }
            } else {
                tx0 = (r.nextFloat() * 2f - 1f) * slackX;
                tx1 = (r.nextFloat() * 2f - 1f) * slackX;
                ty0 = (r.nextFloat() * 2f - 1f) * slackY;
                ty1 = (r.nextFloat() * 2f - 1f) * slackY;
            }
            return new KenBurns(s0, s1, tx0, tx1, ty0, ty1);
        }

        void applyAt(View v, float f) {
            float s = s0 + (s1 - s0) * f;
            v.setScaleX(s);
            v.setScaleY(s);
            v.setTranslationX(tx0 + (tx1 - tx0) * f);
            v.setTranslationY(ty0 + (ty1 - ty0) * f);
        }

        private static float clamp(float v, float lo, float hi) {
            return v < lo ? lo : (v > hi ? hi : v);
        }
    }

    private String captionOf(int i) {
        Slide s = items.get(i);
        if (s.caption != null) {
            return s.caption; // explicit override (e.g. an "On this day" badge)
        }
        return s.timeMs == Slide.NO_DATE ? "" : relativeTime(s.timeMs);
    }

    /** "Today" / "Yesterday" / "N days|weeks|months ago", or "MMM yyyy" past a year. */
    private String relativeTime(long timeMs) {
        long now = System.currentTimeMillis();
        long todayDays = (now + TimeZone.getDefault().getOffset(now)) / 86400000L;
        long days = todayDays - timeMs / 86400000L; // timeMs is already a UTC wall clock
        if (days <= 0) {
            return "Today";
        }
        if (days == 1) {
            return "Yesterday";
        }
        if (days < 7) {
            return days + " days ago";
        }
        if (days < 45) {
            long w = Math.round(days / 7.0);
            return w <= 1 ? "1 week ago" : w + " weeks ago";
        }
        if (days < 365) {
            long m = Math.round(days / 30.0);
            return m <= 1 ? "1 month ago" : m + " months ago";
        }
        return monthYearFmt.format(new java.util.Date(timeMs));
    }

    // ---------------------------------------------------------------- smart shuffle

    /** Remember a shown id so a reshuffle de-weights it (most-recent first). */
    private void noteShown(int i) {
        if (items.isEmpty()) {
            return;
        }
        String id = items.get(i).id;
        lastShownId = id;
        recentIds.remove(id);
        recentIds.addFirst(id);
        int cap = Math.max(1, items.size() / 3);
        while (recentIds.size() > cap) {
            recentIds.removeLast();
        }
    }

    /**
     * Shuffle, then push recently shown photos toward the back (stable sort keeps
     * the shuffled order within each group) and make sure we don't open with the
     * photo just shown — so it never feels like "didn't I just see that?".
     */
    private void smartShuffle(List<Slide> list) {
        java.util.Collections.shuffle(list);
        if (recentIds.isEmpty() && lastShownId == null) {
            return;
        }
        final Set<String> recent = new HashSet<>(recentIds);
        java.util.Collections.sort(list, new Comparator<Slide>() {
            @Override
            public int compare(Slide a, Slide b) {
                return Integer.compare(recent.contains(a.id) ? 1 : 0,
                        recent.contains(b.id) ? 1 : 0);
            }
        });
        if (list.size() > 1 && lastShownId != null && list.get(0).id.equals(lastShownId)) {
            int swap = 1;
            for (int i = 1; i < list.size(); i++) {
                if (!recent.contains(list.get(i).id)) {
                    swap = i;
                    break;
                }
            }
            Slide tmp = list.get(0);
            list.set(0, list.get(swap));
            list.set(swap, tmp);
        }
    }

    // ---------------------------------------------------------------- On this day

    /**
     * Move photos taken on today's month+day in a past year to the front (most
     * recent memory first), re-captioned "N years ago today ✨". No-op for items
     * without a capture date (bundled samples).
     */
    private void promoteOnThisDay(List<Slide> list) {
        Calendar now = Calendar.getInstance();
        int curMonth = now.get(Calendar.MONTH);
        int curDay = now.get(Calendar.DAY_OF_MONTH);
        int curYear = now.get(Calendar.YEAR);

        // timeMs is the capture wall-clock expressed in UTC, so read it back in UTC.
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        List<Slide> memories = new ArrayList<>();
        for (Iterator<Slide> it = list.iterator(); it.hasNext(); ) {
            Slide s = it.next();
            if (s.timeMs == Slide.NO_DATE) {
                continue;
            }
            c.setTimeInMillis(s.timeMs);
            if (c.get(Calendar.MONTH) != curMonth || c.get(Calendar.DAY_OF_MONTH) != curDay) {
                continue;
            }
            int yearsAgo = curYear - c.get(Calendar.YEAR);
            if (yearsAgo < 1) {
                continue; // taken today this year, not a memory
            }
            String badge = yearsAgo == 1
                    ? "1 year ago today ✨"
                    : yearsAgo + " years ago today ✨";
            memories.add(new Slide(s.id, badge, s.timeMs, s.portrait));
            it.remove();
        }
        if (memories.isEmpty()) {
            return;
        }
        java.util.Collections.sort(memories, new Comparator<Slide>() {
            @Override
            public int compare(Slide a, Slide b) {
                return Long.compare(b.timeMs, a.timeMs); // most recent first
            }
        });
        list.addAll(0, memories);
        Log.i(TAG, "On this day: promoted " + memories.size() + " memory photo(s)");
    }

    // ---------------------------------------------------------------- clock + date

    private final Runnable clockTick = new Runnable() {
        @Override
        public void run() {
            updateClock();
            handler.postDelayed(this, 60000 - (System.currentTimeMillis() % 60000));
        }
    };

    private void startClock() {
        handler.removeCallbacks(clockTick);
        updateClock();
        handler.postDelayed(clockTick, 60000 - (System.currentTimeMillis() % 60000));
    }

    private void updateClock() {
        Calendar c = Calendar.getInstance();
        nightTint.setAlpha(nightMode
                ? warmthForHour(c.get(Calendar.HOUR_OF_DAY) + c.get(Calendar.MINUTE) / 60f)
                : 0f);
        if (!showClock) {
            return; // night tint still updates above; clock/weather text is off
        }
        clock.setText(timeFmt.format(c.getTime()));
        String date = dateFmt.format(c.getTime());
        if (weather == null) {
            dateLine.setText(date);
        } else if (weather.moon && moonDrawable != null) {
            // Clear night: draw a blue crescent (color emoji can't be tinted) + temp.
            android.text.SpannableStringBuilder sb =
                    new android.text.SpannableStringBuilder(date + "   ");
            int s = sb.length();
            sb.append(" ");
            sb.setSpan(new android.text.style.ImageSpan(
                            moonDrawable, android.text.style.ImageSpan.ALIGN_CENTER),
                    s, s + 1, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.append("  ").append(String.valueOf(weather.temp)).append("°");
            dateLine.setText(sb);
        } else {
            dateLine.setText(date + "   " + weather.label());
        }
    }

    /**
     * Warm-overlay strength by time of day (Ambient-EQ-lite): none in daylight,
     * easing in 20:00→23:00, full overnight, easing out 06:00→08:00.
     */
    private static float warmthForHour(float h) {
        final float MAX = 0.14f;
        if (h >= 8f && h < 20f) {
            return 0f;
        }
        if (h >= 20f && h < 23f) {
            return MAX * (h - 20f) / 3f;
        }
        if (h >= 23f || h < 6f) {
            return MAX;
        }
        return MAX * (8f - h) / 2f; // 06:00–08:00
    }

    // ---------------------------------------------------------------- weather

    private final Runnable weatherTick = new Runnable() {
        @Override
        public void run() {
            refreshWeather();
            handler.postDelayed(this, WEATHER_INTERVAL_MS);
        }
    };

    private void startWeather() {
        handler.removeCallbacks(weatherTick);
        // Fetch immediately if we have nothing yet; otherwise keep the periodic cadence.
        handler.postDelayed(weatherTick, weather == null ? 0 : WEATHER_INTERVAL_MS);
    }

    private void refreshWeather() {
        loader.executor().execute(new Runnable() {
            @Override
            public void run() {
                final Weather.Now now = Weather.fetch(fahrenheit);
                if (now == null) {
                    return;
                }
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        weather = now;
                        updateClock();
                    }
                });
            }
        });
    }

    // ---------------------------------------------------------------- loading shimmer

    private void hideShimmer() {
        if (shimmerHidden) {
            return;
        }
        shimmerHidden = true;
        shimmer.animate().alpha(0f).setDuration(400).withEndAction(new Runnable() {
            @Override
            public void run() {
                shimmer.stopSweep();
                shimmer.setVisibility(View.GONE);
            }
        });
    }

    /** A soft diagonal light band sweeping across a dark surface — a calm "loading". */
    private static final class ShimmerView extends View {
        private final Paint paint = new Paint();
        private final ValueAnimator anim;
        private float pos = -0.3f; // sweep centre as a fraction of width

        ShimmerView(Context c) {
            super(c);
            setBackgroundColor(0xFF1A1A1A);
            anim = ValueAnimator.ofFloat(-0.3f, 1.3f);
            anim.setDuration(1600);
            anim.setRepeatCount(ValueAnimator.INFINITE);
            anim.setInterpolator(new LinearInterpolator());
            anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator a) {
                    pos = (Float) a.getAnimatedValue();
                    invalidate();
                }
            });
        }

        void startSweep() {
            if (!anim.isStarted()) {
                anim.start();
            }
        }

        void stopSweep() {
            anim.cancel();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int w = getWidth();
            int h = getHeight();
            if (w == 0 || h == 0) {
                return;
            }
            float band = w * 0.35f;
            float cx = pos * w;
            paint.setShader(new LinearGradient(cx - band, 0, cx + band, 0,
                    new int[]{0x00FFFFFF, 0x14FFFFFF, 0x00FFFFFF},
                    new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, w, h, paint);
        }
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
