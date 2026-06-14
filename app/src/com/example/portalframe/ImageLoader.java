package com.example.portalframe;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.LruCache;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Loads bitmaps off the main thread for both bundled assets ("slides/01.png")
 * and remote URLs ("https://..."). Remote bytes are cached on disk (so later
 * slideshow loops are instant and survive reboots) and decoded downsampled to
 * the screen size. A small in-memory LRU caches recently shown bitmaps.
 */
public class ImageLoader {

    private static final String TAG = "PortalFrame";
    static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    public interface Callback {
        void onLoaded(Bitmap bitmap);
    }

    private final Context ctx;
    private final ExecutorService io = Executors.newFixedThreadPool(2);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final LruCache<String, Bitmap> mem;
    private final File cacheDir;

    public ImageLoader(Context context) {
        ctx = context.getApplicationContext();
        int maxKb = (int) (Runtime.getRuntime().maxMemory() / 1024);
        mem = new LruCache<String, Bitmap>(maxKb / 6) {
            @Override
            protected int sizeOf(String key, Bitmap value) {
                return value.getByteCount() / 1024;
            }
        };
        cacheDir = new File(ctx.getCacheDir(), "photos");
        cacheDir.mkdirs();
    }

    /** Shared background pool — reused for album fetches too. */
    public ExecutorService executor() {
        return io;
    }

