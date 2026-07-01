package com.portalhacks.frame

import android.content.Context
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Process-wide status of the LAN drop server, so the Settings screen can build the
 * scannable URL/QR without holding a reference to the server itself (which lives in
 * [DropServerService], a separate component). [LocalDropServer] publishes its bound
 * port here; everyone else reads it.
 */
object DropServerStatus {

    /** The port the drop server is bound to, or -1 when it isn't running. */
    @Volatile
    var port: Int = -1

    /** This device's site-local IPv4 on the LAN (e.g. 192.168.x.x), or null if offline. */
    fun lanIp(): String? = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }
            ?.hostAddress
    } catch (e: Exception) {
        null
    }

    /**
     * A scannable `http://ip:port/?k=token` URL a phone can open to push photos, or null
     * if the server isn't reachable yet (not bound, or no LAN address). The token gates
     * access so only a phone that scanned the on-screen QR can post.
     */
    fun url(ctx: Context): String? {
        val p = port
        if (p <= 0) return null
        val ip = lanIp() ?: return null
        return "http://$ip:$p/?k=${DropAuth.token(ctx)}"
    }
}
