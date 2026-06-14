package com.portalhacks.frame;

import android.app.Activity;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Portal design-system helpers (see Meta's "portal" skill: design-guidelines.md /
 * compose-theme.md), translated for this dependency-free, view-based app.
 *
 * Dark-forced theme (Portal's white system-overlay pills stay legible, matches the
 * native launcher/Settings), Portal platform palette, bundled Inter typography,
 * generous spacing and room-distance hit targets (64dp min, 96dp primary), a
 * centred max-width column, and a reserved top inset for the system overlay strip.
 */
final class Ui {
    private Ui() {}

    // ---- Palette: Portal platform tokens, dark theme (never pure #000/#FFF) ----
    static final int BLUE = 0xFF1990FF;          // primary actions, selected
    static final int BLUE_PRESSED = 0xFF1877F2;  // pressed
    static final int GREEN = 0xFF6CD64F;         // success
    static final int RED = 0xFFFA484E;           // error / destructive
    static final int BG = 0xFF1A1A1A;            // app background
    static final int SURFACE = 0xFF2B2B2B;       // cards / secondary buttons
    static final int SURFACE_PRESSED = 0xFF3A3A3A;
    static final int FIELD = 0xFF202020;         // input fields
    static final int ON_PRIMARY = 0xFFF0F0F0;    // text on blue (near-white)
    static final int TEXT = 0xFFEDEDED;          // headings
    static final int TEXT_BODY = 0xFFDADADA;     // body
    static final int TEXT_MUTED = 0xFFBEC6DC;    // captions / secondary
    static final int HAIRLINE = 0x22FFFFFF;

    // Illustration palette (icons / decorative)
    static final int SLATE = 0xFFB9CAD2, TEAL = 0xFF6BCEBB, LIME = 0xFFA3CE71,
            LEMON = 0xFFFCD872, ORANGE = 0xFFF7923B, TOMATO = 0xFFFB724B, PINK = 0xFFEC7EBD;

    static final int TOP_INSET_DP = 72;  // reserve for Portal's top system overlay
    static final int MAX_W_DP = 760;     // centred content column
    static final int MAX_W_WIDE_DP = 1160; // wide two-column layouts

    // ---- Inter typefaces (bundled in assets; graceful fallback) ----
    private static Typeface sRegular, sMedium, sBold;

    static Typeface regular(Context c) {
        if (sRegular == null) sRegular = load(c, "fonts/inter_regular.ttf", "sans-serif");
        return sRegular;
    }

    static Typeface medium(Context c) {
        if (sMedium == null) sMedium = load(c, "fonts/inter_medium.ttf", "sans-serif-medium");
        return sMedium;
    }

    static Typeface bold(Context c) {
        if (sBold == null) sBold = load(c, "fonts/inter_bold.ttf", "sans-serif-medium");
        return sBold;
    }

    private static Typeface load(Context c, String asset, String fallbackFamily) {
        try {
            return Typeface.createFromAsset(c.getAssets(), asset);
        } catch (Exception e) {
            return Typeface.create(fallbackFamily, Typeface.NORMAL);
        }
    }

    private static Typeface sClock;

    /**
     * The Portal system clock font — Meta's "Optimistic" (Display, Light), loaded
     * straight from /system/fonts (world-readable) so our clock matches the native
     * home/screensaver clock exactly. Falls back to the bundled regular face on
     * non-Portal devices.
     */
    static Typeface clockFace(Context c) {
        if (sClock == null) {
            sClock = loadFile("/system/fonts/Optimistic_Display_A_Lt.ttf", c);
        }
        return sClock;
    }

    private static Typeface loadFile(String path, Context c) {
        try {
            java.io.File f = new java.io.File(path);
            if (f.exists()) {
                return Typeface.createFromFile(f);
            }
        } catch (Exception ignored) {
        }
        return regular(c);
    }

