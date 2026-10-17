#!/bin/sh
# Updater main loop (chunk 4.6). Every few seconds: mark itself alive, and when the server has
# written a request, run update.sh from the current release (read fresh each time, so a release
# can improve the procedure). One update at a time.
ROOT="${SIM_INSTALL_DIR:-/opt/simsensor}"
DIR="$ROOT/update"
mkdir -p "$DIR"
# The server runs as uid 10001 and writes the request file here.
chown 10001 "$DIR" 2>/dev/null
chmod 755 "$DIR"

status() { # state message
  jq -n --arg state "$1" --arg message "$2" --arg at "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
    '{state: $state, message: $message, finishedAt: $at}' > "$DIR/status.json.tmp" && mv "$DIR/status.json.tmp" "$DIR/status.json"
}

if [ ! -L "$ROOT/current" ] || [ ! -f "$ROOT/.env" ]; then
  status unavailable "This install doesn't use the updater layout ($ROOT/current link and $ROOT/.env). See the README."
fi
[ -f "$DIR/status.json" ] || status idle ""
# An update interrupted by a restart of the updater can't be trusted: say so.
if [ -f "$DIR/processing.json" ]; then
  status failed "The updater restarted during an update. Check the server version, then try again."
  rm -f "$DIR/processing.json"
fi

while true; do
  date -u +%s > "$DIR/heartbeat"
  if [ -f "$DIR/request.json" ] && [ -L "$ROOT/current" ] && [ -f "$ROOT/.env" ]; then
    sh "$ROOT/current/deploy/updater/update.sh" || true
  fi
  sleep 5
done
