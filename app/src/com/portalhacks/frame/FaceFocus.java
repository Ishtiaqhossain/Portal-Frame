package com.portalhacks.frame;

import android.graphics.Bitmap;
import android.graphics.PointF;
import android.media.FaceDetector;
import android.util.Log;

/**
 * On-device face detection used to make the cinematic Ken Burns motion drift
 * toward people instead of a random point — "the frame keeps faces in view".
 *
 * Uses Android's built-in {@link android.media.FaceDetector} (classic, since API
 * 1) so it works on Portal with NO Google Mobile Services and NO extra
 * dependencies. Detection runs on a small RGB_565 copy (it requires that config
 * and an even width) so it's cheap (~tens of ms) at slide-change time.
 */
final class FaceFocus {

    private static final String TAG = "PortalFrame";
    private static final int MAX_DIM = 320; // downscale target for detection
    private static final int MAX_FACES = 5;

    private FaceFocus() {}

    /**
     * @return {fx, fy} the normalised [0,1] centre of the most prominent face, or
     *         null when no face is found (or detection fails).
     */
    static float[] find(Bitmap src) {
        if (src == null || src.getWidth() < 2 || src.getHeight() < 2) {
            return null;
        }
        Bitmap scaled = null;
        Bitmap rgb565 = null;
        try {
            float scale = Math.min(1f, MAX_DIM / (float) Math.max(src.getWidth(), src.getHeight()));
            int w = Math.max(2, Math.round(src.getWidth() * scale));
            int h = Math.max(2, Math.round(src.getHeight() * scale));
            if ((w & 1) == 1) {
                w--; // FaceDetector requires an even width
            }
            scaled = Bitmap.createScaledBitmap(src, w, h, true);
            // FaceDetector requires RGB_565.
            rgb565 = scaled.getConfig() == Bitmap.Config.RGB_565
                    ? scaled
                    : scaled.copy(Bitmap.Config.RGB_565, false);
            if (rgb565 == null) {
                return null;
            }

            FaceDetector.Face[] faces = new FaceDetector.Face[MAX_FACES];
            int n = new FaceDetector(w, h, MAX_FACES).findFaces(rgb565, faces);
            if (n <= 0) {
                return null;
            }
            // Most prominent = widest eye distance.
            FaceDetector.Face best = null;
            float bestEyes = -1f;
            for (int i = 0; i < n; i++) {
                float d = faces[i].eyesDistance();
                if (d > bestEyes) {
                    bestEyes = d;
                    best = faces[i];
                }
            }
            PointF mid = new PointF();
            best.getMidPoint(mid);
            float fx = clamp01(mid.x / w);
            float fy = clamp01(mid.y / h);
            Log.i(TAG, "FaceFocus: " + n + " face(s), focus=" + fx + "," + fy);
            return new float[]{fx, fy};
        } catch (Throwable t) {
            Log.w(TAG, "FaceFocus failed: " + t);
            return null;
        } finally {
            if (rgb565 != null && rgb565 != scaled) {
                rgb565.recycle();
            }
            if (scaled != null && scaled != src) {
                scaled.recycle();
            }
        }
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }
}
