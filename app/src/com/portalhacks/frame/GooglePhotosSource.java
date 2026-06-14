package com.portalhacks.frame;

import android.util.Log;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fetches photos from a public Google Photos *shared album* link by scraping the
 * share page (the official Library API was deprecated 2025-03-31). The page
 * embeds each media item as a JS array; we segment by media item and, per item,
 * pull the thumbnail URL, classify photo-vs-video, and read the capture date.
 *
 * Unofficial — can break if Google changes the page format. Callers should fall
 * back to bundled images when this returns empty.
 */
public class GooglePhotosSource {

    private static final String TAG = "PortalFrame";
    private static final int IMG_WIDTH = 1280;

    // Start of each media item: ["<AF1Qip mediaKey>",["https://lh3...
    private static final Pattern ITEM = Pattern.compile(
            "\\[\"AF1Qip[A-Za-z0-9_\\-]+\",\\[\"https://lh3\\.googleusercontent\\.com/");
    // First thumbnail (base url, width, height) within an item.
    private static final Pattern THUMB = Pattern.compile(
            "\"(https://lh3\\.googleusercontent\\.com/[^\"]+?)\",(\\d{2,5}),(\\d{2,5})");
    // Capture time (ms) , "mediaId" , tzOffset(ms)
    private static final Pattern DATE = Pattern.compile(
            ",(\\d{13}),\"[A-Za-z0-9_\\-]+\",(-?\\d{5,9}),");
    // The only reliable POSITIVE video signal: a transcoded download URL on this
    // host. The earlier heuristics (absence of EXIF/filesize, "video-only"
    // metadata keys) were false-positives that discarded ~95% of a large mixed
    // album as "videos" — see isVideo().
    private static final String VIDEO_MARKER = "video-downloads.googleusercontent.com";

    private static final Pattern SHARE_URL = Pattern.compile(
            "(https://photos\\.google\\.com/share/[A-Za-z0-9_\\-]+\\?key=[A-Za-z0-9_\\-]+)");

    // Album name from the share page's Open Graph title (content/property order varies).
    private static final Pattern OG_TITLE_A = Pattern.compile(
            "<meta[^>]+property=\"og:title\"[^>]+content=\"([^\"]*)\"");
    private static final Pattern OG_TITLE_B = Pattern.compile(
            "<meta[^>]+content=\"([^\"]*)\"[^>]+property=\"og:title\"");

    /** An album: its display title (may be empty) and its photos. */
    public static final class Album {
        public final String title;
        public final List<Slide> slides;

        Album(String title, List<Slide> slides) {
            this.title = title;
            this.slides = slides;
        }
    }

    public static Album fetch(String shareUrl) throws Exception {
        String html = httpGet(shareUrl);
        List<Slide> slides = parse(html);
        if (slides.isEmpty()) {
            Matcher sm = SHARE_URL.matcher(html);
            if (sm.find()) {
                String longUrl = sm.group(1).replace("\\u003d", "=").replace("\\/", "/");
                Log.i(TAG, "following embedded share url: " + longUrl);
                html = httpGet(longUrl);
                slides = parse(html);
            }
        }
        String title = parseTitle(html);
        Log.i(TAG, "Google Photos album: " + slides.size() + " photos, title='" + title + "'");
        return new Album(title, slides);
    }

    private static String parseTitle(String html) {
        Matcher m = OG_TITLE_A.matcher(html);
        if (!m.find()) {
            m = OG_TITLE_B.matcher(html);
            if (!m.find()) {
                return "";
            }
        }
        String t = m.group(1)
                .replace("&amp;", "&").replace("&#39;", "'").replace("&quot;", "\"").trim();
        // The generic site title isn't an album name.
        return t.equalsIgnoreCase("Google Photos") ? "" : t;
    }

    private static List<Slide> parse(String html) {
        List<Slide> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        List<Integer> starts = new ArrayList<>();
        Matcher im = ITEM.matcher(html);
        while (im.find()) {
            starts.add(im.start());
        }
        int videos = 0;
        for (int k = 0; k < starts.size(); k++) {
            int s = starts.get(k);
            int e = (k + 1 < starts.size()) ? starts.get(k + 1) : html.length();
            String item = html.substring(s, e);

            Matcher tm = THUMB.matcher(item);
            if (!tm.find()) {
                continue;
            }
            int w = Integer.parseInt(tm.group(2));
            int h = Integer.parseInt(tm.group(3));
            if (w < 256 || h < 256) {
                continue;
            }

            if (isVideo(item)) {
                videos++;
                continue;
            }

            String base = tm.group(1);
            int eq = base.indexOf('=');
            if (eq > 0) {
                base = base.substring(0, eq);
            }
            base = base.replace("\\/", "/");
            if (!seen.add(base)) {
                continue;
            }
            long tms = captureMillis(item);
            // Caption is derived at display time (album · relative time); keep only
            // the raw capture instant and the portrait flag here.
            out.add(new Slide(base + "=w" + IMG_WIDTH, null, tms, h > w));
        }
        if (videos > 0) {
            Log.i(TAG, "skipped " + videos + " video(s)");
        }
        return out;
    }

    /**
     * Treat a media item as a video only on the reliable positive signal — a
     * transcoded {@code video-downloads} URL. We deliberately do NOT infer "video"
     * from the absence of EXIF/filesize or from "unexpected" metadata keys: those
     * heuristics false-positived on most photos of a large mixed album and dropped
     * them. A video whose marker is lazy-loaded (absent here) just shows its still
     * poster frame, which is fine for a photo frame.
     */
    private static boolean isVideo(String item) {
        return item.contains(VIDEO_MARKER);
    }

    /**
     * Capture instant in epoch millis, shifted by the photo's timezone offset so
     * the value reads as the local wall clock when formatted in UTC. Returns
     * {@link Slide#NO_DATE} when the item carries no timestamp.
     */
    private static long captureMillis(String item) {
        Matcher dm = DATE.matcher(item);
        if (dm.find()) {
            try {
                long t = Long.parseLong(dm.group(1));
                long tz = Long.parseLong(dm.group(2));
                return t + tz;
            } catch (NumberFormatException ignored) {
            }
        }
        return Slide.NO_DATE;
    }

    // Cap how much of the share page we'll buffer: it's normally a few MB, and an
    // unbounded read of a hostile/oversized response could exhaust memory.
    private static final int MAX_HTML_BYTES = 12 * 1024 * 1024;

    private static String httpGet(String urlStr) throws Exception {
        if (urlStr == null || !urlStr.startsWith("https://")) {
            throw new java.io.IOException("refusing non-HTTPS album URL");
        }
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(urlStr).openConnection();
            c.setInstanceFollowRedirects(true);
            c.setConnectTimeout(15000);
            c.setReadTimeout(20000);
            c.setRequestProperty("User-Agent", ImageLoader.UA);
            c.setRequestProperty("Accept-Language", "en-US,en;q=0.9");
            int code = c.getResponseCode();
            Log.i(TAG, "album http " + code + " -> " + c.getURL());
            InputStream in = new BufferedInputStream(c.getInputStream());
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            int total = 0;
            while ((n = in.read(buf)) != -1) {
                total += n;
                if (total > MAX_HTML_BYTES) {
                    in.close();
                    throw new java.io.IOException("album page exceeds " + MAX_HTML_BYTES + " bytes");
                }
                bos.write(buf, 0, n);
            }
            in.close();
            return bos.toString("UTF-8");
        } finally {
            if (c != null) {
                c.disconnect();
            }
        }
    }
}
