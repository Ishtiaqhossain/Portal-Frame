package com.portalhacks.frame;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.provider.Settings;
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
import android.util.TypedValue;
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
 * On-device setup, shown as the "Photos" app icon. Styled with the Portal design
 * system ({@link Ui}: dark theme, platform palette, Inter type, room-distance hit
 * targets, centred max-width column, reserved top overlay inset). Three screens:
 *
 *  - Status/settings: album currently playing, how to add one, slideshow settings.
 *  - Scanner: live camera QR scan (validated, written to {@code KEY_ALBUM}).
 *  - Manual entry: type/paste the link with the on-screen keyboard (QR-less fallback).
 *
 * All persist to the same {@code portalframe} prefs the slideshow reads
 * ({@link ConfigReceiver}). Uses the legacy {@link Camera} API for compactness on
 * this fixed API-29 device.
 */
@SuppressWarnings("deprecation")
public class PhotosActivity extends Activity {

    private static final String TAG = "PortalFrame";
    private static final int REQ_CAMERA = 1;
    private static final String SCREENSAVER_COMPONENT =
            "com.portalhacks.frame/com.portalhacks.frame.FrameDreamService";

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
    private boolean showingStatus = false; // re-check screensaver state on return
    private int previewW, previewH;    // cached so we don't hit getParameters() per frame
    private int frameCount;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        root = new FrameLayout(this);
        root.setBackgroundColor(Ui.BG);
        setContentView(root);
        // The Compose SettingsActivity hands off here for the camera / manual flows.
        String goto_ = getIntent() != null ? getIntent().getStringExtra("goto") : null;
        if ("scan".equals(goto_)) {
            startScan();
        } else if ("manual".equals(goto_)) {
            showManualEntry();
        } else {
            showStatus();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Returning from the system Screen-saver picker: refresh the status so the
        // "Screensaver" card reflects the new selection.
        if (showingStatus) {
            showStatus();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopCamera();
    }

    // ------------------------------------------------ screensaver setup

    /** True if Portal Frame is the enabled system screensaver. */
    private boolean isOurScreensaver() {
        try {
            boolean enabled = Settings.Secure.getInt(
                    getContentResolver(), "screensaver_enabled", 0) == 1;
            String comp = Settings.Secure.getString(
                    getContentResolver(), "screensaver_components");
            return enabled && comp != null && comp.contains(getPackageName());
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Deep-link to the system Screen-saver picker, where the user selects
     * "Portal Frame". A normal app can't set the screensaver itself (it needs
     * WRITE_SECURE_SETTINGS), but the Settings app can — so this is the no-ADB path.
     */
    private void openScreensaverSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_DREAM_SETTINGS));
        } catch (Exception e) {
            toast("Open Settings → Display → Screen saver, then choose Frame");
        }
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

    private boolean getPairs() {
        return prefs().getBoolean(ConfigReceiver.KEY_PAIRS, ConfigReceiver.DEFAULT_PAIRS);
    }

    // ------------------------------------------------------- Status / settings

    private void showStatus() {
        stopCamera();
        stopArmed = false;
        showingStatus = true;
        root.removeAllViews();
        LinearLayout col = Ui.screen(this, root, Ui.MAX_W_WIDE_DP);

        final String url = album();
        final boolean hasAlbum = url != null && !url.isEmpty();

        col.addView(Ui.title(this, hasAlbum ? "Your photos" : "Show your Google Photos"));

        LinearLayout[] panes = Ui.twoColumns(this, col);
        LinearLayout left = panes[0];
        LinearLayout right = panes[1];

        // ---------------------------------------------------- LEFT: Source
        // Screensaver — the slideshow only runs once "Portal Frame" is chosen in
        // the system picker (a normal app can't set this itself).
        final boolean ssActive = isOurScreensaver();
        LinearLayout ssCard = Ui.card(this);
        ssCard.addView(Ui.sectionLabel(this, "Screensaver"));
        TextView ssBody = Ui.body(this, ssActive
                ? "✓ Frame is your screensaver. Your photos appear when the Portal is idle."
                : "Almost there — tap below, then choose “Frame” so your photos show "
                        + "when the Portal is idle.");
        topMargin(ssBody, 6);
        ssCard.addView(ssBody);
        ssCard.addView(ssActive
                ? Ui.outline(this, "Change screensaver", new Runnable() {
                    @Override public void run() { openScreensaverSettings(); }
                })
                : Ui.primary(this, "Use as screensaver", new Runnable() {
                    @Override public void run() { openScreensaverSettings(); }
                }));
        left.addView(ssCard);

        // Album
        LinearLayout albumCard = Ui.card(this);
        albumCard.addView(Ui.sectionLabel(this, hasAlbum ? "Album" : "No album yet"));
        if (hasAlbum) {
            TextView u = Ui.body(this, url);
            u.setTextColor(Ui.BLUE);
            u.setSingleLine(true);
            u.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            topMargin(u, 6);
            albumCard.addView(u);
        } else {
            TextView none = Ui.body(this,
                    "Add a Google Photos shared album to show your own photos.");
            topMargin(none, 6);
            albumCard.addView(none);
        }
        albumCard.addView(Ui.primary(this, hasAlbum ? "Change album" : "Add album", new Runnable() {
            @Override public void run() { startScan(); }
        }));
        albumCard.addView(Ui.outline(this, "Enter link manually", new Runnable() {
            @Override public void run() { showManualEntry(); }
        }));
        if (hasAlbum) {
            final Button stop = Ui.destructive(this, "Stop showing photos", null);
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
            albumCard.addView(stop);
        }
        left.addView(albumCard);

        // ------------------------------------------------- RIGHT: Slideshow
        LinearLayout slideCard = Ui.card(this);
        slideCard.addView(Ui.sectionLabel(this, "Slideshow"));

        final View delayRow = Ui.row(this, "Seconds per photo", fmtDelay(getDelay()), null);
        delayRow.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                long next = cycle(DELAY_CHOICES, getDelay(), 0);
                prefs().edit().putLong(ConfigReceiver.KEY_DELAY_MS, next).apply();
                Ui.setRowValue(delayRow, fmtDelay(next));
            }
        });
        topMargin(delayRow, 8);
        slideCard.addView(delayRow);
        slideCard.addView(Ui.hairline(this));

        final View shuffleRow = Ui.row(this, "Shuffle photos", getShuffle() ? "On" : "Off", null);
        shuffleRow.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                boolean next = !getShuffle();
                prefs().edit().putBoolean(ConfigReceiver.KEY_SHUFFLE, next).apply();
                Ui.setRowValue(shuffleRow, next ? "On" : "Off");
            }
        });
        slideCard.addView(shuffleRow);
        slideCard.addView(Ui.hairline(this));

        final View fadeRow = Ui.row(this, "Transition", fadeLabel(getFade()), null);
        fadeRow.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                long next = cycle(FADE_CHOICES, getFade(), 1);
                prefs().edit().putLong(ConfigReceiver.KEY_FADE_MS, next).apply();
                Ui.setRowValue(fadeRow, fadeLabel(next));
            }
        });
        slideCard.addView(fadeRow);
        slideCard.addView(Ui.hairline(this));

        final View pairsRow = Ui.row(this, "Side-by-side portraits", getPairs() ? "On" : "Off", null);
        pairsRow.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                boolean next = !getPairs();
                prefs().edit().putBoolean(ConfigReceiver.KEY_PAIRS, next).apply();
                Ui.setRowValue(pairsRow, next ? "On" : "Off");
            }
        });
        slideCard.addView(pairsRow);
        slideCard.addView(Ui.hairline(this));
        slideCard.addView(boolRow("Cinematic motion",
                ConfigReceiver.KEY_KEN_BURNS, ConfigReceiver.DEFAULT_KEN_BURNS));
        slideCard.addView(Ui.hairline(this));
        slideCard.addView(boolRow("Photo captions",
                ConfigReceiver.KEY_CAPTIONS, ConfigReceiver.DEFAULT_CAPTIONS));
        right.addView(slideCard);

        // Ambient & on-device AI demos
        LinearLayout aiCard = Ui.card(this);
        aiCard.addView(Ui.sectionLabel(this, "Ambient intelligence"));
        View faceRow = boolRow("Face-aware framing",
                ConfigReceiver.KEY_FACE, ConfigReceiver.DEFAULT_FACE);
        topMargin(faceRow, 8);
        aiCard.addView(faceRow);
        aiCard.addView(Ui.hairline(this));
        aiCard.addView(boolRow("Auto-enhance photos",
                ConfigReceiver.KEY_ENHANCE, ConfigReceiver.DEFAULT_ENHANCE));
        aiCard.addView(Ui.hairline(this));
        aiCard.addView(boolRow("Ambient color glow",
                ConfigReceiver.KEY_AMBIENT, ConfigReceiver.DEFAULT_AMBIENT));
        aiCard.addView(Ui.hairline(this));
        aiCard.addView(boolRow("Clock & weather",
                ConfigReceiver.KEY_CLOCK, ConfigReceiver.DEFAULT_CLOCK));
        aiCard.addView(Ui.hairline(this));
        aiCard.addView(boolRow("Night warmth",
                ConfigReceiver.KEY_NIGHT, ConfigReceiver.DEFAULT_NIGHT));
        aiCard.addView(Ui.hairline(this));
        aiCard.addView(boolRow("On This Day memories",
                ConfigReceiver.KEY_ON_THIS_DAY, ConfigReceiver.DEFAULT_ON_THIS_DAY));
        right.addView(aiCard);

        // Tips
        LinearLayout tipsCard = Ui.card(this);
        tipsCard.addView(Ui.sectionLabel(this, "Tips"));
        TextView howto = Ui.body(this,
                "On your phone: open Google Photos, open the album you want, tap Share, and "
                        + "choose Create link / show its QR code. Then tap "
                        + (hasAlbum ? "Change album" : "Add album")
                        + " here and hold your phone up to this screen.\n\n"
                        + "Tip: the album must be shared by link so the frame can see it.");
        topMargin(howto, 6);
        tipsCard.addView(howto);
        right.addView(tipsCard);

        // ----------------------------------------------------------- Done
        Button done = Ui.secondary(this, "Done", new Runnable() {
            @Override public void run() { finish(); }
        });
        topMargin(done, 24);
        col.addView(done);
    }

    private void topMargin(View v, int dp) {
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) v.getLayoutParams();
        if (lp == null) {
            lp = new LinearLayout.LayoutParams(MATCH, WRAP);
        }
        lp.topMargin = Ui.dp(this, dp);
        v.setLayoutParams(lp);
    }

    /** A tappable On/Off row bound to a boolean pref. */
    private View boolRow(String label, final String key, final boolean def) {
        final View row = Ui.row(this, label, prefs().getBoolean(key, def) ? "On" : "Off", null);
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean next = !prefs().getBoolean(key, def);
                prefs().edit().putBoolean(key, next).apply();
                Ui.setRowValue(row, next ? "On" : "Off");
            }
        });
        return row;
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

    // ------------------------------------------------ Manual entry

    /** Type/paste the album link using the on-screen keyboard (QR-less fallback). */
    private void showManualEntry() {
        stopCamera();
        stopArmed = false;
        showingStatus = false;
        root.removeAllViews();
        LinearLayout col = Ui.screen(this, root);

        col.addView(Ui.title(this, "Enter album link"));
        TextView help = Ui.body(this,
                "Paste or type the Google Photos shared-album link (it starts with "
                        + "https://photos.app.goo.gl/ or https://photos.google.com/share/).");
        topMargin(help, 12);
        col.addView(help);

        final EditText edit = Ui.field(this, "https://photos.app.goo.gl/…");
        edit.setSingleLine(true);
        edit.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_URI
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        edit.setText(album());
        edit.setSelection(edit.getText().length());
        col.addView(edit);

        col.addView(Ui.primary(this, "Save", new Runnable() {
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
        col.addView(Ui.secondary(this, "Cancel", new Runnable() {
            @Override
            public void run() {
                hideKeyboard(edit);
                showStatus();
            }
        }));

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

    // ------------------------------------------------------ Scanner

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
        showingStatus = false;
        root.removeAllViews();

        FrameLayout f = new FrameLayout(this);
        f.setBackgroundColor(Color.BLACK);

        surface = new SurfaceView(this);
        f.addView(surface, new FrameLayout.LayoutParams(MATCH, MATCH));

        // Centred target box (Portal blue) so the user knows where to aim the QR.
        View box = new View(this);
        GradientDrawable border = Ui.roundRect(0x00000000, Ui.dp(this, 20));
        border.setStroke(Ui.dp(this, 4), Ui.BLUE);
        box.setBackground(border);
        int boxSize = Ui.dp(this, 340);
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(boxSize, boxSize);
        bp.gravity = Gravity.CENTER;
        f.addView(box, bp);

        scanHint = new TextView(this);
        scanHint.setText("Make the QR fill your phone screen at full brightness, then hold it "
                + "about half a meter away — sharp and filling the blue box. Hold steady.");
        scanHint.setTextColor(0xFFF0F0F0);
        scanHint.setTypeface(Ui.medium(this));
        scanHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        scanHint.setGravity(Gravity.CENTER_HORIZONTAL);
        scanHint.setLineSpacing(Ui.dp(this, 4), 1f);
        scanHint.setShadowLayer(8f, 0f, 1f, Color.BLACK);
        FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(Ui.dp(this, 620), WRAP);
        hp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        hp.topMargin = Ui.dp(this, 72); // clear the top system overlay
        f.addView(scanHint, hp);

        Button typeLink = Ui.secondary(this, "Can't scan? Type the link", new Runnable() {
            @Override public void run() { showManualEntry(); }
        });
        FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(WRAP, WRAP);
        tp.gravity = Gravity.BOTTOM | Gravity.START;
        tp.bottomMargin = Ui.dp(this, 28);
        tp.leftMargin = Ui.dp(this, 28);
        f.addView(typeLink, tp);

        Button cancel = Ui.secondary(this, "Cancel", new Runnable() {
            @Override public void run() { showStatus(); }
        });
        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(WRAP, WRAP);
        cp.gravity = Gravity.BOTTOM | Gravity.END;
        cp.bottomMargin = Ui.dp(this, 28);
        cp.rightMargin = Ui.dp(this, 28);
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

    private void toast(String m) {
        Toast.makeText(this, m, Toast.LENGTH_SHORT).show();
    }
}
