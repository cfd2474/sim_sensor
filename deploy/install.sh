#!/usr/bin/env bash
# Sim Sensor guided installer (chunk 4.8).
#
#   curl -fsSL https://raw.githubusercontent.com/cfd2474/sim_sensor/main/deploy/install.sh -o install.sh
#   sudo bash install.sh                 # first install
#   sudo bash install.sh --reconfigure   # change the domain name or certificate email later
#
# Asks for the server's domain name and an email for its HTTPS certificate, writes the settings
# file itself (/opt/simsensor/.env: nothing to edit by hand), installs the newest version, waits
# until HTTPS works, and prints the first sign-in steps. Unattended: set SIM_DOMAIN and ACME_EMAIL
# (and SIM_ASSUME_YES=1) in the environment.
set -euo pipefail

REPO="${SIM_UPDATE_REPO:-cfd2474/sim_sensor}"
ROOT="${SIM_INSTALL_DIR:-/opt/simsensor}"
INPUT="${SIM_INPUT:-/dev/tty}"   # answers come from the terminal (also when piped from curl)
VERSION=""
RECONFIGURE=0

bold() { printf '\033[1m%s\033[0m\n' "$*"; }
say() { printf '%s\n' "$*"; }
ok() { printf '  \033[32m✓\033[0m %s\n' "$*"; }
warn() { printf '  \033[33m!\033[0m %s\n' "$*"; }
die() { printf '\n\033[31mStopped:\033[0m %s\n' "$*" >&2; exit 1; }

ask() { # prompt default -> REPLY
  local prompt="$1" default="${2:-}"
  if [ -n "$default" ]; then printf '%s [%s]: ' "$prompt" "$default"; else printf '%s: ' "$prompt"; fi
  IFS= read -r -u 3 REPLY || die "No answer (run it in a terminal, or set SIM_DOMAIN and ACME_EMAIL)."
  REPLY="$(printf '%s' "$REPLY" | tr -d '[:space:]')"
  [ -z "$REPLY" ] && REPLY="$default"
  return 0
}

confirm() { # prompt default(y|n)
  [ "${SIM_ASSUME_YES:-0}" = 1 ] && return 0
  local hint="[y/N]"; [ "$2" = y ] && hint="[Y/n]"
  printf '%s %s ' "$1" "$hint"
  IFS= read -r -u 3 REPLY || die "No answer."
  REPLY="$(printf '%s' "$REPLY" | tr '[:upper:]' '[:lower:]' | tr -d '[:space:]')"
  [ -z "$REPLY" ] && REPLY="$2"
  [ "$REPLY" = y ] || [ "$REPLY" = yes ]
}

# --- Answers and their checks ------------------------------------------------------------------

