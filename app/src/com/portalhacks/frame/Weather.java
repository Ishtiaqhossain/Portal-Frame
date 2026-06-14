package com.portalhacks.frame;

import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Tiny dependency-free current-weather lookup for the clock overlay. No API key:
 * approximate location comes from the IP (GeoJS) and the reading from Open-Meteo.
 * Both endpoints are HTTPS, so no cleartext-traffic manifest change is needed.
 * Best-effort — returns null on any failure and the clock simply shows no weather.
 */
final class Weather {

    private static final String TAG = "PortalFrame";

    private Weather() {}

    /** A current reading: a weather emoji plus a rounded temperature in degrees. */
    static final class Now {
        final String emoji;
        final int temp;
        final boolean moon; // clear/mainly-clear at night → draw a blue crescent

        Now(String emoji, int temp, boolean moon) {
            this.emoji = emoji;
            this.temp = temp;
            this.moon = moon;
        }

        /** e.g. "☀️ 72°" */
        String label() {
            return emoji + " " + temp + "°";
        }
    }

    static Now fetch(boolean fahrenheit) {
        try {
            JSONObject geo = new JSONObject(httpGet("https://get.geojs.io/v1/ip/geo.json"));
            String lat = geo.optString("latitude", "");
            String lon = geo.optString("longitude", "");
            if (lat.isEmpty() || lon.isEmpty()) {
                return null;
            }
            String url = "https://api.open-meteo.com/v1/forecast?latitude=" + lat
                    + "&longitude=" + lon
                    + "&current=temperature_2m,weather_code,is_day"
                    + "&temperature_unit=" + (fahrenheit ? "fahrenheit" : "celsius");
            JSONObject cur = new JSONObject(httpGet(url)).getJSONObject("current");
            double t = cur.getDouble("temperature_2m");
            int code = cur.optInt("weather_code", 0);
            boolean day = cur.optInt("is_day", 1) == 1;
            boolean moon = !day && (code == 0 || code == 1); // clear / mainly-clear night
            return new Now(emojiFor(code, day), (int) Math.round(t), moon);
        } catch (Exception e) {
            Log.w(TAG, "weather fetch failed", e);
            return null;
        }
    }

    /** Map a WMO weather code (Open-Meteo) to a single emoji, day/night aware. */
    private static String emojiFor(int c, boolean day) {
        if (c == 0) return day ? "☀️" : "🌙";        // clear sky → moon at night
        if (c == 1) return day ? "🌤️" : "🌙";       // mainly clear
        if (c == 2) return day ? "⛅" : "☁️";          // partly cloudy
        if (c == 3) return "☁️";                    // overcast
        if (c == 45 || c == 48) return "🌫️";  // fog
        if (c >= 51 && c <= 57) return "🌦️";  // drizzle
        if (c >= 61 && c <= 67) return "🌧️";  // rain
        if (c >= 71 && c <= 77) return "❄️";        // snow
        if (c >= 80 && c <= 82) return "🌦️";  // rain showers
        if (c == 85 || c == 86) return "🌨️";  // snow showers
        if (c >= 95) return "⛈️";                   // thunderstorm
        return "🌡️";                          // fallback: thermometer
    }

    private static String httpGet(String urlStr) throws Exception {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(urlStr).openConnection();
            c.setInstanceFollowRedirects(true);
            c.setConnectTimeout(10000);
            c.setReadTimeout(12000);
            c.setRequestProperty("User-Agent", ImageLoader.UA);
            InputStream in = new BufferedInputStream(c.getInputStream());
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
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
