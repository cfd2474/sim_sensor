# Sim Sensor — Product & System Design

**Status:** Draft v0.4 (2026-10-14). Monitors per phone replace per-exercise alarm criteria (§7).
**Repo:** monorepo holding the Android app, the server, and the instructor console
**Host:** a cloud VM running Ubuntu with Docker (any Linux server with Docker works)

---

## 1. Purpose

Sim Sensor turns agency Android phones into **simulated 5-gas hazmat detectors** for training.
Trainees carry a phone that looks and behaves like a multi-gas meter. Instructors control what
each phone reads, either:

1. **Directly**: set or ramp a value on one device, a group, or everyone in an exercise.
2. **By location**: place a virtual leak on a map. Readings rise as a trainee's GPS position
   gets closer to it.
3. **By proximity to a beacon**: put a cheap Bluetooth LE beacon somewhere (a drum, a room,
   a vehicle). The phone raises its own readings as it gets closer, with no server
   round-trip. This works indoors and when the network drops briefly.

Sensor configurations (gases, units, ranges, alarm set points) are managed centrally on the
server and pushed to the devices.

> **Safety requirement:** the device UI must always show a visible "SIMULATOR — TRAINING ONLY"
> marking. A phone that looks like a real detector must never be mistaken for one during a
> real incident.

### 1.1 Requirements from the operator (v0.1 answers)

| # | Requirement |
|---|---|
| R1 | 5-gas detector: **O₂, LEL, CO, H₂S, HCN** |
| R2 | Dedicated agency phones running a normal app. **No kiosk mode.** |
| R3 | **No limit** on how many devices connect at once |
| R4 | Cellular and Wi-Fi available at training sites (online-first) |
| R5 | Server stack chosen for the easiest change and development, and the widest compatibility. Runs on Ubuntu 22.04 |
| R6 | A public DNS name (A record) pointing at a cloud VM |
| R7 | **Several exercises running at the same time** |
| R8 | No beacons owned yet. Recommend hardware (§8.2) |
| R9 | Distributed by **sideloaded APK and MDM** |

---

## 2. Main concepts

| Term | Meaning |
|---|---|
| **Device** | A phone running the Sim Sensor APK, enrolled with the server. |
| **Sensor profile** | A named instrument definition (default: "5-Gas"): a list of channels plus display and alarm behavior. Assigned to devices. |
| **Channel** | One gas sensor in a profile: gas, unit, range, resolution, baseline, alarm thresholds, response time. |
| **Exercise** | A training scenario owned by one or more instructors: a map area, a set of sources, wind, timed events, and the devices assigned to it. Several can run at once. |
| **Source** | Something that produces a gas concentration: a **GPS source** (point, plume or zone on the map) or a **beacon source** (tied to a physical BLE beacon). |
| **Override** | An instructor command that replaces or modifies a device's computed reading (fixed value, ramp, offset, fault). |
| **Session** | One run of an exercise. Telemetry and events are recorded against it for after-action review. |

---

## 3. Architecture

```
                       ┌──────────────── simsensor.tak-solutions.com ────────────────┐
                       │                                                              │
 Instructor browser    │   Reverse proxy (TLS)                                        │
 (laptop or tablet) ───┼──▶ ──────────────┬──────────────────────────────┐            │
                       │                  ▼                              ▼            │
                       │  ┌──────────────────────┐        ┌──────────────────────┐    │
                       │  │ Server instance 1    │  ...   │ Server instance N    │    │
                       │  │ REST API + console   │        │ (add for scale)      │    │
                       │  │ WebSocket hub        │        │                      │    │
                       │  └──────┬─────────┬─────┘        └─────┬──────────┬─────┘    │
                       │         │         │  pub/sub fan-out   │          │          │
                       │         │         └────────▶ Redis ◀───┘          │          │
                       │         └──────────────▶ PostgreSQL ◀─────────────┘          │
                       │                         (+ TimescaleDB for telemetry)        │
                       └──────────────────────────────────────────────────────────────┘
                                                  ▲ WSS (JSON)
                    ┌─────────────────────────────┼──────────────────────┐
             ┌──────┴──────┐               ┌──────┴──────┐        ┌──────┴──────┐
             │ Phone (APK) │               │ Phone (APK) │  ...   │ Phone (APK) │
             │ GPS + BLE   │               └─────────────┘        └─────────────┘
             └──────┬──────┘
                    │ BLE advertisements (one-way, phone only listens)
              ┌─────┴─────┐
              │  Beacon   │
              └───────────┘
```

### 3.1 Readings are computed on the device

The server **does not** compute readings and stream them to phones. It sends each device its
**sensor profile + its exercise's state** (sources, wind, beacon assignments, overrides). The
phone runs the concentration model itself, using its own GPS and BLE scans, and reports back
what it is displaying.

