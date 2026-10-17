# Release notes

Newest first. One section per release (see `CLAUDE.md` §9).

## v0.26.0 — 2026-10-17

- **Update the server from the console:** **Settings → Server update** (admins) shows the running
  version, the newest version on GitHub and what's new, with an **Update to vX.Y.Z** button. After
  you confirm, the server backs up its database, downloads and builds the new version and
  restarts (about a minute offline; phones reconnect by themselves). If the new version doesn't
  start, it goes back to the previous one by itself. Progress and the result stay on the page.
- New installs follow the README's **Install on a server** steps so they can be updated this way.
- Phones are unchanged.

## v0.25.0 — 2026-10-16

- **Create new exercise** (big button at the top of the Exercises page) opens a dialog that sets
  it up in one go:
  - **Name** and default **monitor**.
  - **Location:** type an address and press **Find**, then pick the place. The exercise's map
    starts there. (Address search uses OpenStreetMap; it can be changed later on the exercise's
    Settings tab.)
  - **Phones:** tick the phones to add. A phone already in another exercise is flagged, with that
    exercise's name and state; ticking it moves it here (and warns if that exercise is running).
  - **Beacons:** tick the beacons to use. A beacon can be in several exercises at once.
- **Delete** button on each exercise in the list, with a confirmation. It says what is deleted
  (leaks, event log, recorded readings) and what stays (phones go back to waiting, beacons stay in
  the inventory). A running exercise is ended first ("End and delete").
- Phones are unchanged.

## v0.24.0 — 2026-10-16

- **New installs start with a default admin account** (`admin@simsensor.local`, password in the
  README) when no admin is set in the server settings. The README's new **First sign-in**
  section explains how to replace it: create your own admin account, sign in with it, and
  deactivate the default one.
- While the default account is still active, the console shows an orange warning to admins on
  every page.
- Existing installs, including this server, are not affected: the default account is only created
  on a completely empty install.
- Phones are unchanged.

## v0.23.0 — 2026-10-16

- **One entry per phone on the Devices list.** A phone that enrolls again (after Remove
  enrollment, a revoke, a reinstall, or a new QR code or MDM token) gets its existing entry back,
  with a new connection key, instead of a second entry. Its name stays as set on the console.
  After a revoke it comes back active but out of any exercise.
- Phones are recognised by Android's per-app device ID. A factory reset gives the phone a new ID,
  so it then appears as a new entry.
- Entries from before this release are matched once the updated app connects. Leftover revoked
  duplicates from before can be deleted on the Devices page.
- The event log shows "Phone enrolled again" when this happens.

## v0.22.0 — 2026-10-16

MDM enrollment that recovers by itself, and fleet tokens you can retire.

- **Phones re-enroll from the MDM:** a phone that isn't enrolled (after **Remove enrollment** on
  the phone, or after you revoke it on the console) enrolls again on its own as long as the MDM
  provides a valid fleet token. While on the enroll screen it checks the MDM settings every few
  seconds, so a token pushed while the app was closed is picked up too. If there's no network it
  retries every 30 seconds.
- **Retire fleet tokens** (console → Enroll phones, admins): give a fleet token a **Retire on**
  date when you create it or later, change or clear the date, or **Retire now**. A retired token
  enrolls no new phones; phones it already enrolled keep working. A phone holding a retired token
  waits until the MDM pushes the new one, then enrolls with it straight away. To rotate: create
  a new token, push it through ATLAS, then retire the old one.
- After a re-enrollment the phone's meter switches to its new connection by itself, even if the
  app was in the background.

## v0.21.1 — 2026-10-16

- Console: the **wind control is live**. Moving the direction slider, tapping N / E / S / W or
  typing a speed saves on its own a moment after you stop (no Apply button); plumes and phones
  follow at once. The editor shows "Saving…" / "Saved" and closes with **Done**.
- Phones are unchanged.

## v0.21.0 — 2026-10-16

- **Waiting screen:** a phone that isn't in an exercise now shows a Sim Sensor screen saying
  **Waiting for exercise assignment**, in the style of the app's store graphic, with the phone's
  name, its connection and the Settings button. The meter appears by itself once the instructor
  adds the phone to an exercise.