    public void load(final String id, final int reqW, final int reqH, final Callback cb) {
        Bitmap cached = mem.get(id);
        if (cached != null) {
            cb.onLoaded(cached);
            return;
        }
        io.execute(new Runnable() {
            @Override
            public void run() {
                final Bitmap bmp = loadSync(id, reqW, reqH);
                if (bmp != null) {
                    mem.put(id, bmp);
                }
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        cb.onLoaded(bmp);
                    }
                });
            }
        });
    }

    /** Warm the cache for an id without a UI callback. */
    public void prefetch(final String id, final int reqW, final int reqH) {
        if (mem.get(id) != null) {
            return;
        }
        io.execute(new Runnable() {
            @Override
            public void run() {
                Bitmap b = loadSync(id, reqW, reqH);
                if (b != null) {
                    mem.put(id, b);
                }
            }
        });
    }

    private Bitmap loadSync(String id, int reqW, int reqH) {
        try {
            Bitmap raw;
            if (id.startsWith("http")) {
                File f = new File(cacheDir, md5(id) + ".img");
                if (!f.exists() || f.length() == 0) {
                    if (!download(id, f)) {
                        return null;
                    }
                }
                raw = decodeFile(f.getAbsolutePath(), reqW, reqH);
            } else {
                raw = decodeAsset(id, reqW, reqH);
            }
            return composeFill(raw, reqW, reqH);
        } catch (Exception e) {
            Log.e(TAG, "load failed " + id, e);
            return null;
        }
    }

    /**
     * Compose {@code src} into a screen-sized frame: the photo is fit-centered
     * (whole photo visible, never zoomed/cropped) over a blurred, center-cropped
     * copy of itself that fills the letterbox bars and matches the photo's edge
     * colors. For photos that already match the screen aspect the foreground
     * covers everything, so the blur is invisible.
     */
    private static Bitmap composeFill(Bitmap src, int screenW, int screenH) {
        if (src == null || screenW <= 0 || screenH <= 0) {
            return src;
        }
        Bitmap out = Bitmap.createBitmap(screenW, screenH, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);

        // Blurred background: center-crop into a tiny bitmap, blur, draw upscaled.
        int bw = Math.max(1, screenW / 16);
        int bh = Math.max(1, screenH / 16);
        Bitmap small = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
        Canvas sc = new Canvas(small);
        sc.drawBitmap(src, centerCropRect(src.getWidth(), src.getHeight(), bw, bh),
                new Rect(0, 0, bw, bh), p);
        boxBlur(small, 3, 2);
        c.drawBitmap(small, new Rect(0, 0, bw, bh), new Rect(0, 0, screenW, screenH), p);
        small.recycle();
        c.drawColor(Color.argb(70, 0, 0, 0)); // gentle scrim behind the photo

        // Sharp foreground: whole photo, fit-centered (no crop).
        c.drawBitmap(src, null, fitCenterRect(src.getWidth(), src.getHeight(), screenW, screenH), p);

        if (src != out) {
            src.recycle();
        }
        return out;
    }

    private static Rect centerCropRect(int sw, int sh, int dw, int dh) {
        float srcA = sw / (float) sh;
        float dstA = dw / (float) dh;
        int cw, ch, cx, cy;
        if (srcA > dstA) {
            ch = sh;
            cw = Math.round(sh * dstA);
            cx = (sw - cw) / 2;
            cy = 0;
        } else {
            cw = sw;
            ch = Math.round(sw / dstA);
            cx = 0;
            cy = (sh - ch) / 2;
        }
        return new Rect(cx, cy, cx + cw, cy + ch);
    }

    private static RectF fitCenterRect(int sw, int sh, int dw, int dh) {
        float scale = Math.min(dw / (float) sw, dh / (float) sh);
        float w = sw * scale;
        float h = sh * scale;
        float left = (dw - w) / 2f;
        float top = (dh - h) / 2f;
        return new RectF(left, top, left + w, top + h);
    }

    private static void boxBlur(Bitmap bmp, int radius, int passes) {
        int w = bmp.getWidth();
        int h = bmp.getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        int[] a = new int[w * h];
        int[] b = new int[w * h];
        bmp.getPixels(a, 0, w, 0, 0, w, h);
        for (int p = 0; p < passes; p++) {
            boxH(a, b, w, h, radius);
            boxV(b, a, w, h, radius);
        }
        bmp.setPixels(a, 0, w, 0, 0, w, h);
    }

    private static void boxH(int[] in, int[] out, int w, int h, int r) {
        for (int y = 0; y < h; y++) {
            int row = y * w;
            for (int x = 0; x < w; x++) {
                int aa = 0, rr = 0, gg = 0, bb = 0, cnt = 0;
                int x0 = Math.max(0, x - r), x1 = Math.min(w - 1, x + r);
                for (int xx = x0; xx <= x1; xx++) {
                    int c = in[row + xx];
                    aa += (c >>> 24) & 0xff;
                    rr += (c >> 16) & 0xff;
                    gg += (c >> 8) & 0xff;
                    bb += c & 0xff;
                    cnt++;
                }
                out[row + x] = ((aa / cnt) << 24) | ((rr / cnt) << 16) | ((gg / cnt) << 8) | (bb / cnt);
            }
        }
    }

    private static void boxV(int[] in, int[] out, int w, int h, int r) {
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                int aa = 0, rr = 0, gg = 0, bb = 0, cnt = 0;
                int y0 = Math.max(0, y - r), y1 = Math.min(h - 1, y + r);
                for (int yy = y0; yy <= y1; yy++) {
                    int c = in[yy * w + x];
                    aa += (c >>> 24) & 0xff;
                    rr += (c >> 16) & 0xff;
                    gg += (c >> 8) & 0xff;
                    bb += c & 0xff;
                    cnt++;
                }
                out[y * w + x] = ((aa / cnt) << 24) | ((rr / cnt) << 16) | ((gg / cnt) << 8) | (bb / cnt);
            }
        }
    }

    private boolean download(String urlStr, File dest) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(urlStr).openConnection();
            c.setInstanceFollowRedirects(true);
            c.setConnectTimeout(15000);
            c.setReadTimeout(20000);
            c.setRequestProperty("User-Agent", UA);
            int code = c.getResponseCode();
            if (code != 200) {
                Log.w(TAG, "image http " + code + " for " + urlStr);
                return false;
            }
            File tmp = new File(dest.getAbsolutePath() + ".tmp");
            InputStream in = new BufferedInputStream(c.getInputStream());
            OutputStream out = new FileOutputStream(tmp);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            out.flush();
            out.close();
            in.close();
            return tmp.renameTo(dest);
        } catch (Exception e) {
            Log.e(TAG, "download failed " + urlStr, e);
            return false;
        } finally {
            if (c != null) {
                c.disconnect();
            }
        }
    }

    private Bitmap decodeFile(String path, int reqW, int reqH) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, o);
        o.inSampleSize = sampleSize(o.outWidth, o.outHeight, reqW, reqH);
        o.inJustDecodeBounds = false;
        return BitmapFactory.decodeFile(path, o);
    }

    private Bitmap decodeAsset(String assetPath, int reqW, int reqH) throws Exception {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        InputStream in = ctx.getAssets().open(assetPath);
        BitmapFactory.decodeStream(in, null, o);
        in.close();
        o.inSampleSize = sampleSize(o.outWidth, o.outHeight, reqW, reqH);
        o.inJustDecodeBounds = false;
        in = ctx.getAssets().open(assetPath);
        Bitmap b = BitmapFactory.decodeStream(in, null, o);
        in.close();
        return b;
    }

    private int sampleSize(int w, int h, int reqW, int reqH) {
        if (reqW <= 0 || reqH <= 0 || w <= 0 || h <= 0) {
            return 1;
        }
        int s = 1;
        while (w / (s * 2) >= reqW && h / (s * 2) >= reqH) {
            s *= 2;
        }
        return s;
    }

    private static String md5(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] d = md.digest(s.getBytes("UTF-8"));
            return String.format("%032x", new BigInteger(1, d));
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }
}
