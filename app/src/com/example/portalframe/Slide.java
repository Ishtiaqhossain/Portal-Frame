package com.example.portalframe;

/**
 * One slideshow item: an image id (asset path or URL) plus an optional caption
 * (e.g. the photo's date / location) shown in the lower-right corner.
 */
public class Slide {
    public final String id;
    public final String caption; // may be null

    public Slide(String id, String caption) {
        this.id = id;
        this.caption = caption;
    }
}
