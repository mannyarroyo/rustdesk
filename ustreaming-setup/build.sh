#!/usr/bin/env bash
# Builds the unsigned U-Streaming Setup APK with the plain Android SDK tools
# (no Gradle). Usage: build.sh <version-code> <out.apk>
set -euo pipefail
VERSION_CODE="$1"
OUT="$2"
HERE="$(cd "$(dirname "$0")" && pwd)"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT}}"
BT="$SDK/build-tools/34.0.0"
JAR="$SDK/platforms/android-34/android.jar"
W="$(mktemp -d)"

# The Setup app has its own icons, banner and logo.
mkdir -p "$W"
cp -R "$HERE/res" "$W/res"

"$BT/aapt2" compile --dir "$W/res" -o "$W/res.zip"
"$BT/aapt2" link -o "$W/base.apk" -I "$JAR" --manifest "$HERE/AndroidManifest.xml" \
  --min-sdk-version 21 --target-sdk-version 34 \
  --version-code "$VERSION_CODE" --version-name "1.0.$VERSION_CODE" "$W/res.zip"

mkdir -p "$W/classes"
javac --release 8 -cp "$JAR" -d "$W/classes" $(find "$HERE/src" -name '*.java')
"$BT/d8" --release --min-api 21 --lib "$JAR" --output "$W" $(find "$W/classes" -name '*.class')
(cd "$W" && zip -q base.apk classes.dex)
"$BT/zipalign" -f -p 4 "$W/base.apk" "$OUT"
echo "built $OUT"
