# Frame

An open-source slideshow **screensaver for the Meta Portal Go** (Android 10 / API 29) that shows
your **Google Photos** or **iCloud** shared albums — or photos you **push straight from your phone**
over Wi-Fi. Set it up on-device by scanning an album's QR code (or pasting the link), and it plays
your photos whenever the Portal is idle — with a clock, captions, cinematic motion, and ambient color.

> Repo name: **PortalFrame** (`com.portalhacks.frame`). The app displays as **Frame**.

## Install it on a Portal

### ⬇️ [Download the latest APK](https://github.com/Ishtiaqhossain/Portal-Frame/releases/latest/download/Frame.apk)

That link always serves the newest signed release. Prefer to pick a version (or grab the
`.sha256`)? Browse all builds on the **[Releases page](https://github.com/Ishtiaqhossain/Portal-Frame/releases/latest)**.

Then follow the **[Install & User Guide](INSTALL.md)** (install the APK, add your album, turn it on
as the screensaver).

## Screenshots

<table>
  <tr>
    <td width="50%"><img src="docs/screenshots/01-slideshow.png" alt="Slideshow screensaver"><br><sub><b>Slideshow</b> — your photos when the Portal is idle</sub></td>
    <td width="50%"><img src="docs/screenshots/02-settings-albums.png" alt="Albums settings"><br><sub><b>Albums</b> — add several, stop or remove each</sub></td>
  </tr>
  <tr>
    <td><img src="docs/screenshots/03-add-album.png" alt="Add an album"><br><sub><b>Add an album</b> — scan a QR or paste a link</sub></td>
    <td><img src="docs/screenshots/04-empty-state.png" alt="First-run setup"><br><sub><b>Setup</b> — first run</sub></td>
  </tr>
</table>

<sub>Sample photos shown; personal photos, album names and links are scrubbed.</sub>

## Features

- Plays a **Google Photos or iCloud** shared album; new photos appear automatically.
- **Add photos from a phone** — scan Frame's on-screen QR with any phone on the same Wi‑Fi, pick
  photos from your camera roll in the browser, and they appear on the frame instantly and stay in
  the rotation. No app, no account, no cloud — the photos go straight from your phone to the Portal.
- On-device setup: **QR scan** or paste the link — no computer needed after install.
- Clock & weather, photo date captions, shuffle, adjustable timing and transitions.
- Side-by-side portraits, cinematic pan/zoom, auto-enhance, ambient color, night dimming,
  and "On This Day" memories — all toggleable.
- Touch: **swipe** to change photo, **tap** to dismiss, **long-press** to open setup.

## For developers

Kotlin app (Jetpack Compose settings UI + Android Views slideshow), built with Gradle:

```
./gradlew assembleDebug      # -> app/build/outputs/apk/debug/app-debug.apk
```

Requires JDK 17–21 and an Android SDK (`ANDROID_SDK_ROOT`, or a git-ignored `local.properties`
with `sdk.dir=…`). See **[CONTRIBUTING.md](CONTRIBUTING.md)** for details and project layout, and
**[RELEASING.md](RELEASING.md)** for cutting a signed release (a `v*` tag builds and publishes the
APK to GitHub Releases).

## License & security

[MIT](LICENSE) — third-party attributions in [NOTICE](NOTICE). See [SECURITY.md](SECURITY.md) for
the trust model and how to report issues.