# A fully qualified domain name: labels of letters, digits and hyphens, at least one dot, a
# letters-only top level; no scheme, port or path.
valid_fqdn() {
  local d="$1"
  [ ${#d} -le 253 ] || return 1
  printf '%s' "$d" | grep -Eq '^([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]{2,63}$'
}

fqdn_problem() { # why an answer isn't a domain name, in plain words
  local d="$1"
  case "$d" in
    "") echo "Type the name, e.g. sim.agency.org." ;;
    *://*) echo "Leave out http:// or https://; just the name, e.g. sim.agency.org." ;;
    */*) echo "Leave out any path after the name; just the name, e.g. sim.agency.org." ;;
    *:*) echo "Leave out the port; Sim Sensor uses 80 and 443." ;;
    localhost | *.local | *.localhost) echo "This must be a public name that Let's Encrypt can reach, not a local one." ;;
    [0-9]*.[0-9]*.[0-9]*.[0-9]*) echo "Use a domain name, not an IP address: certificates are issued for names." ;;
    *.*) echo "Use letters, digits, hyphens and dots only, ending in a domain like .org or .com." ;;
    *) echo "That needs a dot and a domain ending, e.g. sim.agency.org (not just a host name)." ;;
  esac
}

valid_email() {
  printf '%s' "$1" | grep -Eq '^[A-Za-z0-9._%+-]+@([A-Za-z0-9-]+\.)+[A-Za-z]{2,63}$'
}

ask_domain() { # default
  local d="${SIM_DOMAIN:-}"
  if [ -n "$d" ]; then
    d="$(printf '%s' "$d" | tr '[:upper:]' '[:lower:]')"
    valid_fqdn "$d" || die "SIM_DOMAIN \"$d\": $(fqdn_problem "$d")"
    DOMAIN="$d"; return
  fi
  echo
  bold "1. The server's domain name"
  say "   The full name people will type to reach the console, e.g. sim.agency.org. Its DNS record"
  say "   (A or AAAA) must already point at this server: Let's Encrypt checks it before issuing the"
  say "   HTTPS certificate."
  while true; do
    ask "   Domain name" "${1:-}"
    d="$(printf '%s' "$REPLY" | tr '[:upper:]' '[:lower:]')"
    d="${d%.}"
    if valid_fqdn "$d"; then DOMAIN="$d"; break; fi
    warn "\"$REPLY\" isn't a domain name. $(fqdn_problem "$d")"
  done
}

ask_email() { # default
  local e="${ACME_EMAIL:-}"
  if [ -n "$e" ]; then
    valid_email "$e" || die "ACME_EMAIL \"$e\" isn't an email address."
    EMAIL="$e"; return
  fi
  echo
  bold "2. Email for the HTTPS certificate"
  say "   Registered with Let's Encrypt for this server's certificate (their account contact)."
  say "   Caddy renews the certificate by itself; Sim Sensor also checks it every day and warns the"
  say "   admins in the console (and by email, once set up) if renewal ever fails."
  while true; do
    ask "   Email" "${1:-}"
    if valid_email "$REPLY"; then EMAIL="$REPLY"; break; fi
    warn "\"$REPLY\" isn't an email address (e.g. it-admin@agency.org)."
  done
}

check_dns() { # warn when the name doesn't point here yet
  local resolved public
  resolved="$(getent ahosts "$DOMAIN" 2>/dev/null | awk '{print $1}' | sort -u | tr '\n' ' ' || true)"
  public="$(curl -fsS -m 10 https://checkip.amazonaws.com 2>/dev/null | tr -d '[:space:]' || true)"
  if [ -z "$resolved" ]; then
    warn "$DOMAIN doesn't resolve yet. Create its DNS record pointing at ${public:-this server} first;"
    warn "until then Let's Encrypt can't issue the certificate."
    confirm "   Continue anyway?" n || die "Set up the DNS record, then run this again."
  elif [ -n "$public" ] && ! printf '%s' " $resolved " | grep -q " $public "; then
    warn "$DOMAIN points at $resolved, but this server's public address is $public."
    confirm "   Continue anyway (e.g. behind a NAT or proxy you control)?" n || die "Fix the DNS record, then run this again."
  else
    ok "$DOMAIN points at this server (${public:-$resolved})"
  fi
}

# --- System checks -----------------------------------------------------------------------------

require_root() { [ "$(id -u)" = 0 ] || die "Run it as root: sudo bash install.sh"; }

require_tools() {
  for t in curl tar; do command -v "$t" >/dev/null || die "Install $t first (e.g. apt-get install -y $t)."; done
  if ! command -v docker >/dev/null; then
    warn "Docker isn't installed."
    if confirm "   Install Docker now with Docker's official script (get.docker.com)?" y; then
      curl -fsSL https://get.docker.com | sh || die "Installing Docker failed."
    else
      die "Install Docker (with the Compose plugin), then run this again."
    fi
  fi
  docker compose version >/dev/null 2>&1 || die "Docker Compose (the 'docker compose' plugin) is missing. Install docker-compose-plugin."
  ok "Docker $(docker version --format '{{.Server.Version}}' 2>/dev/null || echo '') with Compose $(docker compose version --short 2>/dev/null)"
}

check_ports() {
  command -v ss >/dev/null || return 0
  local busy
  busy="$(ss -ltnH '( sport = :80 or sport = :443 )' 2>/dev/null | wc -l)"
  if [ "$busy" -gt 0 ]; then
    warn "Something already listens on port 80 or 443 (another web server?). Sim Sensor needs both."
    confirm "   Continue anyway?" n || die "Free ports 80 and 443, then run this again."
  else
    ok "Ports 80 and 443 are free"
  fi
  if command -v ufw >/dev/null && ufw status 2>/dev/null | grep -q "Status: active"; then
    say "   Note: Docker publishes ports past ufw, but your cloud provider's firewall must allow 80 and 443."
  fi
}

newest_version() {
  curl -fsS "https://api.github.com/repos/$REPO/tags?per_page=100" \
    | grep -o '"name": *"v[0-9]*\.[0-9]*\.[0-9]*"' | grep -o 'v[0-9.]*' | sort -V | tail -1
}

# --- Settings file -----------------------------------------------------------------------------

random_secret() { head -c 48 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | head -c 32; }

write_env() { # domain email
  local env="$ROOT/.env"
  if [ -f "$env" ]; then
    # Keep everything (the database password above all); change only the two answers.
    sed -i -e "s|^SIM_DOMAIN=.*|SIM_DOMAIN=$1|" -e "s|^ACME_EMAIL=.*|ACME_EMAIL=$2|" "$env"
    grep -q '^SIM_DOMAIN=' "$env" || echo "SIM_DOMAIN=$1" >> "$env"
    grep -q '^ACME_EMAIL=' "$env" || echo "ACME_EMAIL=$2" >> "$env"
  else
    umask 077
    cat > "$env" <<EOF
# Written by install.sh. You don't need to edit this file: run
#   sudo bash $ROOT/current/deploy/install.sh --reconfigure
# to change the domain name or certificate email.
SIM_DOMAIN=$1
ACME_EMAIL=$2
POSTGRES_PASSWORD=$(random_secret)
SIM_COOKIE_SECURE=true
EOF
  fi
  chmod 600 "$env"
  ok "Settings saved in $env"
}

env_value() { grep -E "^$1=" "$ROOT/.env" 2>/dev/null | head -1 | cut -d= -f2- || true; }

# --- Install and wait ----------------------------------------------------------------------------

download() { # tag
  local dir="$ROOT/releases/$1"
  if [ ! -f "$dir/deploy/docker-compose.yml" ]; then
    rm -rf "$dir.tmp" && mkdir -p "$dir.tmp"
    curl -fsSL "https://codeload.github.com/$REPO/tar.gz/refs/tags/$1" | tar -xz --strip-components=1 -C "$dir.tmp" \
      || die "Downloading $1 failed."
    rm -rf "$dir" && mv "$dir.tmp" "$dir"
  fi
  ln -sfn "$dir" "$ROOT/current"
  ok "Sim Sensor $1 in $dir"
}

phone_app() { # tag: the phone app on this server's download link (https://<name>/download/simsensor.apk)
  local fetch="$ROOT/current/deploy/updater/fetch-app.sh"
  [ -f "$fetch" ] || return 0
  SIM_INSTALL_DIR="$ROOT" SIM_UPDATE_REPO="$REPO" sh "$fetch" "$1" | sed 's/^fetch-app: /   /'
}

start_stack() { # extra compose args
  say "   Building and starting (the first build takes several minutes)…"
  (cd "$ROOT/current/deploy" && docker compose --env-file "$ROOT/.env" up -d "$@") || die "Starting the services failed (see the messages above)."
  ok "Services started"
}

wait_for_https() {
  say "   Waiting for https://$DOMAIN to answer with a valid certificate (usually under a minute)…"
  local i=0
  while [ $i -lt 120 ]; do # 10 minutes
    if curl -fsS -m 10 "https://$DOMAIN/health" >/dev/null 2>&1; then
      ok "https://$DOMAIN answers with a valid certificate"
      return 0
    fi
    if [ $i -eq 36 ]; then
      warn "Still waiting after 3 minutes. The certificate log says:"
      (cd "$ROOT/current/deploy" && docker compose --env-file "$ROOT/.env" logs --tail 200 proxy 2>/dev/null \
        | grep -iE 'error|challenge|obtain' | tail -5 | sed 's/^/     /') || true
      say "   Common causes: the DNS record doesn't point here yet, or a firewall blocks port 80 or 443."
    fi
    sleep 5
    i=$((i + 1))
  done
  warn "https://$DOMAIN didn't come up within 10 minutes. Sim Sensor keeps trying to get the certificate by"
  warn "itself; check the DNS record and ports 80 and 443. Logs: cd $ROOT/current/deploy && docker compose --env-file $ROOT/.env logs proxy"
  return 1
}

finish() {
  echo
  bold "Sim Sensor is running at https://$DOMAIN"
  echo
  say "First sign-in (the README explains it in full):"
  say "  1. Sign in as admin@simsensor.local, password SimSensor-Admin1 (published in the README)."
  say "  2. Settings → Instructors: add an admin account with your own email and password."
  say "  3. Log out, sign in with your own account, and deactivate admin@simsensor.local."
  echo
  say "Certificates: Caddy renews the HTTPS certificate automatically; Sim Sensor checks it daily and"
  say "warns admins in the console if renewal fails. Nothing to do."
  say "Updates: Settings → Server update in the console."
  say "Change the domain name or certificate email: sudo bash $ROOT/current/deploy/install.sh --reconfigure"
}

# --- Main --------------------------------------------------------------------------------------

while [ $# -gt 0 ]; do
  case "$1" in
    --reconfigure) RECONFIGURE=1 ;;
    --version) VERSION="${2:-}"; shift ;;
    -h | --help) sed -n '2,12p' "$0"; exit 0 ;;
    *) die "Unknown option $1 (try --help)" ;;
  esac
  shift
done

echo
bold "Sim Sensor installer"
require_root
# Answers are read from one open stream (the terminal, also when this script is piped from curl).
if { true < "$INPUT"; } 2>/dev/null; then
  exec 3< "$INPUT"
else
  [ -n "${SIM_DOMAIN:-}" ] && [ -n "${ACME_EMAIL:-}" ] || die "No terminal to ask in: set SIM_DOMAIN and ACME_EMAIL."
  exec 3< /dev/null
fi

if [ "$RECONFIGURE" = 1 ]; then
  [ -f "$ROOT/.env" ] && [ -L "$ROOT/current" ] || die "No Sim Sensor install in $ROOT to reconfigure."
  say "Press Enter to keep a value."
  ask_domain "$(env_value SIM_DOMAIN)"
  ask_email "$(env_value ACME_EMAIL)"
  check_dns
  echo
  confirm "Use https://$DOMAIN with certificate email $EMAIL?" y || die "Nothing changed."
  write_env "$DOMAIN" "$EMAIL"
  start_stack proxy server
  wait_for_https || true
  finish
  exit 0
fi

if [ -f "$ROOT/.env" ]; then
  say "Sim Sensor is already installed here ($(basename "$(readlink "$ROOT/current" 2>/dev/null || echo '?')"))."
  say "Updates: Settings → Server update in the console."
  say "Change the domain name or certificate email: sudo bash $0 --reconfigure"
  exit 0
fi

require_tools
check_ports
[ -n "$VERSION" ] || VERSION="$(newest_version)"
printf '%s' "$VERSION" | grep -Eq '^v[0-9]+\.[0-9]+\.[0-9]+$' || die "Couldn't find the newest version on GitHub ($REPO)."
ok "Newest version: $VERSION"

ask_domain ""
ask_email ""
check_dns
echo
bold "Ready to install"
say "   Version:            $VERSION"
say "   Address:            https://$DOMAIN"
say "   Certificate email:  $EMAIL"
say "   Install folder:     $ROOT"
confirm "Install now?" y || die "Nothing was installed."

mkdir -p "$ROOT/releases"
write_env "$DOMAIN" "$EMAIL"
download "$VERSION"
phone_app "$VERSION"
start_stack --build
wait_for_https || true
finish