    static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics()));
    }

    // ---- Text styles (sp sizes per Portal type scale) ----
    static TextView title(Context c, String t)   { return text(c, t, 30, bold(c), TEXT); }
    static TextView heading(Context c, String t)  { return text(c, t, 24, bold(c), TEXT); }
    static TextView body(Context c, String t) {
        TextView tv = text(c, t, 18, medium(c), TEXT_BODY);
        tv.setLineSpacing(dp(c, 5), 1f);
        return tv;
    }
    static TextView caption(Context c, String t) { return text(c, t, 16, medium(c), TEXT_MUTED); }
    static TextView sectionLabel(Context c, String t) {
        TextView tv = text(c, t.toUpperCase(), 14, bold(c), TEXT_MUTED);
        tv.setLetterSpacing(0.14f);
        return tv;
    }

    private static TextView text(Context c, String t, int sp, Typeface tf, int color) {
        TextView tv = new TextView(c);
        tv.setText(t);
        tv.setTextColor(color);
        tv.setTypeface(tf);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        return tv;
    }

    // ---- Buttons ----
    /** Filled Portal-blue primary action (96dp tall). */
    static Button primary(Context c, String t, final Runnable onClick) {
        return filledButton(c, t, onClick, ON_PRIMARY, BLUE, BLUE_PRESSED, 96);
    }

    /** Neutral surface button (64dp). */
    static Button secondary(Context c, String t, final Runnable onClick) {
        return filledButton(c, t, onClick, TEXT, SURFACE, SURFACE_PRESSED, 64);
    }

    /** Outlined neutral button (64dp) — secondary actions that sit on a card. */
    static Button outline(Context c, String t, final Runnable onClick) {
        Button b = baseButton(c, t, onClick, TEXT, 64);
        GradientDrawable g = roundRect(0x00000000, dp(c, 16));
        g.setStroke(dp(c, 2), 0x40FFFFFF);
        GradientDrawable p = roundRect(SURFACE_PRESSED, dp(c, 16));
        p.setStroke(dp(c, 2), 0x40FFFFFF);
        b.setBackground(pressable(p, g));
        return b;
    }

    /** Outlined red destructive button (64dp). */
    static Button destructive(Context c, String t, final Runnable onClick) {
        Button b = baseButton(c, t, onClick, RED, 64);
        GradientDrawable g = roundRect(0x00000000, dp(c, 16));
        g.setStroke(dp(c, 2), RED);
        GradientDrawable p = roundRect(0x22FA484E, dp(c, 16));
        p.setStroke(dp(c, 2), RED);
        b.setBackground(pressable(p, g));
        return b;
    }

    private static Button filledButton(Context c, String t, Runnable onClick,
                                       int textColor, int fill, int pressed, int minDp) {
        Button b = baseButton(c, t, onClick, textColor, minDp);
        b.setBackground(pressable(roundRect(pressed, dp(c, 16)), roundRect(fill, dp(c, 16))));
        return b;
    }

    private static Button baseButton(Context c, String t, final Runnable onClick,
                                     int textColor, int minDp) {
        Button b = new Button(c);
        b.setText(t);
        b.setAllCaps(false);
        b.setTypeface(medium(c));
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        b.setTextColor(textColor);
        b.setMinHeight(dp(c, minDp));
        b.setMinimumHeight(dp(c, minDp));
        b.setStateListAnimator(null);
        b.setContentDescription(t);
        int padH = dp(c, 24);
        b.setPadding(padH, 0, padH, 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(c, 16); // ≥16dp between adjacent hit targets
        b.setLayoutParams(lp);
        if (onClick != null) {
            b.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { onClick.run(); }
            });
        }
        return b;
    }

    // ---- Containers / fields ----
    /** A rounded surface card (vertical LinearLayout) for grouping content. */
    static LinearLayout card(Context c) {
        LinearLayout ll = new LinearLayout(c);
        ll.setOrientation(LinearLayout.VERTICAL);
        ll.setBackground(roundRect(SURFACE, dp(c, 20)));
        int pad = dp(c, 28);
        ll.setPadding(pad, dp(c, 22), pad, dp(c, 22));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(c, 16);
        ll.setLayoutParams(lp);
        return ll;
    }

    static EditText field(Context c, String hint) {
        EditText e = new EditText(c);
        e.setHint(hint);
        e.setHintTextColor(TEXT_MUTED);
        e.setTextColor(TEXT);
        e.setTypeface(regular(c));
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        e.setBackground(roundRect(FIELD, dp(c, 14)));
        int padH = dp(c, 18), padV = dp(c, 16);
        e.setPadding(padH, padV, padH, padV);
        e.setMinHeight(dp(c, 64));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(c, 16);
        e.setLayoutParams(lp);
        return e;
    }

    /**
     * Build the standard screen scaffold on {@code root}: dark background, a vertical
     * scroll, a centred max-width column with a reserved top inset and page side
     * margins. Returns the column to add content to.
     */
    static LinearLayout screen(Activity a, FrameLayout root) {
        return screen(a, root, MAX_W_DP);
    }

    static LinearLayout screen(Activity a, FrameLayout root, int maxWdp) {
        root.setBackgroundColor(BG);
        ScrollView sv = new ScrollView(a);
        sv.setFillViewport(true);
        sv.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        int side = dp(a, 40);
        col.setPadding(side, dp(a, TOP_INSET_DP), side, dp(a, 40));

        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(
                Math.min(dp(a, maxWdp),
                        a.getResources().getDisplayMetrics().widthPixels),
                ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.gravity = Gravity.CENTER_HORIZONTAL;
        col.setLayoutParams(clp);

        sv.addView(col);
        root.addView(sv);
        return col;
    }

    /**
     * Split {@code parent} into two equal-weight vertical columns with a gap, for
     * side-by-side panes. Returns {left, right}; add cards to either.
     */
    static LinearLayout[] twoColumns(Context c, LinearLayout parent) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = dp(c, 8);
        row.setLayoutParams(rlp);

        LinearLayout left = new LinearLayout(c);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout right = new LinearLayout(c);
        right.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        rp.leftMargin = dp(c, 24);
        right.setLayoutParams(rp);

        row.addView(left);
        row.addView(right);
        parent.addView(row);
        return new LinearLayout[]{left, right};
    }

    /**
     * A settings row inside a {@link #card}: label (left) → value (blue) + chevron,
     * full-width, pressable. The value TextView is stored as the row's tag so it can
     * be updated in place via {@link #setRowValue}.
     */
    static View row(Context c, String label, String value, final Runnable onClick) {
        LinearLayout r = new LinearLayout(c);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setMinimumHeight(dp(c, 64));
        r.setPadding(dp(c, 4), dp(c, 8), dp(c, 4), dp(c, 8));
        r.setClickable(true);
        r.setBackground(pressable(
                roundRect(SURFACE_PRESSED, dp(c, 12)), roundRect(0x00000000, dp(c, 12))));
        r.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView lab = text(c, label, 18, medium(c), TEXT);
        lab.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        r.addView(lab);

        TextView val = text(c, value, 18, medium(c), BLUE);
        r.addView(val);

        TextView chev = text(c, "  ›", 18, medium(c), TEXT_MUTED);
        r.addView(chev);

        r.setTag(val);
        if (onClick != null) {
            r.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { onClick.run(); }
            });
        }
        return r;
    }

    /** Update the value shown by a {@link #row} in place. */
    static void setRowValue(View row, String value) {
        Object tag = row.getTag();
        if (tag instanceof TextView) {
            ((TextView) tag).setText(value);
        }
    }

    /** A thin divider between rows inside a card. */
    static View hairline(Context c) {
        View v = new View(c);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(c, 1)));
        lp.topMargin = dp(c, 4);
        lp.bottomMargin = dp(c, 4);
        v.setLayoutParams(lp);
        v.setBackgroundColor(HAIRLINE);
        return v;
    }

    /** A filled crescent-moon bitmap (for the night weather glyph), {@code color}-tinted. */
    static android.graphics.Bitmap crescent(int sizePx, int color) {
        android.graphics.Bitmap b = android.graphics.Bitmap.createBitmap(
                sizePx, sizePx, android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas cv = new android.graphics.Canvas(b);
        android.graphics.Paint p =
                new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        p.setColor(color);
        float r = sizePx / 2f;
        cv.drawCircle(r, r, r, p);
        // Carve an offset disc to leave a crescent.
        p.setXfermode(new android.graphics.PorterDuffXfermode(
                android.graphics.PorterDuff.Mode.CLEAR));
        cv.drawCircle(r + r * 0.52f, r - r * 0.16f, r * 0.95f, p);
        p.setXfermode(null);
        return b;
    }

    // ---- drawable helpers ----
    static GradientDrawable roundRect(int color, int radiusPx) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radiusPx);
        return g;
    }

    private static StateListDrawable pressable(GradientDrawable pressed, GradientDrawable normal) {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_pressed}, pressed);
        s.addState(new int[]{}, normal);
        return s;
    }
}
