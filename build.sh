#!/usr/bin/env bash
# Builds MD3A-MacroDeck3-Client.apk without Gradle or the Android SDK manager.
# Tools (see README): JDK 17, aapt2 / r8 (d8) / apksig from Google Maven, android.jar (API 34) from AOSP prebuilts.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
TOOLS="${MD3A_TOOLS:-/c/mdw/tools}"
JAVA_HOME="${JAVA_HOME_17:-$(ls -d "$TOOLS"/jdk17* | head -1)}"
JAVA="$JAVA_HOME/bin/java"
JAVAC="$JAVA_HOME/bin/javac"
KEYTOOL="$JAVA_HOME/bin/keytool"
AAPT2="$TOOLS/aapt2/aapt2.exe"
ANDROID_JAR="$TOOLS/android.jar"
PY="${PYTHON:-python}"

OUT="$HERE/build"
APK="$HERE/MD3A-MacroDeck3-Client.apk"
KS="$HERE/keystore/md3a.p12"
KS_PASS="md3a-android"
KS_ALIAS="md3a"

rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex" "$HERE/keystore"

echo "[1/6] aapt2 compile"
"$AAPT2" compile --dir "$HERE/app/res" -o "$OUT/res.zip"

echo "[2/6] aapt2 link"
"$AAPT2" link -o "$OUT/base.apk" -I "$ANDROID_JAR" \
  --manifest "$HERE/app/AndroidManifest.xml" \
  --java "$OUT/gen" --auto-add-overlay \
  "$OUT/res.zip"

echo "[3/6] javac"
if command -v cygpath >/dev/null; then
  find "$HERE/app/src" "$OUT/gen" -name '*.java' | xargs cygpath -m > "$OUT/sources.txt"
else
  find "$HERE/app/src" "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
fi
mkdir -p "$OUT/stubs"
"$JAVAC" -nowarn -Xlint:-options -source 8 -target 8 -bootclasspath "$ANDROID_JAR" \
  -d "$OUT/stubs" "$HERE/tools/stubs/java/lang/invoke/LambdaMetafactory.java"
SEP=":"; w() { echo "$1"; }
if command -v cygpath >/dev/null; then SEP=";"; w() { cygpath -m "$1"; }; fi
"$JAVAC" -nowarn -Xlint:-options -encoding UTF-8 -source 8 -target 8 \
  -bootclasspath "$(w "$OUT/stubs")$SEP$(w "$ANDROID_JAR")" -classpath "$ANDROID_JAR" \
  -d "$OUT/classes" @"$OUT/sources.txt"

echo "[4/6] d8"
"$JAVA" -cp "$TOOLS/r8.jar" com.android.tools.r8.D8 --release --min-api 21 \
  --lib "$ANDROID_JAR" --output "$OUT/dex" $(find "$OUT/classes" -name '*.class')

echo "[5/6] package"
"$PY" "$HERE/tools/addfiles.py" "$OUT/base.apk" "$OUT/unsigned.apk" "$OUT/dex/classes.dex=classes.dex"

echo "[6/6] sign"
if [ ! -f "$KS" ]; then
  "$KEYTOOL" -genkeypair -storetype PKCS12 -keystore "$KS" -storepass "$KS_PASS" -keypass "$KS_PASS" \
    -alias "$KS_ALIAS" -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=MD3A Macro Deck 3 Client, O=MD3A" >/dev/null 2>&1
fi
mkdir -p "$OUT/signer"
"$JAVAC" -nowarn -Xlint:-deprecation -cp "$TOOLS/apksig.jar" -d "$OUT/signer" "$HERE/tools/Sign.java"
"$JAVA" -cp "$(w "$OUT/signer")$SEP$(w "$TOOLS/apksig.jar")" Sign "$OUT/unsigned.apk" "$APK" "$KS" "$KS_PASS" "$KS_ALIAS"

echo "OK -> $APK"
