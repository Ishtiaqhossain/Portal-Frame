package com.example.portalframe;

import android.app.Activity;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.hardware.Camera;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.Log;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;

import java.util.EnumMap;
import java.util.Map;

/**
 * On-device setup, shown as the "Photos" app icon. Two screens inside one Activity:
 *
 *  - Screen A (status/settings): shows the album currently playing (if any), how to
 *    get a Google Photos shared-album QR, and slideshow settings (seconds per photo,
 *    shuffle, transition speed). All persist to the same {@code portalframe} prefs the
 *    slideshow reads ({@link ConfigReceiver}).
 *  - Screen B (scanner): live camera QR scan. A Google Photos share link is validated
 *    and written to {@code KEY_ALBUM}; the slideshow picks it up on its next launch.
 *
 * QR (not OCR) is used because the share link ends in a random, case-sensitive token.
 * The decoder is ZXing's pure-Java {@code core} (vendored in app/libs) — its QR
 * detector is rotation-invariant, so a plain {@code decode} handles a tilted code.
 * Uses the legacy {@link Camera} API for compactness on this fixed API-29 device.
 */
@SuppressWarnings("deprecation")
public class PhotosActivity extends Activity {

    private static final String TAG = "PortalFrame";
    private static final int REQ_CAMERA = 1;

    private static final long[] DELAY_CHOICES = {4000L, 6000L, 10000L, 30000L, 60000L};
    private static final long[] FADE_CHOICES = {2000L, 1200L, 500L}; // Slow, Normal, Fast
    private static final String[] FADE_LABELS = {"Slow", "Normal", "Fast"};

    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    private static final Map<DecodeHintType, Object> HINTS =
            new EnumMap<>(DecodeHintType.class);
    static {
        HINTS.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final QRCodeReader reader = new QRCodeReader();

    private FrameLayout root;

    // Scanner state
    private Camera camera;
    private SurfaceView surface;
    private TextView scanHint;
    private volatile boolean scanning = false;
    private boolean stopArmed = false; // "Stop showing photos" two-tap confirm
    private int previewW, previewH;    // cached so we don't hit getParameters() per frame
    private int frameCount;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        setContentView(root);
        showStatus();
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopCamera();
    }

    // ---------------------------------------------------------------- prefs

    private SharedPreferences prefs() {
        return getSharedPreferences(ConfigReceiver.PREFS, MODE_PRIVATE);
    }

    private String album() {
        return prefs().getString(ConfigReceiver.KEY_ALBUM, "");
    }

    private long getDelay() {
        return prefs().getLong(ConfigReceiver.KEY_DELAY_MS, ConfigReceiver.DEFAULT_DELAY_MS);
    }

    private long getFade() {
        return prefs().getLong(ConfigReceiver.KEY_FADE_MS, ConfigReceiver.DEFAULT_FADE_MS);
    }

    private boolean getShuffle() {
        return prefs().getBoolean(ConfigReceiver.KEY_SHUFFLE, false);
    }

    // ------------------------------------------------------- Screen A (status)