Why:
- **Fast response.** The phone updates at screen rate with no network round-trip, which
  beacons need.
- **Works through brief network drops.** The phone keeps reading correctly from the last
  exercise state it received, queues telemetry, and uploads it on reconnect.
- **Scales without a limit (R3).** Server work per device is tiny (receive about 1 small
  message per second, occasionally send state). No physics runs on the server per device.

### 3.2 Built to scale out (R3, R7)

"No limit" in practice means **no limit set in the software**. Capacity grows by adding
hardware, without code changes:
- **Stateless server instances.** Any instance can serve any device or console. WebSocket
  messages for an exercise are fanned out between instances through **Redis pub/sub**,
  partitioned by exercise ID. One instance is enough at first; more can be added behind the
  proxy later.
- **Telemetry writes are batched** (buffered per instance, flushed every ~1 s with a bulk
  insert) into a **TimescaleDB hypertable**. That is ordinary PostgreSQL with automatic time
  partitioning and compression, so millions of rows stay fast to query and cheap to keep.
- **Adaptive telemetry rate.** 1 Hz in an active exercise, 0.1 Hz idle. The server can lower
  the rate for large exercises (`setTelemetryRate` command).
- **Load test** as part of Phase 1: a simulated-device generator that opens thousands of
  WebSocket connections, so actual capacity on the host is measured, not guessed.

Rough sizing: 1,000 devices × 1 msg/s ≈ 1,000 small JSON messages per second plus one bulk
insert per second. A single 2–4 vCPU instance should handle this comfortably. The load test
confirms it.

### 3.3 Multiple concurrent exercises (R7)

- Each exercise is isolated: its own sources, wind, clock, overrides, sessions, and assigned
  devices.
- A device belongs to **at most one active exercise at a time**. An instructor assigns
  devices by selecting them, by group, or by having trainees scan an exercise join-QR code.
- An exercise has one or more instructors. An instructor sees and controls only their
  exercises. Admins see all.
- Realtime channels are per exercise, so traffic for exercise A never reaches consoles or
  devices in exercise B.
- An unassigned device shows a "Standby — not in an exercise" screen with baseline readings.

---

## 4. Technology choices (decided)

Selection criteria from R5: easiest to change and develop, and widest compatibility.

| Layer | Choice | Why it fits R5 |
|---|---|---|
| Android app | Kotlin, Jetpack Compose, minSdk 26 (Android 8), target latest stable. **No Google Play services dependency** (operator requirement, 2026-10-14) | Google's primary Android stack. Works on phones with or without Play services. |
| Shared logic | **Kotlin Multiplatform** module `shared/`, compiled for Android, JVM (server) and JavaScript (console) | The gas model, alarm logic, and protocol are written **once**. A change to a message or formula is checked by the compiler in all three apps, so they can't drift apart. |
| Server | Kotlin + **Ktor**, kotlinx.serialization, plain JDBC + HikariCP, Flyway migrations (SQL is the single schema definition; decided 2026-10-14, replacing Exposed) | Same language and types as the app. A mature JVM ecosystem. Runs anywhere Docker runs. |
| API contract | REST described by an **OpenAPI** spec; WebSocket protocol documented in `docs/PROTOCOL.md` | Any outside tool or future client, in any language, can integrate. |
| Database | PostgreSQL 16 + TimescaleDB extension | Standard SQL plus time-series performance for telemetry. |
| Fan-out | Redis 7 | Industry standard for pub/sub between server instances. |
| Instructor console | React + TypeScript + Vite, MapLibre GL, imports `shared/` as a JS library | The most widely used web UI stack. Map previews use the same model code as the phones. |
| Maps | OpenStreetMap standard tiles for now (operator decision, 2026-10-14). The OSM tile usage policy forbids heavy use, so switch to a provider or self-hosted tiles before wide rollout. Tile URL is configuration, not code | No vendor lock-in. |
| Deployment | Docker Compose on Ubuntu 22.04 | The whole stack installs, upgrades and rolls back with one command. Nothing installed on the host except Docker. |

---

## 5. Repository layout

```
sim_sensor/
├── android/              # Android app (Gradle module :android)
├── shared/               # Kotlin Multiplatform: protocol DTOs, gas model, alarm logic
├── server/               # Ktor server (Gradle module :server)
├── console/              # Instructor web UI (React/TS, npm); build output served by server
├── deploy/               # docker-compose.yml, proxy config, .env.example, backup scripts
├── tools/loadtest/       # simulated-device generator
├── docs/                 # Design, protocol, platform reference, remote server, release notes
├── VERSION               # product version (see CLAUDE.md §9)
├── settings.gradle.kts   # includes :shared, :android, :server
└── README.md
```

CI with GitHub Actions on `github.com/cfd2474/sim_sensor`:
- Build the APK.
- Run tests for the shared model, the server, and the version/release-notes checks.
- Build the console.
- Build the server Docker image.

