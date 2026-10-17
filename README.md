# Sim Sensor

**A gas detector simulator for hazmat training.** Android phones become simulated multi-gas
meters (O₂, LEL, CO, H₂S, HCN). Instructors run the exercise from a web console: they place
virtual leaks on a map or on Bluetooth beacons at the site, watch every trainee's readings live,
and can override any reading at any moment. Trainees see realistic readings and alarms that
change as they move.

> **SIMULATOR — TRAINING ONLY.** Sim Sensor never measures anything. Never use it to detect real
> hazards.

**Download the phone app:** [latest release](https://github.com/cfd2474/sim_sensor/releases/latest)
(APK for Android 8.0+, with its checksum and signing certificate).

<p align="center">
  <img src="docs/screenshots/app-meter-styles.png" width="720" alt="The phone app in its three meter styles: standard, colour tiles and grey LCD">
</p>

![The instructor console's live map during an exercise](docs/screenshots/console-live.png)

---

## Contents

- [What it does](#what-it-does)
- [How a training session works](#how-a-training-session-works)
- [The instructor console](#the-instructor-console)
- [The phone app](#the-phone-app)
- [Install on a server](#install-on-a-server)
- [Certificates](#certificates)
- [First sign-in: replace the default admin account](#first-sign-in-replace-the-default-admin-account)
- [Updating the server](#updating-the-server)
- [Setting up phones](#setting-up-phones)
- [Development](#development)
- [Documentation](#documentation)

## What it does

- **Virtual leaks on a map.** Round leaks, plumes that drift downwind, and areas. Each has its own
  gases, strength, start time, growth and duration. The phone's GPS position decides what it reads.
- **Leaks on Bluetooth beacons.** For indoor or close-range work, a small beacon becomes the gas
  source. Readings rise as the phone gets closer, with no GPS needed, and a calibrated *gas edge*
  stops the reading at a doorway rather than through the wall.
- **Realistic meters.** Readings respond with a set sensor lag. A1 (low) and A2 (high) alarms come
  with tone and vibration. Latching alarms stay on until acknowledged, alongside STEL and TWA
  exposure alarms, peak readings and an O₂ drop where other gases displace it. Three screen styles
  look like the meters agencies carry.
- **Monitors per phone.** Set up each meter model the agency uses, with its sensors and alarm
  levels. Each phone imitates one of them.
- **Live control.** The instructor sees every phone on the map with its readings and alarms, and
  can set, ramp or offset a reading, fault a sensor, silence alarms or send a message to a phone.
- **Event log** of everything that happened, for the debrief.
- **Built for agency fleets.** Phones enroll with a QR code or with no taps through an MDM, and
  keep one entry on the console even when reinstalled. The server updates itself from the
  console.

## How a training session works

1. **Set up once:** the monitors your agency uses (Settings → Monitors) and any beacons
   (Devices → Beacons).
2. **Create the exercise:** name, location, phones and beacons in one dialog.
3. **Place the leaks** on the exercise's map: click a leak type, then the map.
4. **Start** the exercise. Phones in it switch from the waiting screen to the meter, and the leaks
   begin to release gas on their timings.
5. **Watch and steer** on the Live map: who is where, what they read, who acknowledged which
   alarm. Change the wind or override readings as the scenario requires.
6. **End** it. Every meter returns to clean air with its alarms cleared, and the event log is
   ready for the debrief.

## The instructor console

The console is a web app served by the Sim Sensor server. Instructors see the exercises they're
on; admins also manage accounts, email and server updates.

### Live map

Leaks, phones and their readings, live. The gas overlay shows where a gas reaches and how strong it
is, coloured by the A1 and A2 levels. The panel on the right lists the leaks (turn them on and
off, edit them) and the phones, and holds the instructor controls.

![Live map with leaks, phones and the H₂S overlay](docs/screenshots/console-live.png)

### Creating an exercise

**Create new exercise** gathers everything in one dialog:
- **Name** and default monitor.
- **Location:** type an address and press Find. The exercise's map starts there.
- **Phones:** a phone that's already in another exercise is flagged, and ticking it moves it here.
- **Beacons:** a beacon can serve several exercises at once.

![Create new exercise dialog](docs/screenshots/console-create.png)

The exercises list shows each exercise's state and location. Deleting one asks for confirmation
first, and a running exercise is ended before it's deleted.

![Exercises list](docs/screenshots/console-exercises.png)

### Phones and beacons in an exercise

The exercise's **Devices** tab: the phones in it, the monitor each one imitates, and the beacons
its leaks can use.

![Devices tab of an exercise](docs/screenshots/console-devices.png)

### Monitors

A monitor is a meter model: its sensors, ranges, A1/A2 levels (latching or not), STEL/TWA,
response time and screen style. Phones receive changes within about a second.

![Monitors](docs/screenshots/console-monitors.png)

### Beacons

**Devices → Beacons**, next to the phones: the agency's beacons with their identity and
calibration. Calibration, including the gas edge, is
done on a phone at the site; see [docs/BEACON_SETUP.md](docs/BEACON_SETUP.md).

![Beacon inventory](docs/screenshots/console-beacons.png)

### Event log

Phones connecting, alarms raised and acknowledged, overrides, leak changes: everything, with who
did it.

![Event log](docs/screenshots/console-events.png)

### Enrolling phones

A QR code enrolls one phone. An MDM fleet token enrolls a whole fleet with no taps, and can be
given a retirement date for rotation.

![Enroll phones page](docs/screenshots/console-enroll.png)

## The phone app

The app runs on any Android 8+ phone. A yellow **SIMULATOR — TRAINING ONLY** banner is always
on screen.

| Waiting for an exercise | Standard meter | Colour tiles | Grey LCD |
|---|---|---|---|
| ![Waiting screen](docs/screenshots/app-waiting.png) | ![Standard meter with an H₂S A1 alarm](docs/screenshots/app-standard.png) | ![Colour-tile meter with a CO A2 alarm](docs/screenshots/app-tiles.png) | ![Grey LCD meter, alarms alternating with readings](docs/screenshots/app-lcd.png) |
| Until an instructor adds the phone to an exercise. | Large readings; the alarm row turns yellow (A1) or red (A2). | Tiles turn yellow or red, with the highest alarm across the top. | A1/A2 alternate with the reading, as on the real thing. |

- **Alarms** beep and vibrate, also with the screen off or the app in the background.
  **ACK / SILENCE** acknowledges latching alarms; a new alarm always sounds again.
- **Settings** (behind an optional MDM PIN) shows the connection, exercise, monitor, GPS and
  beacons. It also has **Calibrate** for each beacon and **Shut down Sim Sensor**.
- **Calibrate** records the beacon's signal at known distances and at the gas edge. Readings and
  alarms pause while it's open.

| Settings | Calibrating a beacon |
|---|---|
| ![Phone settings](docs/screenshots/app-settings.png) | ![Calibrate screen](docs/screenshots/app-calibrate.png) |

<sub>Screenshots from the Android emulator and a demo exercise. The emulator has no Bluetooth,
hence "Not heard".</sub>

## Install on a server

You need a Linux server (Ubuntu or Debian, for example) with a public IP address, a DNS name
pointing at it (an A or AAAA record, e.g. `sim.agency.org`), and ports 80 and 443 open. Then run:

```bash
curl -fsSL https://raw.githubusercontent.com/cfd2474/sim_sensor/main/deploy/install.sh -o install.sh
sudo bash install.sh
```

The installer guides you through the rest:

1. It checks for Docker and offers to install it if it's missing.
2. It asks for the **domain name**, checks its format (for example, no `https://` and no port),
   and checks that the name points at this server.
3. It asks for an **email for the HTTPS certificate** and checks its format.
4. It installs the newest version into `/opt/simsensor` and writes the settings itself. That
   includes a random database password, so there's no settings file to edit.
5. It starts everything, waits until `https://<your name>` answers with a valid certificate, and
   shows the first sign-in steps.

The first build takes several minutes. To change the domain name or the certificate email later,
run `sudo bash /opt/simsensor/current/deploy/install.sh --reconfigure`.

## Certificates

The HTTPS certificate comes from Let's Encrypt and is handled for you. You don't need to set
anything up or renew anything by hand.

- **Renewal:** Caddy (the stack's web front end) renews the certificate automatically, well before
  it expires. The certificate is kept in a Docker volume, so it survives restarts and updates.
- **Watch:** Sim Sensor also checks its own certificate every day. If renewal has been failing
  (for example, a firewall now blocks port 80, or the DNS name moved), admins see a warning on
  every console page. If email is set up (Settings → Email), they also get an email once a day
  until it's fixed. **Settings → Server update** shows when the certificate expires.
- **Let's Encrypt's own emails:** Let's Encrypt
  [stopped sending expiry reminders in 2025](https://letsencrypt.org/2025/06/26/expiration-notification-service-has-ended),
  so the watch above is what tells you something is wrong. The installer's email is registered
  with Let's Encrypt as the account contact.

If you see the warning, check that ports 80 and 443 are open to the internet and that the DNS name
still points at the server. The certificate is retried automatically once that's fixed.

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
and upgrades are never affected.

Next, under **Settings → Email**, connect an SMTP provider (presets for Brevo and Resend) to turn
on "Forgot password?" and email invitations for instructors.

## Updating the server

**Settings → Server update** (admins) shows the running version, the newest one on GitHub and
what's new, with an **Update** button. The update:
1. backs up the database;
2. downloads and builds the new version;
3. restarts the server (about a minute offline; phones reconnect by themselves);
4. goes back to the previous version by itself if the new one doesn't start.

The updater shows each step and confirms it is still working while the update runs. The page
reloads by itself when the update is complete.

This is done by the stack's `updater` service, the only part with access to Docker. It has no
network port and only acts on requests from the server. It doesn't update itself: if a release's
notes mention a change to the updater, run
`cd /opt/simsensor/current/deploy && sudo docker compose --env-file /opt/simsensor/.env up -d --build`.

## Setting up phones

1. **Install the app** on the phones: the APK from the
   [latest GitHub release](https://github.com/cfd2474/sim_sensor/releases/latest) (directly or
   through your MDM), the download on the console's **Enroll phones** page when the server has
   one published, or Google Play once it's released there (in closed testing now). All are the
   same app, signed with the same key, and update each other.
2. **Enroll** each phone, with either:
   - **a QR code** from the Enroll page: the trainee taps **Scan QR code** in the app, or scans
     it with the phone's camera;
   - **no taps, through an MDM**: push these managed-configuration keys to
     `com.taksolutions.simsensor`.

     | Key | Value |
     |---|---|
     | `server_url` | `https://<your DNS name>` |
     | `enrollment_token` | a fleet token from the Enroll page |
     | `device_name` | optional: the name shown on the console |
     | `settings_pin` | optional: a PIN for the phone's Settings screen |

     A phone that loses its enrollment enrolls again by itself while the token is valid, and
     keeps its entry on the Devices list.
3. **Grant permissions** (or pre-grant them through the MDM): location (for map leaks),
   notifications (for alarms in the background) and Nearby devices (for beacon leaks).

For beacon leaks, see [docs/BEACON_SETUP.md](docs/BEACON_SETUP.md): programming the beacon,
placing and shielding it, and calibrating it on a phone.

## Development

| Path | What |
|---|---|
| `android/` | Android app (Kotlin, Jetpack Compose) |
| `shared/` | Kotlin Multiplatform: protocol and the gas and sensor model (JVM + JS) |
| `server/` | Ktor server (PostgreSQL/TimescaleDB, Redis); also serves the console |
| `console/` | Instructor web console (React, TypeScript, Vite, MapLibre) |
| `deploy/` | Docker Compose stack (Caddy, server, database, Redis, updater) |
| `tools/` | Release and asset scripts (signed APK and Play bundle, icons, store graphics) |
| `docs/` | Design, protocol, API, platform reference, release notes, guides |

Requirements: JDK 17, the Android SDK (path in `local.properties` as `sdk.dir=...`), Node 22+ and
Docker (the server tests start a database container).

```bash
./gradlew check                      # all tests and release checks
./gradlew :android:assembleDebug     # APK in android/build/outputs/apk/debug/
./gradlew :server:run                # server on http://localhost:8080
cd console && npm ci && npm run dev  # console on http://localhost:5173, proxied to :8080
```

Server-only builds (no Android SDK): set `SIMSENSOR_SKIP_ANDROID=1`.

To run the whole stack locally:

```bash
cd deploy
cp .env.example .env    # set SIM_DOMAIN=localhost and a POSTGRES_PASSWORD
docker compose up -d --build
# https://localhost  (Caddy's local certificate; accept it in the browser)
```

`VERSION` is the product version. Every product-changing push bumps it, tags the commit `vX.Y.Z`,
and adds a section to [docs/RELEASE_NOTES.md](docs/RELEASE_NOTES.md); see `CLAUDE.md` §9.

## Documentation

| Document | What's in it |
|---|---|
| [docs/RELEASE_NOTES.md](docs/RELEASE_NOTES.md) | What changed in each version |
| [docs/BEACON_SETUP.md](docs/BEACON_SETUP.md) | Setting up beacon leaks for instructors |
| [docs/DESIGN.md](docs/DESIGN.md) | How it's built and why |
| [docs/PROTOCOL.md](docs/PROTOCOL.md) | Phone ↔ server and console ↔ server messages |
| [docs/openapi.yaml](docs/openapi.yaml) | The console's HTTP API |
| [docs/ANDROID_PLATFORM_REFERENCE.md](docs/ANDROID_PLATFORM_REFERENCE.md) | The Android behaviour the app relies on, with sources and what was verified on hardware |