- **Ending an exercise resets the meters:** when the instructor ends an exercise, every phone in
  it goes straight to clean-air readings (no slow fall), and all alarms are silenced and cleared,
  including latched A2, STEL/TWA and peaks. The meter shows "EXERCISE ENDED — readings at
  baseline" and stays quiet, ignoring leaks and overrides, until the exercise is started again.

## v0.20.1 — 2026-10-16

- **No alarms while calibrating:** while the phone's Calibrate screen is open, readings and
  alarms pause, because the phone is held right at the beacon. No alarm can latch and go off
  when you leave the screen; readings resume as normal once you're back on the meter. The screen
  says so at the top.
- The console shows "calibration.started" and "calibration.ended" for that phone, so an
  instructor can see why its readings dropped.

## v0.20.0 — 2026-10-16

Beacon leaks that stop at walls.

- **Gas edge:** on the phone, Settings → Calibrate <beacon> has a new **Gas edge** step. Stand
  at the furthest spot where the gas should still read (usually the doorway), press **Record
  edge** (15 s) and **Save gas edge**. Below that signal, phones show no reading from the
  beacon's leaks, so signal coming through a wall doesn't count. The reading rises over a few
  dB above the edge, and a small margin stops it flickering when you stand right at it.
- The Calibrate screen shows **Here: inside / OUTSIDE** live, so you can walk around the walls
  and check the edge. **Clear gas edge** removes it.
- Console: Settings → Beacons shows each beacon's gas edge, and the editor can change or clear
  it. The Live tab says "outside gas edge" for a phone that hears the beacon but is outside.
  The event log shows "Beacon gas edge set".
- **New guide:** `docs/BEACON_SETUP.md`. It covers lower transmit power indoors, shielding the
  side facing a wall, low mounting, calibrating on site and checking the edge.
- Calibration's fade value is now capped at 4.5, the most the beacon editor accepts, so a
  calibrated beacon can always be edited.

## v0.19.1 — 2026-10-16

- Phone Calibrate screen: the beacon's current signal at 1 m is shown with its decimal
  (e.g. −58.5 dBm, not −58).
- Distances typed in feet are saved exactly (25 ft is 7.62 m), and distance fields work on
  phones set to languages that use a decimal comma.

## v0.19.0 — 2026-10-15

Calibrate beacons on the phone, and shut the app down.

- **Calibration moved to the phone.** On a phone in an exercise that includes the beacon, open
  Settings and press **Calibrate** under the beacon. Hold the phone 1 m from the beacon, press
  **Record** and stand still for 15 s. Repeat at 3 m (change the distances or add more if you
  like), then **Save calibration**. The phone shows the result and warns if the measurements
  don't fit well. Every phone in the beacon's exercises uses the new values at once, and the
  exercise's event log shows "Beacon calibrated" with the phone's name.
- The console's Calibrate button (v0.18.0) is gone. Settings → Beacons still shows each beacon's
  saved values.
- **Shut down Sim Sensor** (phone Settings, after a confirmation): stops the meter completely
  (readings, GPS, Bluetooth, connection and the notification) and closes the app. The Live tab
  and event log show that the phone shut down. Open the app again to restart it.
- Like the rest of phone Settings, both are behind the MDM settings PIN when one is set.

## v0.18.0 — 2026-10-15

Beacon calibration.

- **Settings → Beacons → Calibrate** (admins): pick an exercise the beacon is in and a phone that
  hears it. Hold the phone 1 m from the beacon and press **Record** (15 s), then do the same at a
  second distance (3 m by default; add more if you like). **Save calibration** works out the
  beacon's signal at 1 m and how quickly its signal fades at your site. Phones in its exercises
  use the new values at once. The page warns when the measurements don't fit well.
- **Steadier beacon readings:** phones now average the beacon's signal over about 2 seconds
  instead of a fraction of a second, so readings at middle distances jump around much less. A
  move still shows within a few seconds.
- **Beacon leak editor:** a warning appears when a beacon leak reaches past about 5 m (16 ft).
  Indoors, phones can't reliably tell those distances apart.

## v0.17.0 — 2026-10-15

Beacon leaks now work on the phones.

- **Phones listen for the exercise's beacons.** A beacon leak gives readings by the phone's
  distance from the beacon, worked out from signal strength. No GPS is needed for it, and it works
  indoors. Only beacons added to the exercise (Devices tab) count; any other beacon is ignored.
- Phones ask for the **Nearby devices** permission (Android 12 and later) alongside location.
  When an exercise has beacon leaks but the phone can't scan, the meter shows why: permission
  missing, Bluetooth off, or no Bluetooth LE.
