#!/usr/bin/env bash
# Dependency-free Android APK build (no Gradle / no AndroidX).
# Pipeline: aapt2 link -> javac -> d8 -> add dex -> zipalign -> apksigner.
set -euo pipefail

SDK="${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}"
BT="$SDK/build-tools/35.0.0"
ANDROID_JAR="$SDK/platforms/android-36/android.jar"

ROOT="$(cd "$(dirname "$0")" && pwd)"
APP="$ROOT/app"
BUILD="$ROOT/build"
PKG_PATH="com/portalhacks/frame"
LIBS="$APP/libs"
ZXING="$LIBS/zxing-core-3.5.3.jar"   # pure-Java QR decoder, vendored (no Gradle here)

AAPT2="$BT/aapt2"
D8="$BT/d8"
ZIPALIGN="$BT/zipalign"
APKSIGNER="$BT/apksigner"

echo "==> Clean"
rm -rf "$BUILD"
mkdir -p "$BUILD/classes"

echo "==> Generate sample slides"
python3 "$ROOT/tools/gen_slides.py" "$APP/assets/slides" >/dev/null
ls "$APP/assets/slides"

echo "==> Generate launcher icon"
python3 "$ROOT/tools/gen_icon.py" "$APP/res/mipmap-xxxhdpi" >/dev/null

echo "==> aapt2 compile resources (res/ -> res.zip)"
"$AAPT2" compile --dir "$APP/res" -o "$BUILD/res.zip"

echo "==> aapt2 link (manifest + resources + assets -> base.apk)"
"$AAPT2" link \
    -o "$BUILD/base.apk" \
    -I "$ANDROID_JAR" \
    --manifest "$APP/AndroidManifest.xml" \
    -A "$APP/assets" \
    "$BUILD/res.zip"

echo "==> javac"
javac \
    -source 8 -target 8 \
    -bootclasspath "$ANDROID_JAR" \
    -cp "$ZXING" \
    -d "$BUILD/classes" \
    "$APP/src/$PKG_PATH"/*.java

echo "==> d8 (classes + zxing -> classes.dex)"
"$D8" --min-api 24 \
    --output "$BUILD" \
    $(find "$BUILD/classes" -name '*.class') \
    "$ZXING"

echo "==> add classes.dex into apk"
cp "$BUILD/base.apk" "$BUILD/unsigned.apk"
( cd "$BUILD" && zip -q -j unsigned.apk classes.dex )

echo "==> zipalign"
"$ZIPALIGN" -f -p 4 "$BUILD/unsigned.apk" "$BUILD/aligned.apk"

echo "==> debug keystore"
# Kept at project root (NOT under build/, which is wiped each run) so the
# signing key is stable and reinstalls don't hit INSTALL_FAILED_UPDATE_INCOMPATIBLE.
KS="$ROOT/debug.keystore"
if [ ! -f "$KS" ]; then
    keytool -genkeypair -keystore "$KS" -storepass android -keypass android \
        -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
        -dname "CN=Android Debug,O=Android,C=US" >/dev/null 2>&1
fi

echo "==> apksigner sign"
"$APKSIGNER" sign \
    --ks "$KS" --ks-pass pass:android --key-pass pass:android \
    --out "$BUILD/PortalFrame.apk" \
    "$BUILD/aligned.apk"

"$APKSIGNER" verify "$BUILD/PortalFrame.apk" && echo "==> signature OK"
echo "==> built: $BUILD/PortalFrame.apk"