    private void showStatus() {
        stopCamera();
        stopArmed = false;
        root.removeAllViews();

        ScrollView sv = new ScrollView(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(24);
        col.setPadding(pad, pad, pad, pad);
        sv.addView(col);

        final String url = album();
        final boolean hasAlbum = url != null && !url.isEmpty();

        col.addView(title(hasAlbum ? "Your photos" : "Show your Google Photos"));

        if (hasAlbum) {
            col.addView(body("Currently showing:"));
            TextView u = body(url);
            u.setTextColor(0xFF9AD0FF);
            col.addView(u);
        }

        col.addView(body(
                "On your phone: open Google Photos, open the album you want, tap Share, and "
                        + "choose Create link / show its QR code. Then tap "
                        + (hasAlbum ? "Change album" : "Add album")
                        + " below and hold your phone up to this screen.\n\n"
                        + "Tip: the album must be shared by link so the frame can see it."));

        col.addView(button(hasAlbum ? "Change album" : "Add album", new Runnable() {
            @Override
            public void run() {
                startScan();
            }
        }));

        col.addView(button("Enter link manually", new Runnable() {
            @Override
            public void run() {
                showManualEntry();
            }
        }));

        if (hasAlbum) {
            final Button stop = button("Stop showing photos", null);
            stop.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (!stopArmed) {
                        stopArmed = true;
                        stop.setText("Tap again to confirm");
                    } else {
                        prefs().edit().remove(ConfigReceiver.KEY_ALBUM).apply();
                        toast("Showing sample photos");
                        showStatus();
                    }
                }
            });
            col.addView(stop);
        }

        col.addView(sectionLabel("Slideshow"));

        final Button delayBtn = button("Seconds per photo:  " + fmtDelay(getDelay()), null);
        delayBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                long next = cycle(DELAY_CHOICES, getDelay(), 0);
                prefs().edit().putLong(ConfigReceiver.KEY_DELAY_MS, next).apply();
                delayBtn.setText("Seconds per photo:  " + fmtDelay(next));
            }
        });
        col.addView(delayBtn);

        final Button shuffleBtn =
                button("Shuffle photos:  " + (getShuffle() ? "On" : "Off"), null);
        shuffleBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean next = !getShuffle();
                prefs().edit().putBoolean(ConfigReceiver.KEY_SHUFFLE, next).apply();
                shuffleBtn.setText("Shuffle photos:  " + (next ? "On" : "Off"));
            }
        });
        col.addView(shuffleBtn);

        final Button fadeBtn = button("Transition:  " + fadeLabel(getFade()), null);
        fadeBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                long next = cycle(FADE_CHOICES, getFade(), 1);
                prefs().edit().putLong(ConfigReceiver.KEY_FADE_MS, next).apply();
                fadeBtn.setText("Transition:  " + fadeLabel(next));
            }
        });
        col.addView(fadeBtn);

        col.addView(body("These change how the slideshow plays."));

        col.addView(button("Done", new Runnable() {
            @Override
            public void run() {
                finish();
            }
        }));

        root.addView(sv);
    }

    private static long cycle(long[] choices, long cur, int fallback) {
        for (int i = 0; i < choices.length; i++) {
            if (choices[i] == cur) {
                return choices[(i + 1) % choices.length];
            }
        }
        return choices[fallback];
    }

    private static String fadeLabel(long ms) {
        for (int i = 0; i < FADE_CHOICES.length; i++) {
            if (FADE_CHOICES[i] == ms) {
                return FADE_LABELS[i];
            }
        }
        return "Normal";
    }

    private static String fmtDelay(long ms) {
        long s = ms / 1000;
        return s >= 60 ? (s / 60) + "m" : s + "s";
    }

    // ------------------------------------------------ Screen C (manual entry)

    /** Type/paste the album link using the on-screen keyboard (QR-less fallback). */
    private void showManualEntry() {
        stopCamera();
        stopArmed = false;
        root.removeAllViews();

        ScrollView sv = new ScrollView(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(24);
        col.setPadding(pad, pad, pad, pad);
        sv.addView(col);

        col.addView(title("Enter album link"));
        col.addView(body("Paste or type the Google Photos shared-album link (it starts with "
                + "https://photos.app.goo.gl/ or https://photos.google.com/share/)."));

        final EditText edit = new EditText(this);
        edit.setSingleLine(true);
        edit.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_URI
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        edit.setHint("https://photos.app.goo.gl/…");
        edit.setText(album());
        edit.setSelection(edit.getText().length());
        LinearLayout.LayoutParams ep = new LinearLayout.LayoutParams(MATCH, WRAP);
        ep.topMargin = dp(8);
        edit.setLayoutParams(ep);
        col.addView(edit);

        col.addView(button("Save", new Runnable() {
            @Override
            public void run() {
                String url = edit.getText().toString().trim();
                if (!isPhotosLink(url)) {
                    toast("That doesn't look like a Google Photos link");
                    return;
                }
                prefs().edit().putString(ConfigReceiver.KEY_ALBUM, url).apply();
                Log.i(TAG, "album_url set via manual entry: " + url);
                hideKeyboard(edit);
                toast("Album set ✓");
                showStatus();
            }
        }));
        col.addView(button("Cancel", new Runnable() {
            @Override
            public void run() {
                hideKeyboard(edit);
                showStatus();
            }
        }));

        root.addView(sv);

        // Pop the on-screen keyboard for the field.
        edit.requestFocus();
        edit.post(new Runnable() {
            @Override
            public void run() {
                InputMethodManager imm =
                        (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                if (imm != null) {
                    imm.showSoftInput(edit, InputMethodManager.SHOW_IMPLICIT);
                }
            }
        });
    }

    private void hideKeyboard(View v) {
        InputMethodManager imm =
                (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
        }
    }

    // ------------------------------------------------------ Screen B (scanner)

    private void startScan() {
        if (checkSelfPermission(android.Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.CAMERA}, REQ_CAMERA);
            return;
        }
        showScanner();
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] results) {
        if (req == REQ_CAMERA) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
                showScanner();
            } else {
                toast("Camera permission is needed to scan the QR code");
                showStatus();
            }
        }
    }

    private void showScanner() {
        stopArmed = false;
        root.removeAllViews();

        FrameLayout f = new FrameLayout(this);

        surface = new SurfaceView(this);
        f.addView(surface, new FrameLayout.LayoutParams(MATCH, MATCH));

        // Centered target box so the user knows where to aim the QR.
        View box = new View(this);
        GradientDrawable border = new GradientDrawable();
        border.setColor(Color.TRANSPARENT);
        border.setStroke(dp(3), 0xFFFFFFFF);
        border.setCornerRadius(dp(12));
        box.setBackground(border);
        int boxSize = dp(320);
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(boxSize, boxSize);
        bp.gravity = Gravity.CENTER;
        f.addView(box, bp);

        scanHint = new TextView(this);
        scanHint.setText("Make the QR fill your phone screen at full brightness. Hold the phone "
                + "about half a meter away — far enough to look sharp, close enough to fill the "
                + "white box. Hold steady.");
        scanHint.setTextColor(Color.WHITE);
        scanHint.setTextSize(16f);
        scanHint.setGravity(Gravity.CENTER_HORIZONTAL);
        scanHint.setShadowLayer(6f, 0f, 1f, Color.BLACK);
        FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(dp(560), WRAP);
        hp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        hp.topMargin = dp(20);
        f.addView(scanHint, hp);

        Button typeLink = button("Can't scan? Type the link", new Runnable() {
            @Override
            public void run() {
                showManualEntry();
            }
        });
        FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(WRAP, WRAP);
        tp.gravity = Gravity.BOTTOM | Gravity.START;
        tp.bottomMargin = dp(24);
        tp.leftMargin = dp(24);
        f.addView(typeLink, tp);

        Button cancel = button("Cancel", new Runnable() {
            @Override
            public void run() {
                showStatus();
            }
        });
        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(WRAP, WRAP);
        cp.gravity = Gravity.BOTTOM | Gravity.END;
        cp.bottomMargin = dp(24);
        cp.rightMargin = dp(24);
        f.addView(cancel, cp);

        root.addView(f);

        surface.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder h) {
                openCamera(h);
            }

            @Override
            public void surfaceChanged(SurfaceHolder h, int fmt, int w, int height) {
            }

            @Override
            public void surfaceDestroyed(SurfaceHolder h) {
                stopCamera();
            }
        });
    }

    private void openCamera(SurfaceHolder holder) {
        try {
            int id = pickCamera();
            camera = Camera.open(id);
            Camera.Parameters pr = camera.getParameters();

            // Largest supported preview (cap ~1920 wide to bound per-frame CPU) — more pixels
            // give a small/distant QR enough modules to decode on this fixed-focus camera.
            Camera.Size best = null;
            for (Camera.Size s : pr.getSupportedPreviewSizes()) {
                if (s.width <= 1920 && (best == null || s.width > best.width)) {
                    best = s;
                }
            }
            if (best != null) {
                pr.setPreviewSize(best.width, best.height);
            }
            pr.setPreviewFormat(ImageFormat.NV21);
            // Camera is fixed-focus (no autofocus on Portal); a moderate zoom enlarges a
            // far-but-in-focus QR so it carries more pixels.
            if (pr.isZoomSupported()) {
                int z = Math.max(1, pr.getMaxZoom() / 3);
                pr.setZoom(z);
            }
            // A bright phone screen blows out to white if AE meters the whole dim room. Meter
            // the centre box (where the QR is) and bias exposure down so the QR is readable
            // immediately instead of after AE slowly adapts.
            if (pr.getMaxNumMeteringAreas() >= 1) {
                java.util.List<Camera.Area> areas = new java.util.ArrayList<>();
                areas.add(new Camera.Area(new Rect(-350, -350, 350, 350), 1000));
                pr.setMeteringAreas(areas);
            }
            float step = pr.getExposureCompensationStep();
            int comp = step > 0 ? Math.round(-2.0f / step) : pr.getMinExposureCompensation();
            comp = Math.max(pr.getMinExposureCompensation(),
                    Math.min(pr.getMaxExposureCompensation(), comp));
            pr.setExposureCompensation(comp);
            camera.setParameters(pr);
            Log.i(TAG, "QR scan: exposureComp=" + comp + " meteringAreas="
                    + pr.getMaxNumMeteringAreas());

            Camera.Size ps = camera.getParameters().getPreviewSize();
            previewW = ps.width;
            previewH = ps.height;
            frameCount = 0;

            camera.setDisplayOrientation(0); // preview is feedback only; decode uses raw data
            camera.setPreviewDisplay(holder);
            scanning = true;
            camera.setPreviewCallback(previewCb);
            camera.startPreview();
            Log.i(TAG, "camera opened id=" + id + " preview=" + previewW + "x" + previewH
                    + " zoomSupported=" + pr.isZoomSupported());
        } catch (Exception e) {
            Log.e(TAG, "camera open failed", e);
            toast("Couldn't open the camera");
            showStatus();
        }
    }

    private static int pickCamera() {
        int n = Camera.getNumberOfCameras();
        Camera.CameraInfo info = new Camera.CameraInfo();
        for (int i = 0; i < n; i++) {
            Camera.getCameraInfo(i, info);
            if (info.facing == Camera.CameraInfo.CAMERA_FACING_BACK) {
                return i;
            }
        }
        return 0; // Portal has only a front camera, which faces the user holding the phone
    }

    private final Camera.PreviewCallback previewCb = new Camera.PreviewCallback() {
        @Override
        public void onPreviewFrame(byte[] data, Camera cam) {
            if (!scanning || data == null || previewW == 0) {
                return;
            }
            frameCount++;
            if (frameCount == 1) {
                Log.i(TAG, "QR scan: first frame " + previewW + "x" + previewH
                        + " bytes=" + data.length);
            }
            if ((frameCount & 1) == 0) {
                return; // decode every other frame to keep the multi-pass cost down
            }
            final String text = tryDecode(data, previewW, previewH);
            if (text != null) {
                scanning = false; // stop processing further frames until we decide
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        onQr(text);
                    }
                });
            } else if (frameCount % 30 == 1) {
                Log.i(TAG, "QR scan: " + frameCount + " frames, no QR decoded yet");
            }
        }
    };

    /**
     * Try to decode a QR from an NV21 luma plane. The Portal's front camera is
     * fixed-focus and may deliver mirrored frames, so we try: full frame normal,
     * full frame mirrored, then a centered crop (more relative resolution for a
     * small/central code) normal + mirrored. Returns the text or null.
     */
    private String tryDecode(byte[] data, int w, int h) {
        String r = decodeRegion(data, w, h, 0, 0, w, h, false);
        if (r == null) r = decodeRegion(data, w, h, 0, 0, w, h, true);
        if (r == null) {
            int side = (int) (Math.min(w, h) * 0.6f);
            int left = (w - side) / 2;
            int top = (h - side) / 2;
            r = decodeRegion(data, w, h, left, top, side, side, false);
            if (r == null) r = decodeRegion(data, w, h, left, top, side, side, true);
        }
        return r;
    }

    private String decodeRegion(byte[] data, int w, int h,
                                int left, int top, int rw, int rh, boolean mirror) {
        try {
            PlanarYUVLuminanceSource src = new PlanarYUVLuminanceSource(
                    data, w, h, left, top, rw, rh, mirror);
            Result res = reader.decode(new BinaryBitmap(new HybridBinarizer(src)), HINTS);
            return res.getText();
        } catch (Exception notFound) {
            return null;
        } finally {
            reader.reset();
        }
    }

    private void onQr(String text) {
        String url = text == null ? "" : text.trim();
        if (!isPhotosLink(url)) {
            if (scanHint != null) {
                scanHint.setText("That QR isn't a Google Photos album link — try again");
            }
            scanning = true; // keep scanning
            return;
        }
        prefs().edit().putString(ConfigReceiver.KEY_ALBUM, url).apply();
        Log.i(TAG, "album_url set via QR: " + url);
        stopCamera();
        if (scanHint != null) {
            scanHint.setText("Album set ✓ — your photos will appear shortly");
        }
        toast("Album set ✓");
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                showStatus();
            }
        }, 1500);
    }

    private static boolean isPhotosLink(String s) {
        return s.startsWith("https://photos.app.goo.gl/")
                || s.startsWith("https://photos.google.com/share/");
    }

    private void stopCamera() {
        scanning = false;
        if (camera != null) {
            try {
                camera.setPreviewCallback(null);
                camera.stopPreview();
                camera.release();
            } catch (Exception ignored) {
                // best-effort teardown
            }
            camera = null;
        }
    }

    // ----------------------------------------------------------------- view helpers

    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }

    private TextView title(String t) {
        TextView tv = new TextView(this);
        tv.setText(t);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(26f);
        tv.setPadding(0, 0, 0, dp(12));
        return tv;
    }

    private TextView sectionLabel(String t) {
        TextView tv = new TextView(this);
        tv.setText(t);
        tv.setTextColor(0xFFBBBBBB);
        tv.setTextSize(15f);
        tv.setPadding(0, dp(16), 0, dp(4));
        return tv;
    }

    private TextView body(String t) {
        TextView tv = new TextView(this);
        tv.setText(t);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(16f);
        tv.setPadding(0, dp(4), 0, dp(4));
        return tv;
    }

    private Button button(String t, final Runnable onClick) {
        Button b = new Button(this);
        b.setText(t);
        b.setAllCaps(false);
        b.setTextSize(18f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MATCH, WRAP);
        lp.topMargin = dp(10);
        b.setLayoutParams(lp);
        if (onClick != null) {
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    onClick.run();
                }
            });
        }
        return b;
    }

    private void toast(String m) {
        Toast.makeText(this, m, Toast.LENGTH_SHORT).show();
    }
}