- **Live tab:** each phone shows the beacons it hears, with estimated distance and signal
  strength (e.g. "Hears Beacon 1 ~1 ft (−48 dBm)").
- Phone **Settings** lists the scan state and each beacon of the exercise with its distance, or
  "Not heard". The Settings screen now scrolls.
- **Editing a beacon** (UUID, major, minor, calibration) now reaches phones already in exercises
  that use it.
- The "GPS" warning no longer appears for exercises whose only leaks are beacon leaks.
- Distances are uncalibrated estimates for now. Calibration comes in a later release.

## v0.16.1 — 2026-10-15

- Settings → Beacons accepts the UUID **with or without dashes**, in any case (paste it as the
  beacon's app shows it, e.g. `EB14B62F218B4EEE8F15B6322ADBB54E`). It is stored and shown in the
  standard form with dashes.

## v0.16.0 — 2026-10-15

Email for instructor accounts.

- **Settings → Email** (admins): connect an SMTP provider. Presets for **Brevo**
  (`smtp-relay.brevo.com`, 587, SMTP login + SMTP key) and **Resend** (`smtp.resend.com`, 465,
  user `resend` + API key), or any other SMTP server. The password or key is never shown again
  after saving. **Send test email** sends to you and shows the provider's error if it fails.
  The sender address must be verified with the provider.
- **Forgot password?** on the sign-in page (appears once email is set up): sends a link to choose
  a new password. The link works once and for one hour; using it signs the account out
  everywhere. The page answers the same whether or not an address has an account.
- **New instructors by invite:** when adding an instructor, tick "Email them a link to set their
  password" and skip the password; they get an email to choose their own (link valid 72 hours).
- **Email reset link** next to each instructor (admins).
- Phones are unchanged.

## v0.15.1 — 2026-10-15

- Instructor passwords need **at least 8 characters** (was 12), with no other rules. Applies to
  new instructors, password changes on the Account page, and admin resets. Existing passwords
  keep working. The phone app is unchanged.

## v0.15.0 — 2026-10-15

- **Beacons belong to exercises.** An exercise's **Devices** tab now has a **Beacons** card: add
  beacons from Settings → Beacons, or remove them. Phones in the exercise react only to these
  beacons; any other beacon in range is ignored.
- A beacon leak can only use one of the exercise's beacons ("Beacon" on the Live map stays off
  until the exercise has one). A beacon can't leave an exercise while one of its leaks uses it,
  and can't be deleted while it is in any exercise.
- **A beacon can be in several exercises, also running at the same time** (the v0.14.0 limit is
  gone): each exercise's phones only use that exercise's leaks, so two teams can train around
  the same beacon.
- The event log records beacons added to and removed from an exercise.

## v0.14.0 — 2026-10-14

First part of **beacon leaks**: gas around a physical Bluetooth beacon (iBeacon).

- **Settings → Beacons:** keep the agency's beacons with their UUID, major and minor (from the
  beacon's configuration app), the environment (open area, indoors, cluttered) and an optional
  calibrated signal at 1 m. A beacon in use can't be removed.
- **Beacon leaks** on the Live map ("Beacon"): pick a beacon, set how far its gas reaches and
  the gases and timing like any leak, and click where it is placed. The map pin is for the
  instructor's picture and the planning overlay; phones will measure distance from the beacon's
  signal.
- A beacon serves **one running exercise at a time**: starting a second exercise that uses the
  same beacon is refused with the name of the one using it.
- Phones don't scan for beacons yet: that comes next, with the first beacon in hand. **Update
  every phone to this version before adding beacon leaks**; older apps ignore the whole
  exercise update when it contains one.

## v0.13.1 — 2026-10-14

- Renaming a phone in the console now shows on that phone at once (before, only after it
  reconnected). Update the app (0.13.1).

## v0.13.0 — 2026-10-14

