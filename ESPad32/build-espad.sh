#!/bin/bash
# ESPad32 - one-shot debug APK build (run on the Minisforum box)
# Prereqs: JDK 17, Android SDK (ANDROID_HOME set, platform + build-tools
# matching build.gradle), Gradle installed system-wide.
#
# Unlike A330 Whiz Wheel (a Capacitor/web-wrapped app), ESPad32 is a
# native Kotlin project with no committed gradlew wrapper, so this
# generates one via the system `gradle` install before building.
set -e
cd "$(dirname "$0")"

echo "==> Ensuring Gradle wrapper exists..."
if [ ! -f "./gradlew" ]; then
  gradle wrapper
fi

echo "==> Building debug APK (clean build)..."
./gradlew --no-daemon clean assembleDebug

echo ""
echo "DONE: $(pwd)/app/build/outputs/apk/debug/app-debug.apk"
echo "Install: adb install ... or copy the APK to the phone and open it."
