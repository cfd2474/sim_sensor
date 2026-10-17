#!/usr/bin/env bash
# Builds the signed Android App Bundle for Google Play and copies it to ~/Downloads as
# simsensor-<version>.aab, after checking it is signed with the release key that Play App
# Signing holds (docs/PLAY_RELEASE.md §1). Upload that file to the Play Console release.
#
# Only Google's APK is ever published (operator, 2026-10-17): after the upload, download Play's
# universal APK and publish that file (GitHub release, tools/publish-apk.sh, the MDM). Same key,
# so phones move between Play, the MDM and the download link and keep updating.
set -euo pipefail

cd "$(dirname "$0")/.."
VERSION="$(tr -d '[:space:]' < VERSION)"
EXPECTED="92:C8:80:E1:5E:3A:81:C3:71:B3:2F:80:5D:E9:16:C4:45:31:09:05:A8:8A:72:2A:8E:E8:DE:2A:CA:46:20:B3"

./gradlew :android:bundleRelease --console=plain -q

AAB="android/build/outputs/bundle/release/android-release.aab"
ACTUAL="$(keytool -printcert -jarfile "$AAB" | sed -n 's/^[[:space:]]*SHA256: //p' | head -1)"
if [ "$ACTUAL" != "$EXPECTED" ]; then
  echo "Refusing: the bundle is not signed with the Play release key (got '${ACTUAL:-unsigned}')." >&2
  exit 1
fi

OUT="${USERPROFILE:-$HOME}/Downloads/simsensor-${VERSION}.aab"
cp "$AAB" "$OUT"
echo "Built $OUT (version $VERSION), signed with the release key. Upload it to the Play Console release."
