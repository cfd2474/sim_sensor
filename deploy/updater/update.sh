#!/bin/sh
# The updater's actions, requested by the server through update/request.json ({"action": ...}):
#   update   (default)  check the tag → download it from GitHub → back up the database → switch
#                       `current` → rebuild and restart (not the updater) → health check → roll
#                       back if it doesn't come up                                      (chunk 4.6)
#   backup              a database backup now (backups/manual-<time>.dump)             (chunk 4.9)
#   restore  <file>     safety backup → stop the server → replace the database with <file>
#                       (TimescaleDB restore mode) → start → health; on failure, put the safety
#                       backup back                                                    (chunk 4.9)
#   inventory           list the backups for the console and delete those past the retention
#                       period (update/retention.json; the newest backup is always kept)
# Every action ends with an inventory. Status for the console goes to update/status.json (not for
# inventory), the log to update/update.log.
set -u
ROOT="${SIM_INSTALL_DIR:-/opt/simsensor}"
REPO="${SIM_UPDATE_REPO:-cfd2474/sim_sensor}"
DIR="$ROOT/update"
BACKUPS="$ROOT/backups"
LOG="$DIR/update.log"
SERVICES="proxy server postgres redis"   # everything except the updater itself
COMPOSE="docker compose --env-file $ROOT/.env"
DB_CONTAINER="${SIM_DB_CONTAINER:-simsensor-postgres-1}"
DB="simsensor"

mv "$DIR/request.json" "$DIR/processing.json" || exit 0
# Keep the heartbeat going while this runs (the main loop waits for it): the console shows the
# updater is alive during long steps such as the build.
( while kill -0 $$ 2>/dev/null; do date -u +%s > "$DIR/heartbeat"; sleep 5; done ) &
ACTION=$(jq -r '.action // "update"' "$DIR/processing.json" 2>/dev/null)
TAG=$(jq -r '.tag // empty' "$DIR/processing.json" 2>/dev/null)
FILE=$(jq -r '.file // empty' "$DIR/processing.json" 2>/dev/null)
BY=$(jq -r '.requestedBy // empty' "$DIR/processing.json" 2>/dev/null)
STARTED=$(date -u +%Y-%m-%dT%H:%M:%SZ)
mkdir -p "$BACKUPS"

log() { echo "$(date -u +%H:%M:%S) $*" >> "$LOG"; }
status() { # state step message
  jq -n --arg action "$ACTION" --arg state "$1" --arg step "$2" --arg message "$3" --arg tag "$TAG" --arg file "$FILE" \
    --arg by "$BY" --arg startedAt "$STARTED" --arg at "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
    '{action: $action, state: $state, step: $step, message: $message, tag: $tag, file: $file, requestedBy: $by, startedAt: $startedAt, updatedAt: $at}' \
    > "$DIR/status.json.tmp" && mv "$DIR/status.json.tmp" "$DIR/status.json"
}

# --- Backups -------------------------------------------------------------------------------------

