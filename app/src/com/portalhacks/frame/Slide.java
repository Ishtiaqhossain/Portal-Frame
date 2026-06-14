package com.portalhacks.frame;

/**
 * One slideshow item: an image id (asset path or URL), an optional caption
 * override (e.g. an "On this day" badge — normal captions are derived at display
 * time from {@link #timeMs}), the capture instant in epoch millis (timezone-
 * adjusted to the photo's local wall clock, or {@link #NO_DATE} when unknown),
 * and whether the photo is portrait (taller than wide) for side-by-side pairing.
 */
public class Slide {
    /** Sentinel for "no capture date available" (e.g. bundled sample slides). */
    public static final long NO_DATE = Long.MIN_VALUE;

    public final String id;
    public final String caption; // caption override, or null to derive from timeMs
    public final long timeMs;    // capture instant (tz-adjusted) or NO_DATE
    public final boolean portrait;

    public Slide(String id, String caption) {
        this(id, caption, NO_DATE, false);
    }

    public Slide(String id, String caption, long timeMs) {
        this(id, caption, timeMs, false);
    }

    public Slide(String id, String caption, long timeMs, boolean portrait) {
        this.id = id;
        this.caption = caption;
        this.timeMs = timeMs;
        this.portrait = portrait;
    }
}