- **Sensors from a list.** In Settings → Monitors, each row now has a "Sensor" drop-down instead
  of free-text Id and Label: O₂, LEL, CO, H₂S, HCN, and new **NH₃, SO₂, Cl₂, HF, CO₂**. Picking a
  sensor fills in typical settings (range and A1 / A2 / STEL / TWA from OSHA / NIOSH-based
  defaults), all still editable. "Add sensor" adds the next one not yet on the monitor; a new
  monitor starts with O₂, LEL, CO and H₂S.

  | Sensor | Range | A1 | A2 | TWA | STEL |
  |---|---|---|---|---|---|
  | NH₃ | 0–200 ppm | 25 | 50 | 25 | 35 |
  | SO₂ | 0–100 ppm | 2.0 | 4.0 | 2.0 | 5.0 |
  | Cl₂ | 0–20 ppm | 0.5 | 1.0 | 0.5 | 1.0 |
  | HF | 0–10 ppm | 3.0 | 6.0 | 3.0 | 6.0 |
  | CO₂ | 0–5 %vol | 0.5 | 1.0 | 0.5 | 3.0 |

- Leaks can release any of the ten gases.

## v0.12.0 — 2026-10-14

The phone can now look like the agency's real meters.

- **Phone screen per monitor** (Settings → Monitors → Edit → "Phone screen"):
  - **Tiles** (like a Blackline G8): the wearer's name, time and date on top; one tile per gas in
    two columns, green in clean air, flashing yellow at A1 and red at A2, with a "LOW GAS" /
    "HIGH GAS" banner. A fifth gas gets a full-width tile.
  - **Grey LCD** (like a Dräger X-am): one row per gas; in alarm the reading alternates with
    "A1" or "A2"; the light on top flashes red once (A1) or twice (A2); the green OK key
    acknowledges, the blue key shows peak readings.
  - **Sim Sensor** (generic): as before.
- Readings, alarms, latching and sound work the same on every screen; only the look changes.
- No manufacturer logos or names on the phone; the "SIMULATOR — TRAINING ONLY" banners stay.
- Update the app (0.12.0) to see the new screens; older apps keep the generic screen.

## v0.11.0 — 2026-10-14

**Monitors:** set up the agency's real meters once, then give each phone the one it should imitate.

- **Settings → Monitors** (new Settings area, which also holds Display units and Instructors):
  each monitor has a name, a make and model (e.g. "Dräger X-am 2800", "Blackline G8"), its
  sensors with A1 / A2 / STEL / TWA levels, latching and response time. Start from "Duplicate"
  of the built-in 5-gas monitor. A monitor in use can't be deleted.
- **Each phone in an exercise imitates a monitor**, chosen on the exercise's Devices tab (per
  phone, or "Apply to all"). Phones without one use the exercise's **default monitor**, chosen on
  its Settings tab. Changes reach the phones within about a second and are logged
  ("Monitor set"). Moving a phone to another exercise starts it on that exercise's default.
- Phones in one exercise can have different gases, like a mixed fleet. A leak may release any
  gas; a phone whose monitor lacks it doesn't show it. The Live tab shows each phone's monitor
  and its own gases; the control panel and the map overlay offer every gas in the exercise.
- **The exercise Criteria tab is gone:** alarm levels come from the monitor, like on the real
  devices. (No exercise used it.)
- The phone's Settings screen shows the monitor it imitates. Update the app (0.11.0).

## v0.10.1 — 2026-10-14

- **App icon.** The phone app has its own icon (the meter with signal waves) on the home screen,
  as a themed icon on Android 13+, and in the status bar for its notifications.

## v0.10.0 — 2026-10-14

Faster sensors and industry-style A1 / A2 alarms, from the first field walk.

- **Faster response.** The built-in 5-gas setup now responds in 5 s on every sensor (was 15–60 s).
  The reading still moves like a real sensor: fast when far from the real value, slowing as it
  gets close. "Response s" in Meter setups (and "Response (s)" per exercise) changes it.
- **A1 / A2 alarms.** The low and high alarms are now called A1 and A2 on the phone, the
  console and in notifications. When A2 goes off, the meter shows A2 only, not both.
- **Latching per alarm.** Each alarm (A1, A2, STEL, TWA) latches or clears itself on its own,
  per sensor, in Meter setups; an exercise can change it on its Criteria tab. Default: A1 clears
  itself when the reading drops, A2, STEL and TWA stay on until ACK.
- After an ACK of A2 while the gas is still above A1, the meter shows A1 without sounding again.
- Existing meter setups were converted: one that latched every alarm now lets A1 clear itself;
  one that latched none still latches none. Your own response times are unchanged.
- The phone app must be updated (0.10.0) for the new alarm behaviour.

## v0.9.0 — 2026-10-14

