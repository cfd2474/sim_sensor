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

## Versioning

`VERSION` is the product version. Every product-changing push bumps it, tags the commit
`vX.Y.Z`, and adds a section to `docs/RELEASE_NOTES.md`. See `CLAUDE.md` §9.
