#!/bin/sh
# Keeps the server's phone-app download (https://<host>/download/simsensor.apk) on the app that
# goes with the running version (chunk 4.12). Usage: fetch-app.sh <vX.Y.Z running>
#
# Takes simsensor-X.Y.Z.apk from that version's GitHub release. Versions without a new phone app
# have no APK on their release: then the latest release's app is used, if it is not newer than the
# running server. Never replaces a newer published app. Writes $ROOT/apk/simsensor.apk and
# simsensor.json (what tools/publish-apk.sh writes) atomically. Each version is checked once;
# a network problem is retried at the next call.
# Never fails the caller: problems are printed and the current download stays.
# Used by update.sh (after updates and inventories) and install.sh. POSIX sh, no jq.
ROOT="${SIM_INSTALL_DIR:-/opt/simsensor}"
REPO="${SIM_UPDATE_REPO:-cfd2474/sim_sensor}"
APK_DIR="$ROOT/apk"
TAG="$1"

code_of() { # X.Y.Z -> versionCode (android/build.gradle.kts: MAJOR*10000 + MINOR*100 + PATCH)
  echo "$1" | awk -F. '{ print $1 * 10000 + $2 * 100 + $3 }'
}
valid() { echo "$1" | grep -Eq '^v[0-9]{1,4}\.[0-9]{1,4}\.[0-9]{1,6}$'; }

valid "$TAG" || { echo "fetch-app: not a version tag: $TAG"; exit 0; }
mkdir -p "$APK_DIR" && chmod 755 "$APK_DIR"
[ -f "$APK_DIR/.checked-$TAG" ] && exit 0

RUNNING=$(code_of "${TAG#v}")
PUBLISHED=$(sed -n 's/.*"versionCode":\([0-9]*\).*/\1/p' "$APK_DIR/simsensor.json" 2>/dev/null)
PUBLISHED=${PUBLISHED:-0}

# tag -> 0 done (published, or nothing to do), 1 no APK on that release, 2 GitHub not reachable
try() {
  t="$1"; v="${t#v}"; c=$(code_of "$v")
  if [ "$c" -le "$PUBLISHED" ]; then echo "fetch-app: the download already has $PUBLISHED (>= $c)"; return 0; fi
  if [ "$c" -gt "$RUNNING" ]; then echo "fetch-app: $t's app is newer than the running $TAG; not published"; return 0; fi
  tmp="$APK_DIR/simsensor.apk.downloading"
  http=$(curl -sSL -m 300 -o "$tmp" -w '%{http_code}' "https://github.com/$REPO/releases/download/$t/simsensor-$v.apk" 2>/dev/null)
  if [ "$http" != 200 ]; then
    rm -f "$tmp"
    [ "$http" = 404 ] && return 1
    echo "fetch-app: couldn't download $t's app (HTTP ${http:-none}); will try again later"
    return 2
  fi
  # An APK is a zip file; anything else (an error page) is refused.
  if [ "$(head -c 2 "$tmp")" != "PK" ]; then rm -f "$tmp"; echo "fetch-app: $t's download is not an APK"; return 0; fi
  sum=$(sha256sum < "$tmp" | cut -c1-64)
  size=$(wc -c < "$tmp" | tr -d ' ')
  now=$(date -u +%Y-%m-%dT%H:%M:%SZ)
  printf '{"version":"%s","versionCode":%s,"sha256":"%s","sizeBytes":%s,"publishedAt":"%s","source":"github-release"}\n' \
    "$v" "$c" "$sum" "$size" "$now" > "$APK_DIR/simsensor.json.downloading"
  chmod 644 "$tmp" "$APK_DIR/simsensor.json.downloading"
  mv "$tmp" "$APK_DIR/simsensor.apk" && mv "$APK_DIR/simsensor.json.downloading" "$APK_DIR/simsensor.json"
  echo "fetch-app: published the phone app $v (versionCode $c, sha256 $sum)"
  return 0
}

try "$TAG"
result=$?
if [ $result = 1 ]; then
  # No APK on this version's release (a server-only version): the latest release's app.
  LATEST=$(curl -fsSI -m 30 "https://github.com/$REPO/releases/latest" 2>/dev/null | tr -d '\r' \
    | sed -n 's|^[Ll]ocation: .*/releases/tag/\(v[0-9.]*\)$|\1|p' | tail -1)
  if ! valid "$LATEST"; then
    echo "fetch-app: no phone app on $TAG's release, and the latest release couldn't be read; will try again later"
    result=2
  elif [ "$LATEST" = "$TAG" ]; then
    echo "fetch-app: no phone app on $TAG's release"
    result=0
  else
    try "$LATEST"
    result=$?
    if [ $result = 1 ]; then echo "fetch-app: no phone app on $TAG's or $LATEST's release"; result=0; fi
  fi
fi
# Only a definite answer is remembered; a network problem is retried at the next call.
[ $result = 2 ] || touch "$APK_DIR/.checked-$TAG"
exit 0