Leaks are now set up and run from the map in the console.

- **Place leaks on the Live map.** Pick "Round leak", "Plume" or "Area", then click the map
  (for an area, click its corners). A form sets the gases and their peak, the size, how
  quickly the strength falls off, and the timing: start with the exercise, at a set time or
  when you trigger it; build-up, duration, fade-out and growth.
- **Move a leak** by dragging its label on the map. Phones get every change within about a
  second.
- **Leak list** with what each leak is doing right now (waiting, rising, leaking, fading,
  stopped, off), "Start leak now" for leaks you trigger, and on/off.
- **Wind** badge on the map: shows where the gas drifts; click to change the direction.
  Plumes turn with it.
- **Gas overlay:** choose a gas to see where it reaches. Before the exercise runs, every leak
  is shown at full strength; while it runs, the picture follows the exercise clock. Colours
  follow the meter's LOW and HIGH alarms.
- **System settings** page (admins): imperial or metric. Sizes, wind speed, GPS accuracy and
  the map scale follow it, in the console and on the phones.
- The event log now shows leak changes, and names the exercises a phone moved between.

## v0.8.1 — 2026-10-14

- Build fix only: v0.8.0 failed the Android lint check (a false "missing permission" error
  when stopping location updates). The app is the same; install v0.8.1.

## v0.8.0 — 2026-10-14

Phones now react to leaks on the map: readings rise and fall as the trainee walks.

- **Readings follow position.** The phone combines the exercise's leaks, the wind and the
  exercise clock with its own GPS several times a second, with the same sensor lag and alarms
  as before. Leaks start, ramp, grow and fade on exercise time.
- **Precise GPS.** The app now asks Android for high-accuracy location. Before, it got the
  default "balanced" mode, which can be off by about 100 m.
- **GPS warnings.** When the fix is poor or lost, readings hold at the last good position and
  the meter says "GPS WEAK", "GPS LOST" or "NO GPS FIX".
- **Display units** from the system setting (feet or metres) for GPS accuracy.
- **Smaller app:** 12 MB → 2.4 MB.
- Phones enrolled by ATLAS move past "Location needed" by themselves once the policy grants
  location (it can arrive a few seconds after install).
- Leaks are placed through the API for now; the console's map editor is the next release.

## v0.7.0 — 2026-10-14

Server groundwork for leaks on the map. The console screens and the phone readings come in the
next releases, so nothing changes yet in day-to-day use.

- **Virtual leaks** can be stored per exercise: point, plume (follows the wind) or zone, with one
  or more gases, how fast the reading falls off, and timing (immediately, at a time into the
  exercise, or started by hand; ramp-up, growth, run time, fade-out).
- **Exercise clock:** leak timing counts exercise time. It pauses when the exercise is paused,
  and a new run starts from zero.
- **Preview** of expected readings for any gas over a map area, from the same calculation the
  phones will use.
- **Display units** (imperial or metric) are a system setting for admins. Data is always
  stored in metric.
- Changing an exercise's meter setup is refused if one of its leaks uses a gas the new setup
  doesn't have.

## v0.6.0 — 2026-10-14

Ready for real phones: a signed app, a download page, and a server tested with 1,000 phones.

- **Signed release app.** The same signing key is used for Google Play, the MDM and direct
  downloads, so any of them can update the others. (Phones with the earlier test build must
  uninstall it once before installing this one.)
- **Download the app from the server:** the console's Enroll page shows a QR code for
  https://simsensor.tak-solutions.com/download/simsensor.apk, with its version and checksum.
- **The screen no longer times out** while the app is in the foreground.
- **Faster instructor control at scale:** an override or message for a whole exercise now
  reaches 1,000 phones in under 0.2 seconds (it took about 0.7 s for 300 before).
- Admins can permanently delete a revoked phone together with its data.
- Google Play closed-testing pack (`docs/PLAY_RELEASE.md`): listing texts, Data safety answers,
  foreground-service declaration and demo-video script.

## v0.5.0 — 2026-10-14

The instructor console: run exercises from the browser at https://simsensor.tak-solutions.com.

- **Exercises:** create, start, pause, resume, end, run again, reset. Each run is recorded
  separately. Reset releases every override.
- **Live map:** every phone in the exercise on an OpenStreetMap map, colored by its worst alarm
  (red pulsing for high alarms), with its name, alarms and walking trail. Gray when a phone
  stops reporting. Side list with each phone's readings, battery and GPS accuracy.
