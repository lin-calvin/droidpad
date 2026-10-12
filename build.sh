#!/usr/bin/env bash
# Minimal APK build for a no-dependency Android app (no Gradle/AGP).
set -euo pipefail

SDK="${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}"
BT="$SDK/build-tools/34.0.0"
PLATFORM="$SDK/platforms/android-34/android.jar"
JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-amd64}"
export PATH="$JAVA_HOME/bin:$PATH"

ROOT="$(cd "$(dirname "$0")" && pwd)"
OUT="$ROOT/build"
rm -rf "$OUT"
mkdir -p "$OUT/classes" "$OUT/dex"

LINK_ARGS=(-o "$OUT/base.apk" -I "$PLATFORM" --manifest "$ROOT/AndroidManifest.xml"
           --min-sdk-version 31 --target-sdk-version 34
           --version-code 1 --version-name 1.0)

if [ -d "$ROOT/res" ] && [ -n "$(ls -A "$ROOT/res" 2>/dev/null)" ]; then
    "$BT/aapt2" compile --dir "$ROOT/res" -o "$OUT/res.zip"
    LINK_ARGS+=(-R "$OUT/res.zip")
fi

"$BT/aapt2" link "${LINK_ARGS[@]}"

find "$ROOT/src" -name '*.java' > "$OUT/sources.txt"
javac -nowarn -source 8 -target 8 -classpath "$PLATFORM" \
      -d "$OUT/classes" @"$OUT/sources.txt"

find "$OUT/classes" -name '*.class' > "$OUT/classes.txt"
"$BT/d8" --release --lib "$PLATFORM" --min-api 31 --output "$OUT/dex" @"$OUT/classes.txt"

cp "$OUT/base.apk" "$OUT/app.apk"
( cd "$OUT" && zip -q -j app.apk dex/classes.dex )

"$BT/zipalign" -f 4 "$OUT/app.apk" "$OUT/aligned.apk"

KS="$ROOT/debug.keystore"
if [ ! -f "$KS" ]; then
    keytool -genkeypair -keystore "$KS" -alias androiddebugkey \
        -storepass android -keypass android -dname "CN=DroidPad" \
        -keyalg RSA -keysize 2048 -validity 10000 >/dev/null 2>&1
fi

"$BT/apksigner" sign --ks "$KS" --ks-pass pass:android --key-pass pass:android \
    --out "$OUT/droidpad.apk" "$OUT/aligned.apk"

"$BT/apksigner" verify --print-certs "$OUT/droidpad.apk" >/dev/null
echo "OK: $OUT/droidpad.apk"
ls -l "$OUT/droidpad.apk"
