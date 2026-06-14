package com.portalhacks.frame;

import android.graphics.Bitmap;
import android.graphics.ColorMatrix;

/**
 * On-device auto-enhance: analyses each photo's luminance histogram and builds a
 * gentle auto-levels (contrast stretch) + vibrance {@link ColorMatrix} so dull or
 * flat photos pop, without the over-processed look. Pure Java, no dependencies —
 * the kind of "AI auto-enhance" a frame can do that a dumb slideshow can't.
 */
final class PhotoEnhance {

    private static final int GRID = 48;

    private PhotoEnhance() {}

    /** A flattering ColorMatrix for this photo, or null if it's already well-exposed. */
    static ColorMatrix compute(Bitmap src) {
        if (src == null || src.getWidth() < 2 || src.getHeight() < 2) {
            return null;
        }
        Bitmap s = null;
        try {
            s = Bitmap.createScaledBitmap(src, GRID, GRID, true);
            int[] px = new int[GRID * GRID];
            s.getPixels(px, 0, GRID, 0, 0, GRID, GRID);
            int[] hist = new int[256];
            for (int p : px) {
                int r = (p >> 16) & 0xff, g = (p >> 8) & 0xff, b = p & 0xff;
                int y = (r * 77 + g * 150 + b * 29) >> 8; // luma 0..255
                hist[y]++;
            }
            int total = px.length;
            int lo = percentile(hist, total, 0.01f);
            int hi = percentile(hist, total, 0.99f);
            if (hi - lo < 8) {
                return null; // degenerate
            }
            // Contrast stretch lo..hi -> 0..255, but hold back so it stays natural.
            float span = hi - lo;
            float scale = 255f / span;
            scale = clamp(1f + (scale - 1f) * 0.7f, 1f, 1.8f); // ease + cap
            float translate = -lo * scale;
            // Skip if the photo is already near full-range (little to gain).
            if (scale < 1.04f && lo < 6 && hi > 249) {
                return null;
            }
            ColorMatrix levels = new ColorMatrix(new float[]{
                    scale, 0, 0, 0, translate,
                    0, scale, 0, 0, translate,
                    0, 0, scale, 0, translate,
                    0, 0, 0, 1, 0,
            });
            ColorMatrix vibrance = new ColorMatrix();
            vibrance.setSaturation(1.12f); // subtle pop
            ColorMatrix out = new ColorMatrix();
            out.postConcat(levels);
            out.postConcat(vibrance);
            return out;
        } catch (Throwable t) {
            return null;
        } finally {
            if (s != null && s != src) {
                s.recycle();
            }
        }
    }

    private static int percentile(int[] hist, int total, float pct) {
        int target = (int) (total * pct);
        int acc = 0;
        for (int i = 0; i < hist.length; i++) {
            acc += hist[i];
            if (acc >= target) {
                return i;
            }
        }
        return hist.length - 1;
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