- **Control readings** from the map page: set, ramp to, add, or sensor fault for one gas or all;
  for all phones, the selected ones, or a group. Release, silence alarms, send a message to the
  screens.
- **Criteria tab:** the meter setup's alarm levels next to this exercise's changes; impossible
  values are flagged under the field.
- **Devices:** the phone fleet (rename, groups, assign to exercises, revoke) and per-exercise
  phone lists.
- **Enroll phones:** QR codes (15 minutes to 24 hours), and for admins an MDM fleet token with the
  managed-configuration keys to paste into ATLAS.
- **Meter setups** (admins edit, everyone reads), **Instructors** (admins), **Event log** per
  exercise, **Account** (change password).
- Instructors can now see the instructor list, to share exercises.

## v0.4.0 — 2026-10-14

The phone app now works as a simulated 5-gas meter connected to the server.

- **Enroll a phone** by scanning an instructor's QR code (in the app, or with the phone's own
  camera app), or automatically through the MDM. The phone's key is stored encrypted.
- **Live meter screen:** O₂, LEL, CO, H₂S and HCN readings from the exercise's meter setup,
  with realistic sensor response time. Alarms flash and beep (low, high, STEL, TWA,
  over-range, sensor fault); ACK silences them and clears latched alarms once the gas is gone;
  PEAK shows the highest readings. "SIMULATOR — TRAINING ONLY" stays on every screen.
- **Instructor control works end to end:** fixed values, ramps, offsets and faults set from the
  server appear on the phone within a second; messages appear on screen.
- **Keeps working in the background:** readings, alarms (with a notification) and position
  reporting continue when the trainee switches apps or turns the screen off.
- **Network drops:** readings carry on from the last state; position and readings are queued on
  the phone and uploaded when the connection returns. Reconnects automatically.
- Standby screen when the phone isn't in an exercise; settings behind the MDM PIN when one is
  set; an admin removing the phone signs it out.
- Server: more reliable first start (waits for the database).

## v0.3.0 — 2026-10-14

Phones can now be enrolled and controlled live from the server. The phone app catches up in
the next release, so this is still server-side only.

- **Enrollment:** instructors create a QR code (single use, 15 minutes by default). Admins
  create a long-lived code for the MDM. Each phone gets its own permanent key; an admin can
  revoke a phone, which disconnects it immediately.
- **Assign phones to exercises.** The phone receives the exercise's meter setup with its alarm
  levels. Changing an exercise's alarm levels, or its meter setup, reaches its phones within
  about a second.
- **Instructor control of readings:** set a fixed value, ramp to a value over time, add an
  offset, or show a sensor fault, for one phone, a group, or the whole exercise; release to
  return to live readings. Also: silence alarms, send a message to the screen, change how
  often phones report.
- Phones in one exercise never receive another exercise's changes, and an instructor can't
  take over phones in someone else's exercise.
- **Live data:** phone positions, readings and alarms are stored, and streamed live to the
  instructor watching that exercise.
- Built for many server instances sharing the load (Redis).

## v0.2.0 — 2026-10-14

Server foundation for exercises and alarm settings. Nothing changes on the phones yet.

- Instructor accounts with login and logout. Admins create instructors, reset passwords and
  disable accounts. Repeated wrong passwords are blocked for 15 minutes.
- The first admin account is created automatically on first start.
- Meter setups ("profiles"): the built-in 5-Gas setup (O₂, LEL, CO, H₂S, HCN) is installed
  automatically. Admins can create and edit others.
- Exercises: instructors create exercises and choose the meter setup. Each exercise can
  change any gas's alarm levels (low, high, STEL, TWA), range, baseline and response time.
  Impossible values are rejected with a message for each problem.
- Several instructors can share an exercise. Instructors see only their own exercises; admins
  see all of them.
- The full API is described in `docs/openapi.yaml`.

## v0.1.0 — 2026-10-14

First build of Sim Sensor. Nothing usable for training yet.

- Android app: placeholder 5-gas detector screen (O₂, LEL, CO, H₂S, HCN) with the
  "SIMULATOR — TRAINING ONLY" banner. It can connect to a server and show whether the
  connection works.
- Server: health check and the first device connection handshake.
- Instructor console: placeholder page that shows the server's status and version.
