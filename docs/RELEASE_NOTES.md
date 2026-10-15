# Release notes

Newest first. One section per release (see `CLAUDE.md` §9).

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
