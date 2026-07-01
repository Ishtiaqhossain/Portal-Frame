# CLAUDE.md

Guidance for AI assistants (and humans) working in this repo.

## What this is

**Frame** (repo: PortalFrame, app id `com.portalhacks.frame`) — an Android slideshow /
screensaver for the **Meta Portal Go** (Android 10 / API 29) that shows Google Photos and iCloud
shared albums, plus photos pushed from a phone over Wi‑Fi (the "AirDrop for Portal" drop). App is
100% Kotlin — Jetpack Compose for the settings screens, Android Views for the slideshow/scanner.
No cloud backend; the only server is an on-device, LAN-only photo-drop HTTP server (see Architecture).

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
  `SlideshowComposeActivity` and finishes. (Portal's ambient manager kills interactive in-dream
  windows, so the slideshow runs as a normal foreground Activity instead — see the README
  "trampoline" note.)
- **`SlideshowComposeActivity` + `SlideshowController`** (Kotlin) — the actual full-screen
  slideshow and the dream target. The Activity is Compose (`setContent`) and hosts the
  view-based `SlideshowController` via `AndroidView` (the controller's crossfade / Ken Burns
  `ValueAnimator` / `Canvas` shimmer are imperative custom animation, best kept as Views); the
  Activity owns the album fetch/cache/refresh + night-dimming logic. Features: crossfade,
  clock/weather overlay, captions, Ken Burns, face-aware framing, ambient color, auto-enhance,
  night dimming, smart shuffle, On This Day, portrait pairing.
- **`SettingsActivity`** (Kotlin/Compose) — the home-icon ("Frame") setup/settings screen: a
  two-column "Set up" / "Customize" layout (tuning grouped into collapsible sections). Hands off to
  `PhotosActivity` (scanner / manual link entry) and `UploadsActivity` (phone-pushed photos grid),
  and shows the phone-drop QR.
- **`PhotosActivity`** (Kotlin, Android Views) — camera QR scanner + manual entry (ZXing, vendored jar).
- **`PhotoProvider` / `PhotoSources`** — provider abstraction + registry. `PhotoSources.matches(url)`
  / `fetch(url)` route a link to the right provider and return a shared `Album` (title + slides).
  Add a new provider by implementing `PhotoProvider` and listing it in `PhotoSources`.
- **`GooglePhotosSource`** — provider that scrapes the public shared-album page (the Library API is gone).
- **`ApplePhotosSource`** — provider for public iCloud Shared Albums via Apple's `sharedstreams`
  web API (`webstream` → `webasseturls`, handling the 330 partition redirect).
- **`ImageLoader`** — background decode + disk/memory image cache. Also decodes locally-pushed
  photos (absolute `filesDir/uploads` paths) and applies their EXIF orientation (androidx).
- **`AlbumCache`** — shared persistence of the fetched photo list + title in `SharedPreferences`,
  used by both the slideshow and the settings preview.
- **Add photos from a phone ("AirDrop for Portal")** — a phone on the LAN scans an on-screen QR,
  its browser posts photos to the frame, and they persist in the slideshow rotation:
  - **`DropServerService`** — foreground service owning the LAN server (started from the slideshow,
    Settings, and on boot); broadcasts `ACTION_UPLOAD` (package-scoped) on each upload.
  - **`LocalDropServer`** — embedded HTTP server (raw `ServerSocket` + hand-rolled multipart, no HTTP
    lib): serves the mobile upload page (`GET /`) and accepts `POST /upload`. **Plaintext + LAN-only
    by design**, token-gated, image-validated (magic bytes), size/connection-capped.
  - **`LocalUploads`** — persistent `filesDir/uploads` store (the "keep") with count/byte caps.
  - **`DropAuth`** (per-install token carried in the QR), **`DropServerStatus`** (bound port + LAN
    URL), **`QrCodes`** (ZXing QR encode).
  - **`UploadsActivity`** (Compose) — full-screen `LazyVerticalGrid` to view/delete pushed photos;
    reached from `SettingsActivity`'s "Manage photos".
- **`ConfigReceiver`** — `SharedPreferences` keys + the exported ADB config receiver.
- **`Ui` (Views) / `Theme.kt` (Compose)** — the shared Portal dark palette + Inter typography.

## Conventions & gotchas

- All Kotlin — no Java. Match surrounding style: idiomatic Kotlin + Android Views for the
  view-based pieces, idiomatic Compose for new UI. Keep the Portal palette (`Ui` / `PortalColors`).
- **Outbound networking is HTTPS-only** and size-capped; album fetch and image download reject
  non-HTTPS URLs (`network_security_config.xml`). Don't loosen this. The inbound photo-drop server
  (`LocalDropServer`) is the deliberate exception: plaintext + LAN-only + token-gated **by design**
  (it's a server socket, which the network-security config doesn't govern). See `SECURITY.md`.
- **Static analysis runs in CI** (`lintDebug` + `detekt` + `ktlint` on every push/PR): new findings
  fail, existing ones are grandfathered by baselines (`app/detekt-baseline.xml`,
  `app/config/ktlint/baseline.xml`; detekt config at `config/detekt/detekt.yml`). Run
  `./gradlew lintDebug detekt ktlintCheck` and `./gradlew ktlintFormat` before pushing.
- The scraper regexes in `GooglePhotosSource` are intentionally tight and anchored to
  `lh3.googleusercontent.com`; it's unofficial and must fail closed (fall back to bundled samples).
- `ConfigReceiver` is exported (for ADB) but validates the album URL — keep that validation.
- Do **not** commit secrets, `local.properties`, keystores, or build output (see `.gitignore`).
- The codebase is fully Kotlin and every Activity is Compose (`setContent`). `SettingsActivity`
  is native Compose; `SlideshowComposeActivity` (the live dream target) is Compose hosting the
  imperative `SlideshowController` View stack via `AndroidView`, and `PhotosActivity` (scanner)
  is still a view-based screen. `SlideshowController`/`Ui` remain Android Views by design (custom
  animation / canvas drawing); that's the intended end state, not a pending migration.
