#!/bin/bash
# Собирает addons/durak_bt/bin/durak_bt.aar без Gradle (javac + zip).
set -e
cd "$(dirname "$0")"
SDK=${ANDROID_HOME:-/opt/android/sdk}
JAVA_HOME=${JAVA_HOME:-/opt/android/jdk17}
GODOT_LIB=${GODOT_LIB:-/opt/android/work/godotlib/classes.jar}
OUT=build
rm -rf "$OUT" && mkdir -p "$OUT/classes" "$OUT/aar"
"$JAVA_HOME/bin/javac" -source 11 -target 11 -nowarn -encoding UTF-8 \
  -cp "$SDK/platforms/android-35/android.jar:$GODOT_LIB" \
  -d "$OUT/classes" $(find src -name '*.java') 2>&1 | grep -v "bootstrap classpath" || true
test -f "$OUT/classes/com/durak/bt/DurakBluetoothPlugin.class"
(cd "$OUT/classes" && "$JAVA_HOME/bin/jar" cf ../aar/classes.jar .)
cp AndroidManifest.xml "$OUT/aar/"
touch "$OUT/aar/R.txt"
mkdir -p ../addons/durak_bt/bin
(cd "$OUT/aar" && rm -f ../../../addons/durak_bt/bin/durak_bt.aar && zip -q -r ../../../addons/durak_bt/bin/durak_bt.aar .)
echo "AAR: $(cd ../addons/durak_bt/bin && pwd)/durak_bt.aar"
