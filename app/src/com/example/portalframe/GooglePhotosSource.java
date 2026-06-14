package com.example.portalframe;

import android.util.Log;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;
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
    // A real photo carries a filesize element after its dimensions...
    private static final Pattern FILESIZE = Pattern.compile("\\[null,null,1\\],\\[\\d+\\]\\]");
    // ...or an EXIF block (camera make/model etc.).
    private static final Pattern EXIF = Pattern.compile("\\[\\d{3,4},\\d{3,4},1,null,\\[");
    // Capture time (ms) , "mediaId" , tzOffset(ms)
    private static final Pattern DATE = Pattern.compile(
            ",(\\d{13}),\"[A-Za-z0-9_\\-]+\",(-?\\d{5,9}),");
    private static final String VIDEO_MARKER = "video-downloads.googleusercontent.com";
    // Protobuf field keys present on every item (owner / common metadata). A
    // VIDEO item additionally carries a video-specific key (e.g. 76647426 or
    // 117194011) that photos never have — this structural signal is reliable
    // even when the lazy "video-downloads" URL is absent from a given response.
    private static final Pattern META_KEY = Pattern.compile("\"(\\d{8,9})\":");
    private static final java.util.Set<String> PHOTO_KEYS = new java.util.HashSet<>(
            java.util.Arrays.asList("101428965", "525000002"));

    private static final Pattern SHARE_URL = Pattern.compile(
            "(https://photos\\.google\\.com/share/[A-Za-z0-9_\\-]+\\?key=[A-Za-z0-9_\\-]+)");

    public static List<Slide> fetch(String shareUrl) throws Exception {
        String html = httpGet(shareUrl);
        List<Slide> slides = parse(html);
        if (slides.isEmpty()) {
            Matcher sm = SHARE_URL.matcher(html);
            if (sm.find()) {
                String longUrl = sm.group(1).replace("\\u003d", "=").replace("\\/", "/");
                Log.i(TAG, "following embedded share url: " + longUrl);
                slides = parse(httpGet(longUrl));
            }
        }
        Log.i(TAG, "Google Photos album: " + slides.size() + " photos");
        return slides;
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
            out.add(new Slide(base + "=w" + IMG_WIDTH, caption(item)));
        }
        if (videos > 0) {
            Log.i(TAG, "skipped " + videos + " video(s)");
        }
        return out;
    }

    /**
     * Treat a media item as a video if ANY signal says so:
     *  - a video-specific protobuf key (most reliable, always present), or
     *  - a video-downloads URL, or
     *  - it carries neither a still filesize nor camera EXIF (transcoded clip).
     */
    private static boolean isVideo(String item) {
        Matcher km = META_KEY.matcher(item);
        while (km.find()) {
            if (!PHOTO_KEYS.contains(km.group(1))) {
                return true; // a metadata key photos never have
            }
        }
        if (item.contains(VIDEO_MARKER)) {
            return true;
        }
        boolean hasFilesize = FILESIZE.matcher(item).find();
        boolean hasExif = EXIF.matcher(item).find();
        return !hasFilesize && !hasExif;
    }

    /** Caption from the item's capture timestamp (location data isn't present). */
    private static String caption(String item) {
        Matcher dm = DATE.matcher(item);
        if (dm.find()) {
            try {
                long t = Long.parseLong(dm.group(1));
                long tz = Long.parseLong(dm.group(2));
                SimpleDateFormat f = new SimpleDateFormat("MMM d, yyyy", Locale.US);
                f.setTimeZone(TimeZone.getTimeZone("UTC"));
                return f.format(new Date(t + tz));
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }

    private static String httpGet(String urlStr) throws Exception {
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
            while ((n = in.read(buf)) != -1) {
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
