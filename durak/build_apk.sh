#!/bin/bash
# Сборка APK: ./build_apk.sh [release|debug]
set -e
cd "$(dirname "$0")"
MODE=${1:-release}
export JAVA_HOME=${JAVA_HOME:-/opt/android/jdk17}
export ANDROID_HOME=${ANDROID_HOME:-/opt/android/sdk}
KS="$(pwd)/keystore/durak.keystore"
export GODOT_ANDROID_KEYSTORE_RELEASE_PATH="$KS" GODOT_ANDROID_KEYSTORE_RELEASE_USER=durak GODOT_ANDROID_KEYSTORE_RELEASE_PASSWORD=durak123
export GODOT_ANDROID_KEYSTORE_DEBUG_PATH="$KS" GODOT_ANDROID_KEYSTORE_DEBUG_USER=durak GODOT_ANDROID_KEYSTORE_DEBUG_PASSWORD=durak123
./android_plugin/build_aar.sh
godot --headless --import >/dev/null 2>&1 || true
INSTALL=""
if [ ! -f android/build/build.gradle ]; then INSTALL="--install-android-build-template"; fi
if false; then
  godot --headless --install-android-build-template --quit 2>&1 | tail -3 || true
fi
mkdir -p build
godot --headless $INSTALL --export-$MODE "Android" build/durak.apk
ls -la build/durak.apk
