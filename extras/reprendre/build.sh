#!/usr/bin/env bash
# Build and install Reprendre, the app behind the launcher's continue_row.
# Usage: TV_HOST=192.168.1.50 extras/reprendre/build.sh
# Needs JDK 17 and the Android SDK (platform 34, build-tools 34.0.0). The signing key is made on first run and
# kept outside the repo, in ~/.cleanyourtv/.
set -euo pipefail
: "${TV_HOST:?set TV_HOST to the TV IP address, e.g. TV_HOST=192.168.1.50 $0}"
cd "$(dirname "$0")"
ROOT="$(cd ../.. && pwd)"
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}" PATH="${JAVA_HOME}/bin:$PATH"
ANDROID_HOME="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"
BT="$ANDROID_HOME/build-tools/34.0.0"; JAR="$ANDROID_HOME/platforms/android-34/android.jar"
KEYDIR="$HOME/.cleanyourtv"; KS="$KEYDIR/reprendre.jks"; PASSFILE="$KEYDIR/reprendre.pass"
if [ ! -f "$KS" ]; then
  mkdir -p "$KEYDIR"; chmod 700 "$KEYDIR"
  (umask 077; head -c 24 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' > "$PASSFILE")
  keytool -genkeypair -keystore "$KS" -storepass "$(cat "$PASSFILE")" -keypass "$(cat "$PASSFILE")" -alias k \
    -keyalg RSA -keysize 2048 -validity 3650 -dname "CN=reprendre" >/dev/null 2>&1
fi
OUT="$(mktemp -d)"; trap 'rm -rf "$OUT"' EXIT
"$BT/aapt2" compile --dir res -o "$OUT/res.zip"
"$BT/aapt2" link -o "$OUT/base.apk" --manifest AndroidManifest.xml -I "$JAR" --min-sdk-version 26 --target-sdk-version 30 "$OUT/res.zip"
mkdir "$OUT/classes"
javac -source 8 -target 8 -cp "$JAR" -d "$OUT/classes" src/tv/reprendre/*.java 2>&1 | grep -v -i warning || true
"$BT/d8" --lib "$JAR" --output "$OUT" "$OUT"/classes/tv/reprendre/*.class
(cd "$OUT" && zip -q base.apk classes.dex)
"$BT/zipalign" -f 4 "$OUT/base.apk" "$OUT/aligned.apk"
"$BT/apksigner" sign --ks "$KS" --ks-pass "pass:$(cat "$PASSFILE")" --out "$OUT/reprendre.apk" "$OUT/aligned.apk"
PY="$ROOT/tools/venv/bin/python"
"$PY" -I "$ROOT/tools/b64push.py" "$OUT/reprendre.apk" /data/local/tmp/reprendre.apk
"$PY" -I "$ROOT/tools/tv.py" 'pm install -r /data/local/tmp/reprendre.apk; rm -f /data/local/tmp/reprendre.apk'
"$PY" -I "$ROOT/tools/tv.py" 'pm grant tv.reprendre android.permission.READ_TV_LISTINGS'
