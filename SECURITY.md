# Security Policy

Frame is a sideloaded hobby app for the Meta Portal Go. It stores no accounts or credentials,
and its only inbound surface is an opt-in **local-network photo-drop server** (see below) used to
push photos onto the frame from a phone on the same Wi-Fi. Here's the trust model and how to
report issues.

## Reporting a vulnerability

Please open a GitHub issue, or for anything sensitive, contact the maintainer privately rather
than filing a public issue with exploit details. There's no formal SLA — this is a hobby
project — but reports are appreciated and will be looked at.

## Trust model & hardening

- **Network.** The app only talks to the photo providers' hosts (Google Photos / iCloud and their
  image CDNs), always over **HTTPS**. Cleartext traffic is disabled via
  `res/xml/network_security_config.xml`, and the album fetch (`PhotoSources` → `GooglePhotosSource`
  / `ApplePhotosSource`) and image download (`ImageLoader`) both refuse non-HTTPS URLs and cap
  response sizes to avoid memory/disk exhaustion from a hostile or oversized response.

- **Public shared albums only.** Providers read **public, link-shared** albums via their public
  share endpoints — Google Photos by scraping the share page (the Library API was deprecated
  2025-03-31), iCloud via the `sharedstreams` web API. These are unofficial and may break if the
  provider changes its format; each fails closed (falls back to the bundled sample photos). The app
  has no account access and cannot read a private library.

- **`ConfigReceiver` (exported).** This broadcast receiver lets the album be set over ADB
  (`am broadcast`) without rebuilding, so it is exported and any app on the device could send to
  it. It only writes the app's own private `SharedPreferences`, and it **validates** the album URL
  (`PhotoSources.matches`) — it persists only an empty value (clear) or a recognised Google Photos
  / iCloud shared-album link, ignoring anything else.

- **`MainActivity` (exported).** The slideshow Activity is intentionally exported so it can be
  launched for testing (`am start`) and by the screensaver trampoline. It displays photos only
  and takes no untrusted parameters.

- **Local photo-drop server ("AirDrop for Portal").** `DropServerService` runs a small embedded
  HTTP server (`LocalDropServer`, raw `ServerSocket`) so a phone on the same Wi-Fi can open the
  frame's address in a browser and push photos onto it. The deliberate trust decisions:
  - **Plaintext, LAN-only by design.** The server is HTTP, not HTTPS — the photos already
    traverse the home network and there are no credentials to protect, so the cost of a
    self-signed-cert UX (browser warnings, no trusted CA on a LAN IP) isn't worth it. This is an
    accepted trade-off for a home-network appliance, **not** an oversight; it is intentionally
    exempt from the app's HTTPS-only outbound policy because it is a server socket, which
    `network_security_config.xml` does not govern. Do not expose the frame's port beyond the LAN.
  - **Token-gated.** Every `GET`/`POST` requires a per-install secret (`DropAuth`, generated with
    `SecureRandom`, persisted in private prefs) supplied as `?k=…`. The token is shown only in the
    on-screen QR/URL in Settings, so a device that never scanned the frame is refused (**403**).
    The comparison is constant-time.
  - **Upload validation.** Posted files must pass a magic-byte image check (JPEG/PNG/WebP/GIF/HEIC);
    the whole request body is size-capped; concurrent connections are capped; request headers are
    bounded. The **client's filename is never used as a path** — the server mints its own name —
    so there is no path traversal. Kept photos live in app-private `filesDir/uploads/` and are
    bounded by count/bytes (oldest evicted).
  - **Not exported.** `DropServerService` is `android:exported="false"`; the upload-notification
    broadcast is package-scoped.

- **Permissions.** `INTERNET`, `ACCESS_NETWORK_STATE`, `CAMERA` (on-device QR scan, optional),
  `FOREGROUND_SERVICE` (the always-on drop server), `RECEIVE_BOOT_COMPLETED` (restart it after a
  reboot), and `WRITE_SECURE_SETTINGS` (granted once over ADB to keep Frame as the screensaver).
  `android:allowBackup="false"`.

## Supported versions

This is a rolling hobby project; only the latest `main` is maintained.
