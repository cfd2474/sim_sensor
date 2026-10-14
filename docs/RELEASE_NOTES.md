# Release notes

Newest first. One section per release (see `CLAUDE.md` §9).

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