---

## 6. Android app

### 6.1 Screens

1. **Enrollment.** Scan a QR code shown in the console (server URL + one-time token) with the
   in-app scanner **or the phone's own camera app** (the `simsensor://enroll` link opens the app,
   which asks for confirmation), **or enroll with no taps through MDM managed configuration**
   (§11.2). The device receives a
   permanent device token. The instructor names it (for example "Entry 1").
2. **Detector screen** (main):
   - Five channel tiles: **O₂ · LEL · CO · H₂S · HCN**. Each shows gas name, value and unit,
     with a color for its alarm state.
   - Alarm states: normal → low → high → STEL / TWA → over-range ("OR") → sensor fault. Alarm
     states flash and play a loud tone, with vibration and flashlight strobe (each
     configurable).
   - Buttons: Acknowledge / Silence, Peak view, (mock) Zero / Bump test, Backlight.
   - Status bar: connection indicator, simulated battery, clock, exercise name, and the
     **SIMULATOR** watermark.
   - A generic multi-gas meter LCD look. No vendor branding or trade dress is copied.
3. **Standby screen** when the device is not assigned to an exercise.
4. **Settings and Diagnostics** behind an instructor PIN set by the server: GPS accuracy,
   beacons in range (RSSI and estimated distance), last sync, profile and exercise versions.

### 6.2 Target hardware and no Play services

- **Reference phone: Samsung Galaxy XCover6 Pro** (rugged; shipped with Android 12). All
  real-device testing happens on it, and findings go in `docs/ANDROID_PLATFORM_REFERENCE.md`.
- **No Google Play services.** No FusedLocationProvider, no ML Kit, no Firebase. QR scanning
  uses CameraX + the open-source ZXing decoder. Push to devices is only over our own
  WebSocket.
- Samsung adds its own battery management ("sleeping apps") on top of Android's. The app must
  be excluded through MDM policy or settings. To be verified on the XCover6 Pro.
- Idea, unverified: the XCover6 Pro has a programmable side key that could act as a physical
  "acknowledge alarm" button. To be checked on the device.

### 6.3 Normal app, no kiosk mode (R2)
- Runs as a regular launcher app. Trainees can switch apps, and the simulator **keeps
  working in the background**.
- A **foreground service** (types `location` + `connectedDevice`) runs GPS, BLE scanning, the
  model loop, and the WebSocket connection. It shows an ongoing notification with the worst
  current alarm state.
- **Alarms sound even when the app isn't on screen.** An alarm while in the background raises
  a high-priority notification and plays the alarm tone. Tapping the notification returns to
  the detector screen.
- The screen never times out while the app is in the foreground (operator requirement,
  2026-10-14; `FLAG_KEEP_SCREEN_ON` on the activity window). In the background the normal
  screen timeout applies.
- Location from the platform `LocationManager`, **not** Google's FusedLocationProvider (no Play
  services). Use `GPS_PROVIDER`, and `FUSED_PROVIDER` (AOSP, Android 12+) when present. 1 Hz while an exercise is active and
  slower when idle.
- BLE scanning runs continuously in low-latency mode while the exercise uses beacons. (Android
  throttles apps that restart scans more than 5 times in 30 seconds, so the app keeps one scan
  running instead of restarting it.)
- Permissions: fine location (**no background location**: the location foreground service keeps
  access while backgrounded; see platform reference §1), POST_NOTIFICATIONS (Android 13+), CAMERA
  (QR scan), and BLUETOOTH_SCAN in Phase 3. Through MDM these can be **granted in advance by
  policy**, so trainees never see prompts.
- The service must be started while the app is visible (Android 12+ background-start rule). After
  a reboot or app kill, the trainee opens the app once to resume.
- Battery optimization exemption is requested, or granted by MDM, so the OS doesn't kill the
  service.

### 6.4 Network drops
- The app keeps the last profile and exercise state in local storage and keeps computing from them.
- Every telemetry sample goes through a local SQLite queue (plain `SQLiteOpenHelper`, no Room)
  and is uploaded in batches whenever connected, so nothing is lost during a drop.
- Reconnects with exponential backoff and jitter. This prevents thousands of phones from all
  reconnecting at the same instant after a server restart.
- Device clock offset is corrected at connect, so timed events fire at the same moment on
  every phone.

---

## 7. Monitors (server-managed)

A **monitor** is the gas meter a phone imitates, set up like a meter the agency really owns (for
example a Dräger X-am 2800 or a Blackline G8, as the agency has configured it). Admins keep the
monitors under Settings → Monitors. In storage and in the API a monitor is a **profile**
(`sensor_profiles`, `/api/profiles`); the console calls it a monitor.

A monitor is versioned. Editing it pushes the new version to every phone that uses it.

**Monitor fields:** name, make and model (free text), screen, channels[].

