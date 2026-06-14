package com.portalhacks.frame

import android.content.Intent
import android.service.dreams.DreamService
import android.util.Log

/**
 * Thin trampoline screensaver.
 *
 * Portal's ambient/dream manager kills an interactive in-dream UI within ~1s
 * (DREAM_FINISHED), which made a self-hosted slideshow flicker in and out. So
 * instead of rendering inside the dream, we use the dream purely as the
 * "device went idle" trigger: launch the full-screen interactive
 * [SlideshowComposeActivity] (which renders and handles swipe/tap reliably) and exit
 * the dream. The Activity keeps the screen on, so nothing loops; a tap finishes
 * the Activity -> device idles -> dream fires again -> relaunch.
 */
class FrameDreamService : DreamService() {

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        try {
            val i = Intent(this, SlideshowComposeActivity::class.java)
            i.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK
                    or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    or Intent.FLAG_ACTIVITY_NO_ANIMATION
            )
            startActivity(i)
            Log.i(TAG, "Dream launched SlideshowComposeActivity")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch slideshow from dream", e)
        }
        finish() // hand off to the Activity
    }

    private companion object {
        private const val TAG = "PortalFrame"
    }
}
