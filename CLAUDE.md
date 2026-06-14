# CLAUDE.md

Guidance for AI assistants (and humans) working in this repo.

## What this is

**Frame** (repo: PortalFrame, app id `com.portalhacks.frame`) — an Android slideshow /
screensaver for the **Meta Portal Go** (Android 10 / API 29) that shows Google Photos shared
albums. App is 100% Kotlin — Jetpack Compose for the settings screen, Android Views for the
slideshow/scanner. There is no backend.

## Build & run

```bash
./gradlew assembleDebug      # -> app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

- Requires **JDK 17–21** (a newer/GraalVM JDK can fail AGP's `JdkImageTransform`; set `JAVA_HOME`)
  and an Android SDK (`ANDROID_SDK_ROOT`, or `local.properties` with
  `sdk.dir=` — git-ignored, never commit it).
- Versions are pinned in `app/build.gradle.kts` (compileSdk 36, minSdk 28, targetSdk 29).
- Set/clear the album for testing without rebuilding:
  ```bash
  adb shell am broadcast -n com.portalhacks.frame/.ConfigReceiver --es url "https://photos.app.goo.gl/XXXX"
  adb shell am broadcast -n com.portalhacks.frame/.ConfigReceiver --es url ""   # clear
  ```
- See README for the screensaver-install commands.

> `build.sh` is a **legacy** dependency-free pipeline (Java only). It predates the Compose
> migration and will NOT build the current app. Use Gradle.

## Architecture

- **`FrameDreamService`** — the registered screensaver; a thin trampoline that launches
  `MainActivity` and finishes. (Portal's ambient manager kills interactive in-dream windows, so
  the slideshow runs as a normal foreground Activity instead — see the README "trampoline" note.)
- **`MainActivity` + `SlideshowController`** (Kotlin, Android Views) — the actual full-screen
  slideshow: crossfade, clock/weather overlay, captions, Ken Burns, ambient color, night dimming.
- **`SettingsActivity`** (Kotlin/Compose) — the home-icon ("Frame") setup/settings screen. Hands
  off to `PhotosActivity` for the camera scanner / manual link entry.
- **`PhotosActivity`** (Kotlin, Android Views) — camera QR scanner + manual entry (ZXing, vendored jar).
- **`GooglePhotosSource`** — scrapes the public shared-album page (the Library API is gone).
- **`ImageLoader`** — background decode + disk/memory image cache.
- **`AlbumCache`** — shared persistence of the fetched photo list + title in `SharedPreferences`,
  used by both the slideshow and the settings preview.
- **`ConfigReceiver`** — `SharedPreferences` keys + the exported ADB config receiver.
- **`Ui` (Views) / `Theme.kt` (Compose)** — the shared Portal dark palette + Inter typography.

## Conventions & gotchas

- All Kotlin — no Java. Match surrounding style: idiomatic Kotlin + Android Views for the
  view-based pieces, idiomatic Compose for new UI. Keep the Portal palette (`Ui` / `PortalColors`).
- **Networking is HTTPS-only** and size-capped; album fetch and image download reject non-HTTPS
  URLs (`network_security_config.xml`). Don't loosen this. See `SECURITY.md`.
- The scraper regexes in `GooglePhotosSource` are intentionally tight and anchored to
  `lh3.googleusercontent.com`; it's unofficial and must fail closed (fall back to bundled samples).
- `ConfigReceiver` is exported (for ADB) but validates the album URL — keep that validation.
- Do **not** commit secrets, `local.properties`, keystores, or build output (see `.gitignore`).
- The codebase is fully Kotlin. The Compose-ification of the UI is partial: settings is Compose,
  while the slideshow (`MainActivity`/`SlideshowController`) and scanner (`PhotosActivity`) are
  Kotlin + Android Views. The view-based `MainActivity` is still the live dream target;
  `SlideshowComposeActivity` is a Compose port developed alongside it (not yet at parity).
