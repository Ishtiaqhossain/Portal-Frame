package com.portalhacks.frame;

import android.graphics.Bitmap;
import android.graphics.Color;

/**
 * Extracts a single vibrant "mood" color from a photo so the frame's edges can
 * glow with it — a living, color-matched frame (think Ambilight for photos).
 *
 * Pure Java, no dependencies: downscale to a tiny grid and take a
 * saturation-weighted average, then normalise so even muted photos give a clear
 * but tasteful tint. Runs in well under a frame at slide-change time.
 */
final class AmbientColor {

    private static final int GRID = 32;

    private AmbientColor() {}

    /** An opaque, pleasantly saturated color representing the photo, or null. */
    static Integer extract(Bitmap src) {
        if (src == null || src.getWidth() < 2 || src.getHeight() < 2) {
            return null;
        }
        Bitmap s = null;
        try {
            s = Bitmap.createScaledBitmap(src, GRID, GRID, true);
            int[] px = new int[GRID * GRID];
            s.getPixels(px, 0, GRID, 0, 0, GRID, GRID);
            double rw = 0, gw = 0, bw = 0, wsum = 0;
            float[] hsv = new float[3];
            for (int p : px) {
                int r = (p >> 16) & 0xff, g = (p >> 8) & 0xff, b = p & 0xff;
                Color.RGBToHSV(r, g, b, hsv);
                // Favour colorful, mid-bright pixels; ignore near-black/near-white.
                float sat = hsv[1], val = hsv[2];
                if (val < 0.15f || val > 0.97f) {
                    continue;
                }
                double w = sat * sat * (0.4 + 0.6 * val);
                rw += r * w;
                gw += g * w;
                bw += b * w;
                wsum += w;
            }
            if (wsum < 1e-3) {
                return null; // a flat / monochrome photo — no glow
            }
            int r = (int) Math.round(rw / wsum);
            int g = (int) Math.round(gw / wsum);
            int b = (int) Math.round(bw / wsum);
            // Normalise: lift saturation a touch and cap brightness so the glow reads
            // as color without washing out.
            Color.RGBToHSV(r, g, b, hsv);
            hsv[1] = Math.min(1f, hsv[1] * 1.25f + 0.10f);
            hsv[2] = Math.min(0.85f, Math.max(0.45f, hsv[2]));
            return Color.HSVToColor(hsv);
        } catch (Throwable t) {
            return null;
        } finally {
            if (s != null && s != src) {
                s.recycle();
            }
        }
    }
}