**Screens (phone):** `generic` (Sim Sensor's own), `g8` (tiles in two columns, green → flashing
yellow for A1 → flashing red for A2, header with name, time and date; like a Blackline G8) and
`xam` (grey LCD with a row per gas, value alternating with "A1"/"A2" in alarm, LED flashing once
for A1 and twice for A2, blue key = peak, green OK = acknowledge; like a Dräger X-am) and `io6`
(v0.29.0: grey LCD under a status bar with signal, GPS, wearer, time and phone battery; gas cells
in two columns, three rows for six gases, label top left, large value, unit bottom right, VOC with
"Isobutylene"; light bar on top; three keys; like an MSA ALTAIR io 6 from the operator's photo.
Assumed, no manual found: a cell in alarm inverts and flashes, once a second for A1 and twice for
A2, with LOW / HIGH / STEL / TWA in place of its unit, the light bar flashes red, centre key =
OK / acknowledge, chevrons = peak). Screens
imitate layout, colours and behaviour only: **no manufacturer logos or names** on the phone, and
the "SIMULATOR — TRAINING ONLY" banners stay. Readings, alarms and sound are the same code for
every screen.

**Sensor catalog** (`shared/.../profile/SensorCatalog.kt`, `GET /api/sensors`): a monitor's
sensors are picked from O₂, LEL, CO, H₂S, HCN, NH₃, SO₂, Cl₂, HF, CO₂ and VOC (a PID read as
isobutylene: 0–2000 ppm at 0.1 from the ALTAIR io 6 page; A1 50, A2 100, STEL 25, TWA 10 ppm are
common isobutylene presets, assumed, as no maker publishes defaults). The catalog fixes each
sensor's id, label, unit and alarm direction, and gives starting values for the rest (new
sensors: Industrial Scientific default alarm settings, i.e. low = OSHA PEL / NIOSH, high =
2 × PEL; HF by the same rule on its 3 ppm PEL). The server normalises monitors to the catalog on
save; leaks may release any catalog gas.

**Channel fields:** gas, label, unit, range, resolution, baseline, A1 (low) / A2 (high) alarm,
STEL / TWA alarm, A1 direction (falling for O₂), latching per alarm (A1 clears itself by default;
A2, STEL and TWA latch), response time T90, noise.

### 7.1 Default "5-Gas" profile (R1)

| Channel | Unit | Range | Resolution | Baseline | Low | High | STEL | TWA | T90 |
|---|---|---|---|---|---|---|---|---|---|
| O₂ | %vol | 0–30 | 0.1 | 20.9 | 19.5 ↓ | 23.5 ↑ | — | — | 5 s |
| LEL | %LEL | 0–100 | 1 | 0 | 10 | 20 | — | — | 5 s |
| CO | ppm | 0–1000 | 1 | 0 | 35 | 200 | 400 | 35 | 5 s |
| H₂S | ppm | 0–200 | 0.1 | 0 | 10 | 15 | 15 | 10 | 5 s |
| HCN | ppm | 0–30 | 0.1 | 0 | 4.7 | 10 | 4.7 | 4.7 | 5 s |

> The built-in monitor is a **starting point only**. Admins copy it into the agency's real
> models and edit every value.

### 7.2 Monitors in an exercise

Alarm levels come from the monitor, the way they are set on the real devices, not from the
exercise. (Per-exercise alarm criteria existed until v0.10; retired in v0.11.)

- **Each phone in an exercise imitates one monitor**, chosen on the exercise's Devices tab (one
  phone or all at once). A phone without its own uses the exercise's **default monitor**
  (Settings tab). Moving a phone to another exercise clears its choice.
- Each phone receives **its own** monitor in its exercise snapshot. Changing a phone's monitor,
  the default monitor, or a monitor's settings reaches the phones within about 1 second and is
  recorded in the event log.
- Phones in one exercise may have different gas sets. A leak may release any gas some monitor
  knows; a phone whose monitor lacks that gas doesn't show it, like the real device. Instructor
  overrides and the map preview accept any gas of the exercise's monitors.
- **Validation:** A2 at or above A1 (O₂: A1 below clean air, A2 above), set points within the
  channel's range. A monitor in use can't be deleted.

**O₂ displacement (optional, per exercise):** every 10,000 ppm (1 %vol) of total other gas
lowers O₂ by about 0.21 %vol.

---

## 8. Reading model (runs in `shared/`)

For each channel, about 4 times per second:

```
raw      = baseline
         + Σ GPS-source contributions(gas, devicePosition, t)
         + Σ beacon-source contributions(gas, estimatedDistance, t)
         ± cross-sensitivity terms
apply override:   LIVE | FIXED v | RAMP to v over s | OFFSET +v | FAULT
sensor lag:       first-order filter, τ = T90 / 2.3     (readings rise and fall like a real sensor)
noise:            small random jitter, scaled to resolution
clamp & display:  < min → 0 ; > max → "OR" ; round to resolution
alarms:           low/high/STEL (15-min avg)/TWA (8-h avg), latch per profile
```

