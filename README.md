# Sim Sensor

Hazmat gas detector simulator for training. Android phones act as simulated 5-gas meters
(O₂, LEL, CO, H₂S, HCN). Instructors drive the readings from a web console, directly, from
map-based virtual leaks, or from Bluetooth beacons.

> **SIMULATOR — TRAINING ONLY.** Never use this app to detect real hazards.

See [docs/DESIGN.md](docs/DESIGN.md) for the design, and [PROJECT_STATE.md](PROJECT_STATE.md)
for where the work stands.

## Layout

| Path | What |
|---|---|
| `android/` | Android app (Kotlin, Jetpack Compose) |
| `shared/` | Kotlin Multiplatform: protocol, gas model (JVM + JS) |
| `server/` | Ktor server; also serves the console |
| `console/` | Instructor web console (React + TypeScript + Vite) |
| `deploy/` | Docker Compose stack (Caddy, server, PostgreSQL/TimescaleDB, Redis) |
| `docs/` | Design, protocol, platform reference, release notes, remote server |

## Build

Requirements: JDK 17, Android SDK (path in `local.properties` as `sdk.dir=...`), Node 22+, Docker.

```bash
./gradlew check                    # all tests + release guards (VERSION == newest tag, release notes)
./gradlew :android:assembleDebug   # APK at android/build/outputs/apk/debug/
./gradlew :server:run              # server on http://localhost:8080
cd console && npm ci && npm run dev  # console on http://localhost:5173, proxied to :8080
```

Server-only builds (no Android SDK): set `SIMSENSOR_SKIP_ANDROID=1`.

## Run the full stack locally

```bash
cd deploy
cp .env.example .env    # set SIM_DOMAIN=localhost and a POSTGRES_PASSWORD
docker compose up -d --build
# https://localhost  (Caddy's local certificate; accept it in the browser)
```

## Install on a server

Needs a Linux server with Docker (with Compose), a DNS name pointing at it, and ports 80 and 443
open. Install into `/opt/simsensor` in this layout, so that later updates can be done from the
console:

```bash
sudo -i
TAG=v0.26.0                      # the newest version tag on GitHub
mkdir -p /opt/simsensor/releases/$TAG && cd /opt/simsensor
curl -fsSL https://codeload.github.com/cfd2474/sim_sensor/tar.gz/refs/tags/$TAG \
  | tar -xz --strip-components=1 -C releases/$TAG
ln -sfn /opt/simsensor/releases/$TAG current
cp current/deploy/.env.example .env   # set SIM_DOMAIN to your DNS name and a strong POSTGRES_PASSWORD
cd current/deploy && docker compose --env-file /opt/simsensor/.env up -d --build
```

The first build takes several minutes. Then open `https://<your DNS name>` and sign in for the
first time (next section).

## Updating the server

**Settings → Server update** (admins) shows the running version, the newest one on GitHub and
what's new, with an **Update** button. The update backs up the database, downloads and builds the
new version, restarts the server (about a minute offline; phones reconnect by themselves), and
goes back to the previous version by itself if the new one doesn't start.

This is done by the stack's `updater` service, the only part with access to Docker. It has no
network port and only acts on requests from the server. It doesn't update itself; if a release
notes a change to the updater, rerun the last command above (`docker compose … up -d --build`) in
`/opt/simsensor/current/deploy`.

## First sign-in: replace the default admin account

A fresh install, one with no accounts yet, starts with one **default admin account**:

| | |
|---|---|
| Email | `admin@simsensor.local` |
| Password | `SimSensor-Admin1` |

> ⚠️ **This password is public: it is printed here.** Anyone who can reach your server can sign
> in with it until you deactivate the account. Do the steps below **straight after installing**.
> Until then, the console shows an orange warning on every page.

1. Sign in with the default account above.
2. Go to **Settings → Instructors** and **add an admin account with your own email address and
   your own password** (at least 8 characters). Choose the role **Admin**.
3. **Log out**, then **sign in with your new account**.
4. In **Settings → Instructors**, find `admin@simsensor.local` and **deactivate** it.
   (An account can't deactivate itself, which is why you sign in as yourself first.)

When the orange warning disappears, the default account can no longer be used.

The default account is created only when the database has no accounts at all. Existing installs
and upgrades are never affected. To skip it, set `SIM_ADMIN_EMAIL` and `SIM_ADMIN_PASSWORD` in
`deploy/.env` before the first start; that account is then created instead.

## Versioning

`VERSION` is the product version. Every product-changing push bumps it, tags the commit
`vX.Y.Z`, and adds a section to `docs/RELEASE_NOTES.md`. See `CLAUDE.md` §9.
