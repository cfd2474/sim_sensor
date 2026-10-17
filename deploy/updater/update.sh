#!/bin/sh
# One server update (chunk 4.6), the same steps as the manual deploy in docs/REMOTE_SERVER.md:
# check the tag → download it from GitHub → back up the database → switch `current` → rebuild and
# restart (not the updater) → check the health page → roll back if it doesn't come up.
# Status for the console goes to update/status.json, the log to update/update.log.
set -u
ROOT="${SIM_INSTALL_DIR:-/opt/simsensor}"
REPO="${SIM_UPDATE_REPO:-cfd2474/sim_sensor}"
DIR="$ROOT/update"
LOG="$DIR/update.log"
SERVICES="proxy server postgres redis"   # everything except the updater itself
COMPOSE="docker compose --env-file $ROOT/.env"

mv "$DIR/request.json" "$DIR/processing.json" || exit 0
# Keep the heartbeat going while this runs (the main loop waits for it): the console shows the
# updater is alive during long steps such as the build.
( while kill -0 $$ 2>/dev/null; do date -u +%s > "$DIR/heartbeat"; sleep 5; done ) &
TAG=$(jq -r '.tag // empty' "$DIR/processing.json" 2>/dev/null)
BY=$(jq -r '.requestedBy // empty' "$DIR/processing.json" 2>/dev/null)
STARTED=$(date -u +%Y-%m-%dT%H:%M:%SZ)
: > "$LOG"

log() { echo "$(date -u +%H:%M:%S) $*" >> "$LOG"; }
status() { # state step message
  jq -n --arg state "$1" --arg step "$2" --arg message "$3" --arg tag "$TAG" --arg by "$BY" \
    --arg startedAt "$STARTED" --arg at "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
    '{state: $state, step: $step, message: $message, tag: $tag, requestedBy: $by, startedAt: $startedAt, updatedAt: $at}' \
    > "$DIR/status.json.tmp" && mv "$DIR/status.json.tmp" "$DIR/status.json"
}
finish() { # state message
  status "$1" done "$2"
  log "$1: $2"
  rm -f "$DIR/processing.json"
  exit 0
}

# 1. The tag: strictly vX.Y.Z (nothing else ever reaches a command), newer, and on GitHub.
echo "$TAG" | grep -Eq '^v[0-9]{1,4}\.[0-9]{1,4}\.[0-9]{1,6}$' || finish failed "Not a version tag: $TAG"
PREV=$(readlink "$ROOT/current")
CURRENT=$(basename "$PREV")
log "Update $CURRENT -> $TAG requested by $BY"
NEWEST=$(printf '%s\n%s\n' "$CURRENT" "$TAG" | sed 's/^v//' | sort -t. -k1,1n -k2,2n -k3,3n | tail -1)
[ "v$NEWEST" = "$TAG" ] && [ "$TAG" != "$CURRENT" ] || finish failed "$TAG is not newer than $CURRENT"
status running check "Checking $TAG on GitHub"
curl -fsS -o /dev/null "https://api.github.com/repos/$REPO/git/ref/tags/$TAG" >> "$LOG" 2>&1 || finish failed "$TAG was not found in $REPO"

# 2. Download the tag (the same files as `git archive`).
status running download "Downloading $TAG"
NEW="$ROOT/releases/$TAG"
rm -rf "$NEW.tmp" && mkdir -p "$NEW.tmp"
if ! curl -fsSL "https://codeload.github.com/$REPO/tar.gz/refs/tags/$TAG" | tar -xz --strip-components=1 -C "$NEW.tmp" 2>> "$LOG"; then
  rm -rf "$NEW.tmp"
  finish failed "Downloading $TAG failed"
fi
rm -rf "$NEW" && mv "$NEW.tmp" "$NEW"

# 3. Database backup (as before every manual release).
status running backup "Backing up the database"
mkdir -p "$ROOT/backups"
BACKUP="$ROOT/backups/pre-$TAG-$(date -u +%Y%m%dT%H%M%SZ).dump"
if ! docker exec simsensor-postgres-1 pg_dump -U simsensor -Fc simsensor > "$BACKUP" 2>> "$LOG"; then
  rm -f "$BACKUP"
  finish failed "The database backup failed; nothing was changed"
fi
chmod 600 "$BACKUP"
log "Backup $BACKUP"

# 4. Switch and rebuild. The server builds from source here, which takes several minutes.
status running build "Building and restarting the server (several minutes; phones reconnect by themselves)"
ln -sfn "$NEW" "$ROOT/current"
if ! (cd "$ROOT/current/deploy" && $COMPOSE up -d --build $SERVICES) >> "$LOG" 2>&1; then
  BUILD_FAILED=1
else
  BUILD_FAILED=0
fi

# 5. Health: the server must report the new version.
WANT=${TAG#v}
healthy() {
  i=0
  while [ $i -lt "${SIM_UPDATE_HEALTH_TRIES:-90}" ]; do # 5 s apart: up to 7.5 minutes after the restart
    if curl -fsS http://server:8080/health 2>/dev/null | grep -q "\"version\":\"$1\""; then return 0; fi
    sleep 5
    i=$((i + 1))
  done
  return 1
}
if [ "$BUILD_FAILED" = 0 ]; then
  status running health "Waiting for the server to start"
  if healthy "$WANT"; then
    finish succeeded "Updated to $TAG (backup $(basename "$BACKUP"))"
  fi
fi

# 6. Roll back to the previous release. Database changes stay (they only add); the old server
#    ignores newer migrations.
status running rollback "The new version didn't start; going back to $CURRENT"
log "Rolling back to $PREV"
ln -sfn "$PREV" "$ROOT/current"
(cd "$ROOT/current/deploy" && $COMPOSE up -d --build $SERVICES) >> "$LOG" 2>&1
if healthy "${CURRENT#v}"; then
  finish failed "$TAG didn't start, so the server went back to $CURRENT. Details in update/update.log."
fi
finish failed "$TAG didn't start and going back to $CURRENT didn't either. The server needs a manual fix (docs/REMOTE_SERVER.md)."