### 8.1 GPS sources

All source types share: gas, **peak concentration**, **activation time** (immediately, at
T+mm:ss, or manual), **ramp-up duration**, optional **growth** (radius grows N m/min), and
**stop / decay**. A source can emit **several gases** (for example a fire with CO + HCN, each
with its own peak).

| Type | Shape | Instructor sets |
|---|---|---|
| **Point** | Concentration falls off with distance | center, radius (reading reaches 0 at the edge), falloff (linear / smooth / steep) |
| **Plume** | Teardrop-shaped area stretched downwind | center, length, width, wind direction (from exercise wind or per source) |
| **Zone** | Polygon, uniform inside, soft edge | polygon, edge width |

**Exercise clock (Phase 2).** Source timing ("start at T+5:00", ramp, growth, stop) runs on
exercise time: it starts with the run, freezes while paused, and stops at end or reset. The
snapshot sends elapsed time and whether the clock runs, so every phone agrees without
streaming time.

**Where it's computed.** Each phone evaluates all active sources at its own GPS position
several times a second (shared Kotlin code). The server uses the same code to draw a preview
grid on the instructor's map. Readings never wait for the network.

The plume is a **simplified training model**, not a dispersion model like ALOHA. Instructors
can drag sources, rotate the wind, and change peaks live, and every device in that exercise
picks up the change within about 1 second.

**GPS accuracy:** consumer GPS is accurate to about 3–10 m outdoors and is poor indoors. The
console warns when a GPS source radius is under about 25 m. Short-range interactions should use
beacons.

### 8.2 Beacon sources

**Recommended hardware (none owned yet, R8).** Buy beacons that meet all of these:
- Advertise **iBeacon** (and ideally also **Eddystone-UID**)
- Nordic **nRF52**-series chip (common in reliable beacons)
- **Transmit power and advertising interval adjustable** from the vendor's phone app
- **Replaceable coin-cell battery** (CR2032 / CR2477) or USB-rechargeable
- **Rugged / IP-rated case** with a way to mount it (adhesive, lanyard hole, magnet)

Candidate models to evaluate. Check current availability and price before buying:
MOKOSmart H2/H4, Minew E8 / i3, Blue Charm BC011 / BC021, Feasycom FSC-BP103/104.
**Start by buying a pack of 3–5 of one or two models** to test in Phase 3. Pick the one that
gives the steadiest signal on the agency's phones, then buy in volume.

Suggested settings: 100–200 ms advertising interval, medium transmit power (about 0 dBm, which
gives roughly 10–30 m of usable range).

**First beacon (operator, ordered 2026-10-15): Feasycom FSC-BP108B** (Dialog DA14531, BLE 5.1,
IP67, replaceable battery). Vendor data (https://www.feasycom.com/product/fsc-bp108b/): iBeacon,
Eddystone (URL / UID / TLM) and AltBeacon, up to 10 advertising slots; advertising interval
**1300 ms by default**, 100–10 000 ms adjustable; configured with Feasycom's app, **PIN 000000 by
default**; battery life quoted at 1300 ms / 0 dBm; "up to 400 m" in open air at full power.
Default UUID / major / minor not published (read them from a phone scan). Setup for training:
iBeacon slot on, interval 100–200 ms (at 1300 ms a phone hears too few packets to follow a
walking trainee), power about 0 dBm, unused slots off, PIN changed so trainees can't reconfigure
it. Shorter intervals shorten battery life; check it before each drill.

**How the phone uses a beacon:**
1. Smooth RSSI with a short moving median plus an exponential moving average.
2. Convert to distance with the log-distance path-loss formula:
   `d = 10 ^ ((txPower@1m − rssi) / (10·n))`, where n ≈ 2.0 (open area) to 3.0 (indoors).
3. If no packets arrive for N seconds, the contribution fades to 0.

**Calibration (v0.19.0, on the phone):** Settings → Calibrate <beacon> records the median smoothed
RSSI over 15 s at two or more known distances; the server fits `txPower@1m` and n
(`BeaconCalibration`, least squares on log distance, n 1.5–4.5) and pushes them to the phones of
the beacon's exercises. Calibration belongs to the site (first walk indoors: n ≈ 3.3–4.1 with
RSSI flat past ~5 m; outdoors 2026-10-16: n 2.87, steady to 7.6 m).

