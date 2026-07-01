package com.portalhacks.frame

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restarts the screensaver guard after a reboot, if the user opted in. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED) {
            ScreensaverGuardService.startIfEnabled(ctx)
            // Bring the LAN photo-drop server up at boot so the frame is always reachable.
            DropServerService.start(ctx)
        }
    }
}
