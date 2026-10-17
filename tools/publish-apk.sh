#!/usr/bin/env bash
# Publishes Google Play's universal APK on the host for sideloading
# (https://<host>/download/simsensor.apk). See docs/REMOTE_SERVER.md "Publish the phone app".
#
#   tools/publish-apk.sh ~/Downloads/3200.apk
#
# Only Google's APK is ever published, never a local build (operator, 2026-10-17): the file must
# carry our certificate *and* Google's source stamp, which only Play-generated APKs have. The
# version comes from the file itself. Servers on v0.30.0 or later also keep this download current
# by themselves from the GitHub release (deploy/updater/fetch-app.sh).
#
# Needs: the Android build tools (apksigner, aapt2), ssh alias "simsensor".
set -euo pipefail

APK="${1:?Usage: tools/publish-apk.sh <Google Play universal APK, e.g. ~/Downloads/3200.apk>}"
[ -f "$APK" ] || { echo "No such file: $APK" >&2; exit 1; }
EXPECTED="92c880e15e3a81c371b32f805de916c445310905a88a722a8ee8de2aca4620b3"

BUILD_TOOLS=$(ls -d "${ANDROID_HOME:-$LOCALAPPDATA/Android/Sdk}"/build-tools/* | sort -V | tail -1)
APKSIGNER="$BUILD_TOOLS/apksigner"; [ -x "$APKSIGNER" ] || APKSIGNER="$BUILD_TOOLS/apksigner.bat"
AAPT2="$BUILD_TOOLS/aapt2"; [ -x "$AAPT2" ] || AAPT2="$BUILD_TOOLS/aapt2.exe"

CERTS=$("$APKSIGNER" verify --print-certs "$APK") || { echo "Refusing: the APK's signature doesn't verify." >&2; exit 1; }
echo "$CERTS" | grep -q "Signer #1 certificate SHA-256 digest: $EXPECTED" \
  || { echo "Refusing: not signed with the Sim Sensor key." >&2; exit 1; }
echo "$CERTS" | grep -q "Source Stamp Signer certificate DN: CN=Android, OU=Android, O=Google Inc." \
  || { echo "Refusing: no Google source stamp. Publish the APK downloaded from the Play Console, not a local build." >&2; exit 1; }

BADGING=$("$AAPT2" dump badging "$APK" | head -1)
[[ "$BADGING" == *"name='com.taksolutions.simsensor'"* ]] || { echo "Refusing: not the Sim Sensor app." >&2; exit 1; }
VERSION=$(sed -n "s/.*versionName='\([^']*\)'.*/\1/p" <<< "$BADGING")
VERSION_CODE=$(sed -n "s/.*versionCode='\([0-9]*\)'.*/\1/p" <<< "$BADGING")
SHA256=$(sha256sum < "$APK" | cut -c1-64)   # from stdin: no "\" escape marker on Windows paths
SIZE=$(wc -c < "$APK" | tr -d ' ')
NOW=$(date -u +%Y-%m-%dT%H:%M:%SZ)
META=$(printf '{"version":"%s","versionCode":%s,"sha256":"%s","sizeBytes":%s,"publishedAt":"%s","source":"google-play"}' "$VERSION" "$VERSION_CODE" "$SHA256" "$SIZE" "$NOW")

# Upload under a temporary name, then rename, so a download never sees a half-written file.
ssh -o BatchMode=yes simsensor 'mkdir -p /opt/simsensor/apk'
scp -q -o BatchMode=yes "$APK" simsensor:/opt/simsensor/apk/simsensor.apk.uploading
ssh -o BatchMode=yes simsensor "cd /opt/simsensor/apk && echo '$META' > simsensor.json.uploading \
  && mv simsensor.apk.uploading simsensor.apk && mv simsensor.json.uploading simsensor.json \
  && sha256sum simsensor.apk"
echo "Published $VERSION (versionCode $VERSION_CODE, Google-signed), sha256 $SHA256"