**Reset (chunk 4.13, operator 2026-10-17):** console Devices → Beacons → Reset calibration (admin,
`POST /api/beacons/{id}/reset-calibration`): `txPowerAt1m` null (the beacon's advertised power),
n = 2.5, no `edgeRssi`. Power and n are reset together because they are fitted together. Pushed to
the phones like any edit; each exercise using the beacon logs `beacon.calibration_reset`.

**Gas edge (v0.20.0, chunk 3.4):** RSSI can't tell a wall from distance (drywall ≈ 3–6 dB), so a
beacon may carry `edgeRssi`, recorded on the phone at the doorway. `BeaconRanging.presence`
gates the beacon's leaks: 0 until the smoothed signal is ≥ edge + 3 dB, on until it drops below
the edge (dead band), ramping 0 → 1 over 6 dB above it; `SourceModel` multiplies the leak by it.
Setup that makes walls cost more signal (lower TX power, shielded back, low mounting) is in
`docs/BEACON_SETUP.md`. Field result 2026-10-16 (indoors, lowered TX power): −63 dBm at 1 m, n 3.56,
edge −78 dBm; the operator confirmed that the edge stopped readings outside the room.

**Expectations:** BLE ranging is noisy, so treat beacons as "warmer / colder", not as a measuring
tape. The sensor-lag filter hides most of the jitter.

**Exercise beacons (v0.15.0, operator decision 2026-10-15):** an exercise lists its beacons on
its Devices tab (`exercise_beacons`, `/api/exercises/{id}/beacons`). Phones receive exactly those
in their snapshot and ignore every other beacon. A beacon leak must use one of them. A beacon
may be in **several exercises, also running at once**: each phone computes readings only from
its own exercise's leaks, so a shared beacon doesn't interfere (the v0.14.0 "one running
exercise per beacon" rule was dropped).

**Implementation (v0.14.0):** beacon inventory `beacons` (`/api/beacons`, admin); a beacon leak
is a `GasSource` with shape `beacon` (`beaconId`, `radiusM`, optional map `anchor` for the
console and the planning preview); the exercise snapshot carries `beacons` (identity and
calibration). Shared `IBeaconParser`, `BeaconRanging` (since v0.18.0: 2 s median window, EMA τ 2 s, stale after 8 s)
and `SourceModel.contributions(..., beaconDistances)`, which works without GPS.

### 8.3 Instructor overrides

An override targets one device, a selection, a group, or every device in the exercise, and one
channel or all channels:
- **FIXED** a value, **RAMP** to a value over N seconds, **OFFSET** (add to the computed value),
  **RELEASE** (return to LIVE)
- **Faults:** sensor fault, pump fault, low battery, bump-test failure
- **Utility:** silence alarms remotely, send a text message to the screen, pause the exercise

---

## 9. Instructor console (web)

- **Exercise list:** create, copy (as a template), and archive exercises. See which are running.
- **Live map** for the selected exercise:
  - Each device as a marker with its callsign, a color for its worst alarm, and a mini readout.
  - Breadcrumb trails and GPS accuracy circles.
  - Stale or offline indicators.
- **Source editing:** draw points, plumes, and zones. Place beacon sources at their physical
  location for reference. Wind arrow control. Preview heatmap (uses the shared model) showing
  what a device would read at each location.
- **Device panel:** live readings, alarm history, override controls, profile, battery, signal,
  app version.
- **Exercise control:** Start / Pause / Resume / Reset. A timeline of scripted events.
- **After-action review:** replay a session on the map with a time slider. Export CSV and
  GeoJSON.
- **Admin:** sensor profiles, beacon inventory, device fleet and enrollment, instructor
  accounts, APK version on each device.

---

## 10. Server

### 10.1 Responsibilities
Authentication, CRUD for all entities, the realtime hub (pushes changes to devices and
consoles, scoped per exercise), telemetry ingest, exercise clocks and event schedulers, replay
queries, and hosting the APK download.

### 10.2 Data model (first pass)

```
Instructor(id, name, email, passwordHash, role[admin|instructor])
Device(id, name, tokenHash, model, appVersion, groupId, exerciseId?, monitorId?, lastSeenAt)
       -- monitorId: the monitor imitated in its exercise; null = the exercise default (§7.2)
DeviceGroup(id, name)
SensorProfile(id, name, version, json)   -- a monitor (§7): model, screen, channels
Beacon(id, label, protocol[ibeacon|eddystone], uuid, major, minor | namespace, instance,
       txPower1m, pathLossN, exerciseId?, notes)
Exercise(id, name, areaBounds, wind{dirDeg, speed}, o2Displacement,
         profileId,                -- default monitor (§7.2)
         version, state)
ExerciseInstructor(exerciseId, instructorId)
Source(id, exerciseId, kind[point|plume|zone|beacon], gases[{gas, peak}], geometry|beaconId,
       radius, falloff, activation, rampSec, growth, enabled)
ScriptedEvent(id, exerciseId, offsetSec, action json)
Session(id, exerciseId, startedAt, endedAt, state)
Override(id, sessionId, target, channel, mode, value, rampSec, createdBy, createdAt, releasedAt)
Telemetry(sessionId, deviceId, ts, lat, lon, accuracy, battery, readings json, alarms json)
          -- TimescaleDB hypertable, compressed after 7 days
EventLog(sessionId, deviceId?, ts, type, data json)
```

### 10.3 Device protocol (WebSocket, JSON, versioned)

**Device → server**
| Message | Contents |
|---|---|
| `hello` | deviceId, token, appVersion, protocolVersion, profileVersion, exerciseVersion |
| `telemetry` | ts, lat, lon, accuracy, heading, speed, battery, readings{ch:value}, alarms[], beacons[{id, rssi, dist}] (sent 1×/s, batched after a network drop) |
| `event` | alarmRaised / alarmAck / peakViewed / bumpTest, etc. |

**Server → device**
| Message | Contents |
|---|---|
| `welcome` | serverTime, device name, profile (full), exercise state (full) or standby |
| `profile` | full profile when it changes |
| `exercise` | full exercise state with a version number, or `null` when the device leaves an exercise |
| `override` | target channel(s), mode, value, rampSec |
| `command` | silence, message, fault, setTelemetryRate, appUpdateAvailable |

Full-state snapshots with version numbers keep reconnect logic simple. A `protocolVersion`
field lets older APKs keep working while the fleet updates.

### 10.4 Security
- HTTPS / WSS only, with a Let's Encrypt certificate for `simsensor.tak-solutions.com`.
- Instructors log in with password and session cookie. Devices use a per-device bearer token.
  Enrollment tokens are single-use and short-lived.
- Revoking a device invalidates its token immediately.
- Rate limiting on enrollment and login endpoints.

---

**Guided install and certificate watch (v0.27.0, chunk 4.8):** `deploy/install.sh` asks for
the FQDN and certificate email and writes the settings itself (operators don't edit `.env`).
`CertificateService` checks the server's own certificate daily and warns admins when renewal is
failing (Let's Encrypt no longer sends expiry emails). The Server update page shows the steps,
elapsed time and updater heartbeat (kept alive during updates from v0.27.0's update.sh on) and
reloads the console when the new version answers.

**Backups (v0.28.0, chunk 4.9, operator 2026-10-17):** the updater also makes backups
(`pg_dump -Fc`), restores them and prunes them, on requests from the server in the same shared
folder (`{"action": "backup" | "restore" | "inventory" | "update"}`). A restore backs up the
current data first, stops the server, recreates the database in TimescaleDB restore mode
(`timescaledb_pre_restore` / `pg_restore` / `timescaledb_post_restore`) and starts the server; on
failure it puts the safety backup back. Backup names are checked against a strict pattern on both
sides; only a plain file name in the backups folder is ever used. The retention period (default 7
days, 1–365) lives in `update/retention.json`, read by the updater, which deletes older backups
except the newest one and lists the rest in `update/backups.json`. One action at a time; an
inventory request never blocks an update, backup or restore.

**Server updates (v0.26.0, chunk 4.6, operator 2026-10-16):** user-initiated from Settings →
Server update. `UpdateService` reads the repository's version tags and the newer release notes
from GitHub (public repo, cached 10 min) and, for an admin, writes `request.json` into the folder
shared with the `updater` container. The server never touches Docker; the updater (Docker socket,
no ports) validates the tag and runs `deploy/updater/update.sh` from the current release (backup,
build, health check, automatic rollback). The steps are in `deploy/updater/update.sh`.

**Exercise location and address search (v0.25.0, chunk 4.5):** an exercise may have a location
(label + coordinates, V11) chosen in the console's create dialog or Settings tab; the Live map
starts there until there are leaks to frame. `GET /api/geocode` proxies OpenStreetMap Nominatim
(`SIM_GEOCODER_URL`) behind `CachingGeocoder` (1-day cache, at most one request per second
server-wide, identifying User-Agent `SimSensor/<version> (+<public URL>)`), as the Nominatim
usage policy requires; the console searches only when Find is pressed (no autocomplete).

**First account (v0.24.0, operator 2026-10-16):** a database with no instructors gets the admin
from `SIM_ADMIN_EMAIL` / `SIM_ADMIN_PASSWORD` if set, otherwise the published default admin
`admin@simsensor.local` / `SimSensor-Admin1` (`bootstrap/DefaultAdmin`). While that account is
enabled, `/api/auth/me` reports `defaultAdminActive` to admins and the console shows a warning
on every page; the README walks the installer through replacing it.

## 11. Deployment & distribution

### 11.1 Server (R5, R6)

**Target:** a public DNS name pointing at a Linux VM (Ubuntu 22.04 or later), Docker Compose.

```
deploy/
  docker-compose.yml   # server, postgres(+timescaledb), redis, [proxy]
  .env.example         # DB password, admin bootstrap credentials, public URL
  backup.sh            # nightly pg_dump, kept N days
```

**Host surveyed 2026-10-14:** freshly reinstalled, 4 vCPU / 15 GiB / 315 GB, nothing
running, ports 80/443 free. Sim Sensor therefore runs **its own Caddy proxy** on 80/443 with
automatic Let's Encrypt for `simsensor.tak-solutions.com`.

**Host preparation (done 2026-10-14):**
- `apt upgrade`
- Install Docker Engine + Compose plugin
- Enable ufw for 22/80/443 only
- Disable SSH password login once key access is confirmed

Host-specific operations notes are kept privately by the maintainers.

### 11.2 Android app (R9)
- **Application ID `com.taksolutions.simsensor`**, confirmed by the operator 2026-10-14. It is
  permanent.
- **Google Play closed testing** (operator, 2026-10-14) as well as MDM and sideloading. One
  reason is Play Protect recognition of the app. Play builds are App Bundles (`.aab`); MDM and
  sideload builds are APKs from the same code and the same key. Play policy requirements are
  in `docs/ANDROID_PLATFORM_REFERENCE.md` §9.
- **Release signing key** generated once and stored safely outside the repo, with a backup,
  and **uploaded to Play App Signing** (operator choice) so all three channels share one
  signature.
  ⚠️ Sideloaded and MDM updates only install over the existing app if the signature matches.
  **Losing the key means uninstalling the app from every phone.**
- **Sideload:** the server hosts the current APK at
  `https://<host>/download/simsensor.apk`, with a QR code to it in the console. Kept current
  automatically (chunk 4.12, operator 2026-10-17): `deploy/updater/fetch-app.sh` takes the APK
  from the running version's GitHub release (or the latest release's, for server-only versions,
  if not newer than the server), never replaces a newer published app, and writes it atomically.
  The installer runs it; the updater runs it at every inventory, and the server asks for an
  inventory at start, so after an update the new release's scripts publish the new app.
  `tools/publish-apk.sh` remains for publishing by hand.
- **MDM (agency's self-built MDM):** the release APK is uploaded to the MDM server, which
  pushes it to devices. The app supports Android **managed configurations** (app
  restrictions), so if the MDM's device policy controller can set them, it can push the server
  URL and a fleet enrollment token, and devices enroll themselves with no taps. It can also
  pre-grant permissions and the battery optimization exemption. **Confirmed 2026-10-14: the
  agency MDM can push managed configurations.** QR enrollment is still built as a
  full first-class path (operator requirement), for sideloaded phones and re-enrollment.
  - Configuration keys (stable contract, documented in `docs/MDM.md`): `server_url`,
    `enrollment_token`, `device_name` (optional), `settings_pin` (optional).
    `device_name` is used only when the phone enrolls; rename an enrolled phone on the console.
    The phone enrolls by itself whenever it isn't enrolled (also after Remove enrollment or a
    console revoke) while the token is valid. **Retiring a fleet token** (Enroll page: retire on
    a date, or now) stops new enrollments with it; phones it enrolled keep working. Rotation:
    create a new token, push it through the MDM, retire the old one after the push (v0.22.0).
  - ⚠️ The MDM likely treats a repeated `versionCode` as already installed, so every APK
    uploaded to it must have a higher `versionCode`.
- **Update awareness:** devices report their app version. The console shows outdated devices,
  and the server can tell a device that an update is available.
- `versionCode` always increases with each release (see `CLAUDE.md` §9).

---

## 12. Roadmap

| Phase | Scope | Result |
|---|---|---|
| **0. Scaffold** | Gradle monorepo, shared module, Ktor skeleton, Compose skeleton, console skeleton, Docker Compose, CI, VERSION and release-notes checks | Everything builds; the app connects to the server |
| **1. MVP: remote control** | Enrollment (QR + MDM managed configuration), WebSocket link, telemetry, 5-gas profile, detector screen with alarms and background alerts, multi-exercise model, live device map, manual overrides, load test, first deploy | Instructors can drive the readings in separate exercises and watch them on a map |
| **2. GPS sources** | Point, plume and zone sources, multi-gas sources, wind, timing, live editing, heatmap preview | Location-driven leaks outdoors |
| **3. Beacons** | Buy test beacons, registry, scanning, RSSI→distance, calibration, beacon sources | Close-range and indoor leaks driven by the phone itself |
| **4. Training features** | Scripted timeline, STEL/TWA, faults, bump/zero mock, after-action replay, export | Complete exercise lifecycle |
| **5. Hardening** | Scale-out to several server instances, backups and restore drill, audit log, roles refinement | Production use at scale |

---

## 13. Open questions

1. ~~Host contents~~: resolved 2026-10-14. The host is freshly reinstalled and empty (§11.1).
2. ~~MDM~~: resolved 2026-10-14. Managed configurations supported; QR enrollment also built.
