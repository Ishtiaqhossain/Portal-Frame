# Contributing to Frame

Thanks for your interest! Frame is a hobby/open-source slideshow + screensaver for the
Meta Portal Go. Contributions, bug reports, and ideas are welcome.

## Building

The app is Android (Java + Kotlin/Jetpack Compose) and builds with Gradle:

```bash
./gradlew assembleDebug
# -> app/build/outputs/apk/debug/app-debug.apk
```

Requirements:
- **JDK 17–21** (AGP needs a JDK in this range; a newer/GraalVM JDK can fail the build's
  `JdkImageTransform`). Point Gradle at one with `JAVA_HOME` or `org.gradle.java.home` if your
  default `java` is newer.
- An **Android SDK** — point to it with `ANDROID_SDK_ROOT`, or create a `local.properties`
  with `sdk.dir=/path/to/Android/sdk` (this file is git-ignored; never commit it).
- The compile/target SDKs and dependency versions are pinned in `app/build.gradle.kts`.

The Gradle build generates a debug keystore automatically, so you don't need to (and shouldn't)
commit one.

> The legacy `build.sh` (a dependency-free `aapt2 → javac → d8 → zipalign → apksigner` pipeline)
> predates the Compose migration and only compiles the Java sources — it won't produce a working
> APK of the current app. Use Gradle.

## Installing / testing on a Portal Go

See the **Install & run on Portal Go** section of the [README](README.md). In short:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Then either launch the **Frame** app icon (setup/settings) or set it as the screensaver
(commands in the README). The album can also be set over ADB for quick testing — see
`ConfigReceiver` and the README.

## Project layout

- `app/src/com/portalhacks/frame/` — sources.
  - `MainActivity` / `SlideshowController` — the full-screen slideshow (Java).
  - `FrameDreamService` — the screensaver trampoline that launches `MainActivity`.
  - `SettingsActivity` (Kotlin/Compose) — the home-screen setup/settings UI.
  - `PhotosActivity` (Java) — camera QR scanner + manual link entry.
  - `GooglePhotosSource` / `ImageLoader` / `AlbumCache` — fetching, decoding, caching.
- `app/res/`, `app/assets/` — resources, fonts, sample slides.
- `tools/` — pure-stdlib Python helpers that generate the sample slides and launcher icon.

## Code style

- Match the surrounding code: plain Java for the existing view-based pieces, idiomatic
  Compose/Kotlin for the new UI. Keep the dark Portal palette/typography (`Ui` / `PortalColors`).
- The Google Photos scraper (`GooglePhotosSource`) is unofficial and relies on the share-page
  format; keep its regexes tightly anchored and fail closed (fall back to bundled samples).
- Only fetch over HTTPS from Google Photos / Google image hosts (see `SECURITY.md`).

## Pull requests

Keep PRs focused, describe what you changed and how you tested it on-device (or why that wasn't
possible). By contributing you agree your work is licensed under the project's [MIT License](LICENSE).
