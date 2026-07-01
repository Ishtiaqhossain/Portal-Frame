package com.portalhacks.frame

import android.content.Context
import java.security.SecureRandom

/**
 * The per-install secret that gates the LAN drop server. The on-screen QR encodes this
 * token, so only a phone that actually scanned the frame can push photos — a random
 * device on the same Wi-Fi (which never saw the QR) is refused. Generated once with
 * [SecureRandom] and persisted; stable across restarts so a saved QR keeps working.
 */
object DropAuth {

    /** The token for this install, creating and persisting it on first use. */
    fun token(ctx: Context): String {
        val prefs = ctx.getSharedPreferences(ConfigReceiver.PREFS, Context.MODE_PRIVATE)
        prefs.getString(ConfigReceiver.KEY_DROP_TOKEN, null)?.let { if (it.isNotEmpty()) return it }
        val t = generate()
        prefs.edit().putString(ConfigReceiver.KEY_DROP_TOKEN, t).apply()
        return t
    }

    private fun generate(): String {
        val bytes = ByteArray(12)
        SecureRandom().nextBytes(bytes)
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) sb.append(String.format("%02x", b.toInt() and 0xff))
        return sb.toString()
    }

    /** Constant-time equality so the token can't be recovered by response timing. */
    fun matches(expected: String, given: String?): Boolean {
        if (given == null || given.length != expected.length) return false
        var diff = 0
        for (i in expected.indices) diff = diff or (expected[i].code xor given[i].code)
        return diff == 0
    }
}