inventory() { # prune by retention, then list for the console
  DAYS=$(jq -r '.days // 7' "$DIR/retention.json" 2>/dev/null || echo 7)
  echo "$DAYS" | grep -Eq '^[0-9]{1,3}$' || DAYS=7
  [ "$DAYS" -ge 1 ] || DAYS=1
  NEWEST=$(ls -1t "$BACKUPS"/*.dump 2>/dev/null | head -1)
  for f in $(find "$BACKUPS" -maxdepth 1 -name '*.dump' -type f -mmin +$((DAYS * 1440)) 2>/dev/null); do
    if [ "$f" != "$NEWEST" ]; then rm -f "$f" && log "Deleted $(basename "$f") (older than $DAYS days)"; fi
  done
  {
    echo "["
    first=1
    for f in $(ls -1t "$BACKUPS"/*.dump 2>/dev/null); do
      [ $first = 1 ] || echo ","
      first=0
      jq -n --arg name "$(basename "$f")" --argjson size "$(stat -c %s "$f")" \
        --arg created "$(date -u -d "@$(stat -c %Y "$f")" +%Y-%m-%dT%H:%M:%SZ)" '{name: $name, sizeBytes: $size, createdAt: $created}'
    done
    echo "]"
  } | jq -c --argjson days "$DAYS" --arg at "$(date -u +%Y-%m-%dT%H:%M:%SZ)" '{backups: ., retentionDays: $days, inventoryAt: $at}' \
    > "$DIR/backups.json.tmp" && mv "$DIR/backups.json.tmp" "$DIR/backups.json"
}

finish() { # state message
  status "$1" done "$2"
  log "$1: $2"
  inventory
  rm -f "$DIR/processing.json"
  exit 0
}

dump_to() { # file — a full database backup
  if docker exec "$DB_CONTAINER" pg_dump -U "$DB" -Fc "$DB" > "$1" 2>> "$LOG"; then
    chmod 600 "$1"
    log "Backup $(basename "$1") ($(stat -c %s "$1") bytes)"
    return 0
  fi
  rm -f "$1"
  return 1
}

restore_from() { # file — replace the database (TimescaleDB restore mode); the server must be stopped
  docker exec "$DB_CONTAINER" psql -U "$DB" -d postgres -qc "DROP DATABASE $DB WITH (FORCE);" -c "CREATE DATABASE $DB;" >> "$LOG" 2>&1 \
    && docker exec "$DB_CONTAINER" psql -U "$DB" -d "$DB" -qc "CREATE EXTENSION IF NOT EXISTS timescaledb;" -c "SELECT timescaledb_pre_restore();" >> "$LOG" 2>&1 \
    && docker exec -i "$DB_CONTAINER" pg_restore -U "$DB" -d "$DB" --no-owner < "$1" >> "$LOG" 2>&1 \
    && docker exec "$DB_CONTAINER" psql -U "$DB" -d "$DB" -qc "SELECT timescaledb_post_restore();" >> "$LOG" 2>&1
}

server_up() { # the server answers its health page (any version)
  i=0
  while [ $i -lt "${SIM_UPDATE_HEALTH_TRIES:-90}" ]; do
    if curl -fsS http://server:8080/health 2>/dev/null | grep -q '"status":"ok"'; then return 0; fi
    sleep 5
    i=$((i + 1))
  done
  return 1
}

STAMP=$(date -u +%Y%m%dT%H%M%SZ)
: > "$LOG"

case "$ACTION" in
  inventory)
    inventory
    rm -f "$DIR/processing.json"
    exit 0
    ;;
  backup)
    log "Backup requested by $BY"
    status running backup "Backing up the database"
    OUT="$BACKUPS/manual-$STAMP.dump"
    dump_to "$OUT" || finish failed "The backup failed. Details in update/update.log."
    finish succeeded "Backup $(basename "$OUT") made"
    ;;
  restore)
    # Only a plain file name from the backups folder ever reaches a command.
    echo "$FILE" | grep -Eq '^[A-Za-z0-9][A-Za-z0-9._-]{0,120}\.dump$' || finish failed "Not a backup file name: $FILE"
    [ -f "$BACKUPS/$FILE" ] || finish failed "$FILE is not in the backups folder"
    log "Restore of $FILE requested by $BY"
    status running safety "Backing up the current data first"
    SAFETY="$BACKUPS/pre-restore-$STAMP.dump"
    dump_to "$SAFETY" || finish failed "The safety backup failed, so nothing was changed. Details in update/update.log."
    status running stop "Stopping the server"
    (cd "$ROOT/current/deploy" && $COMPOSE stop server) >> "$LOG" 2>&1
    status running restore "Restoring $FILE"
    if restore_from "$BACKUPS/$FILE"; then
      status running start "Starting the server"
      (cd "$ROOT/current/deploy" && $COMPOSE up -d server) >> "$LOG" 2>&1
      if server_up; then finish succeeded "Restored $FILE (the data before it is in $(basename "$SAFETY"))"; fi
      log "The server didn't start after the restore"
    else
      log "Restoring $FILE failed"
    fi
    status running rollback "That didn't work; putting the previous data back"
    (cd "$ROOT/current/deploy" && $COMPOSE stop server) >> "$LOG" 2>&1
    if restore_from "$SAFETY"; then
      (cd "$ROOT/current/deploy" && $COMPOSE up -d server) >> "$LOG" 2>&1
      server_up && finish failed "Restoring $FILE failed, so the data from before the restore was put back. Details in update/update.log."
    fi
    (cd "$ROOT/current/deploy" && $COMPOSE up -d server) >> "$LOG" 2>&1
    finish failed "Restoring $FILE failed and putting the previous data back failed too. The server needs a manual fix; the safety backup is $(basename "$SAFETY")."
    ;;
  update) ;;
  *) finish failed "Unknown action: $ACTION" ;;
esac

# --- Update --------------------------------------------------------------------------------------

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
BACKUP="$BACKUPS/pre-$TAG-$STAMP.dump"
dump_to "$BACKUP" || finish failed "The database backup failed; nothing was changed"

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
finish failed "$TAG didn't start and going back to $CURRENT didn't either. The server needs a manual fix (see the maintainers' notes)."
