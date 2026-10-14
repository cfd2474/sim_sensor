# Release notes

Newest first. One section per release (see `CLAUDE.md` §9).

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
