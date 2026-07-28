#!/bin/bash
# ESPad32 - one-shot debug APK build (works on any machine with JDK 17 +
# Android SDK set up - Minisforum, Linode, or anywhere else)
# Prereqs: JDK 17, Android SDK (ANDROID_HOME set, platform 35 +
# build-tools 35.0.0 matching build.gradle), Gradle installed
# system-wide (only used once, to generate the wrapper below).
#
# Unlike A330 Whiz Wheel (a Capacitor/web-wrapped app), ESPad32 is a
# native Kotlin project with no committed gradlew wrapper, so this
# generates one before building.
#
# IMPORTANT: the wrapper is pinned to a specific Gradle version
# (8.11.1) rather than whatever `gradle wrapper` would default to. On
# a fresh machine, the system-installed `gradle` (e.g. via apt) is
# often an older version than what this project actually needs -
# compileSdk 35 requires a modern Gradle, and an older one fails with
# errors like "Could not find method google()" when parsing
# settings.gradle. Pinning here means the FIRST build on any new
# machine downloads and uses exactly the right version regardless of
# what happened to already be installed system-wide.
set -e
cd "$(dirname "$0")"

GRADLE_VERSION="8.11.1"

echo "==> Ensuring Gradle wrapper exists (pinned to $GRADLE_VERSION)..."
if [ ! -f "./gradlew" ]; then
  gradle wrapper --gradle-version "$GRADLE_VERSION"
fi

echo "==> Building debug APK (clean build)..."
./gradlew --no-daemon clean assembleDebug

echo ""
echo "DONE: $(pwd)/app/build/outputs/apk/debug/app-debug.apk"
echo "Install: adb install ... or copy the APK to the phone and open it."
