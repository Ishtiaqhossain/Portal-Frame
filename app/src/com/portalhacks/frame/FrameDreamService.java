package com.portalhacks.frame;

import android.content.Intent;
import android.service.dreams.DreamService;
import android.util.Log;

/**
 * Thin trampoline screensaver.
 *
 * Portal's ambient/dream manager kills an interactive in-dream UI within ~1s
 * (DREAM_FINISHED), which made a self-hosted slideshow flicker in and out. So
 * instead of rendering inside the dream, we use the dream purely as the
 * "device went idle" trigger: launch the full-screen interactive
 * {@link MainActivity} (which renders and handles swipe/tap reliably) and exit
 * the dream. The Activity keeps the screen on, so nothing loops; a tap finishes
 * the Activity -> device idles -> dream fires again -> relaunch.
 */
public class FrameDreamService extends DreamService {

    private static final String TAG = "PortalFrame";

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        try {
            Intent i = new Intent(this, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_NO_ANIMATION);
            startActivity(i);
            Log.i(TAG, "Dream launched MainActivity");
        } catch (Exception e) {
            Log.e(TAG, "Failed to launch MainActivity from dream", e);
        }
        finish(); // hand off to the Activity
    }
}
