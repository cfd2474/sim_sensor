#!/usr/bin/env bash
# Builds the signed release APK and publishes it on the host for sideloading
# (https://<host>/download/simsensor.apk). See docs/REMOTE_SERVER.md "Publish the phone app".
#
# Needs: the release keystore (~/.simsensor/keystore.properties), ssh alias "simsensor".
set -euo pipefail

cd "$(dirname "$0")/.."
VERSION=$(tr -d '[:space:]' < VERSION)

./gradlew :android:assembleRelease --console=plain -q
APK=android/build/outputs/apk/release/android-release.apk

# Refuse an unsigned build: it could never update an installed copy.
BUILD_TOOLS=$(ls -d "${ANDROID_HOME:-$LOCALAPPDATA/Android/Sdk}"/build-tools/* | sort -V | tail -1)
APKSIGNER="$BUILD_TOOLS/apksigner"
[ -x "$APKSIGNER" ] || APKSIGNER="$BUILD_TOOLS/apksigner.bat"
"$APKSIGNER" verify "$APK" >/dev/null || { echo "APK is not signed; check ~/.simsensor/keystore.properties" >&2; exit 1; }

VERSION_CODE=$(python -c "import json;print(json.load(open('android/build/outputs/apk/release/output-metadata.json'))['elements'][0]['versionCode'])")
SHA256=$(sha256sum "$APK" | cut -d' ' -f1)
SIZE=$(stat -c %s "$APK")
NOW=$(date -u +%Y-%m-%dT%H:%M:%SZ)
META=$(printf '{"version":"%s","versionCode":%s,"sha256":"%s","sizeBytes":%s,"publishedAt":"%s"}' "$VERSION" "$VERSION_CODE" "$SHA256" "$SIZE" "$NOW")

# Upload under a temporary name, then rename, so a download never sees a half-written file.
ssh -o BatchMode=yes simsensor 'mkdir -p /opt/simsensor/apk'
scp -q -o BatchMode=yes "$APK" simsensor:/opt/simsensor/apk/simsensor.apk.uploading
ssh -o BatchMode=yes simsensor "cd /opt/simsensor/apk && echo '$META' > simsensor.json.uploading \
  && mv simsensor.apk.uploading simsensor.apk && mv simsensor.json.uploading simsensor.json \
  && sha256sum simsensor.apk"
echo "Published $VERSION (versionCode $VERSION_CODE), sha256 $SHA256"
