# Realtime protocols

Two WebSockets: **devices** (phones) and **consoles** (instructor web UI). The source of truth
is `shared/src/commonMain/kotlin/com/taksolutions/simsensor/protocol/`. This document explains
it. When the two disagree, the code wins, and this file should be fixed.

Common rules:
- One JSON object per text frame. The `type` field selects the message.
- Unknown fields are ignored, so a newer peer may add fields without breaking an older one.
- WebSocket ping every 15 s. The server drops the connection after 30 s without a pong.
- Max frame size is 1 MiB (an offline telemetry backlog must be split into several messages).

---

## 1. Enrollment (REST, before the socket)

A phone gets its permanent **device token** once:

```
POST /api/devices/enroll        (no login)
{"enrollmentToken":"…","appVersion":"0.3.0","deviceName":"Entry 1","model":"SM-G736U1"}
→ 201 {"deviceId":"…","deviceToken":"…","deviceName":"Entry 1"}
→ 401 for any invalid, used, expired or revoked enrollment token (same answer for all)
```

The enrollment token comes from:
- a **QR code**: `simsensor://enroll?server=<percent-encoded URL>&token=<token>`. Single use,
  15 minutes by default (1 min – 24 h). Any instructor can create one.
  See `EnrollmentLink`.
- **MDM managed configuration**: keys `server_url` and `enrollment_token` (a **fleet** token,
  reusable, admins only, optionally expiring). Optional `device_name`.

The device stores the device token securely and never shows it. An admin can revoke a device.
Its token then stops working and any open socket is closed.

---

## 2. Device socket — `wss://<host>/ws/device`

Authentication: `Authorization: Bearer <deviceToken>` on the upgrade request. A missing,
unknown or revoked token is refused with 401 before the upgrade.

`protocolVersion` is currently **1**.

### Handshake

```
device                                          server
  | -- hello {appVersion, protocolVersion, model} --> |   (within 10 s, or the socket is closed)
  | <-- welcome {serverTimeMillis, deviceId, deviceName, exercise, overrides} --|
```

The device works out its clock offset as `serverTimeMillis − localNow` and uses it for every
timestamp it sends.

### Device → server

| type | fields | notes |
|---|---|---|
| `hello` | `appVersion`, `protocolVersion`, `model?` | first message |
| `telemetry` | `samples[]` of `{ts, lat?, lon?, accuracyM?, headingDeg?, speedMps?, battery?, readings{channelId: value}, alarms[]}` | normally one sample per second. After a network drop, the queued backlog is sent in batches. Timestamps more than 7 days old or more than 1 minute in the future are replaced by server time. |
| `event` | `ts`, `kind`, `channelId?`, `detail?` | e.g. `alarm.raised`, `alarm.ack`, `bump.test`. Stored in the event log and shown on consoles. |

Alarm codes in `alarms`: `<channelId>:<LOW|HIGH|STEL|TWA|OVER_RANGE|FAULT>`, e.g. `CO:HIGH`.

### Server → device

| type | fields | when |
|---|---|---|
| `welcome` | `serverTimeMillis`, `serverVersion`, `protocolVersion`, `deviceId`, `deviceName`, `exercise` (snapshot or null), `overrides`, `units` | reply to `hello` |
| `settings` | `units` (`IMPERIAL` / `METRIC`, display only) | an admin changed system settings |
| `device` | `deviceName` | the device was changed in the console (e.g. renamed); since v0.13.1 |
| `exercise` | `exercise`: snapshot or **null (standby)** | device assigned/unassigned; exercise settings changed; its monitor changed or was edited |
| `overrides` | `version`, `overrides[]` | the device's override list changed (also sent after an assignment change) |
| `command` | `kind` (`SILENCE_ALARMS`, `SHOW_MESSAGE`, `SET_TELEMETRY_INTERVAL`), `text?`, `value?` | instructor action |
| `error` | `code`, `message` | the last message wasn't understood |

`exercise` and `overrides` are **full replacements**, never patches. A device that reconnects
gets everything again in `welcome`.

**Exercise snapshot:** `exerciseId`, `name`, `version` (rises on every change), `state`,
`profile` (**this phone's monitor**: its own, or the exercise default; since v0.11.0 also
`model` and `screen` (`generic`, `g8`, `xam` since v0.12.0; phones draw unknown keys as `generic`);
phones in one exercise may receive different profiles), `o2Displacement`,
`windDirDeg?` (direction the wind comes **from**), `windSpeedMps?`, and since v0.7.0:
- `sources`: the virtual leaks (`GasSource`: `id`, `name`, `shape` = `point` / `plume` / `zone`
  with metres and WGS-84 positions, `gases` [{`gas`, `peak`}], `falloff`, `timing`, `enabled`,
  `activatedAtS`). The phone evaluates them with `SourceModel` at its own GPS position.
- `clock`: `{elapsedS, atServerMillis, running}` while the exercise runs or is paused, else
  null. Exercise time now = `elapsedS + (serverNow − atServerMillis)/1000` while `running`.
  Sources emit only while `clock` is present.
Both are optional (protocol stays v1): older apps ignore them.

**Override:** `{channelId?, mode, value?, rampSeconds?, startedAtMillis}`
- `channelId` null applies to every channel without its own override.
- `FIXED` show `value` · `RAMP` move from the current reading to `value` over `rampSeconds`,
  starting at `startedAtMillis` (server time), so a reconnect resumes the ramp correctly ·
  `OFFSET` add `value` · `FAULT` show a sensor fault.
- Assigning a device to another exercise (or none) clears its overrides.

Example:

```json
{"type":"hello","appVersion":"0.3.0","protocolVersion":1,"model":"SM-G736U1"}
{"type":"welcome","serverTimeMillis":1760450000000,"serverVersion":"0.3.0","protocolVersion":1,
 "deviceId":"…","deviceName":"Entry 1","exercise":null,"overrides":{"version":0,"overrides":[]}}
{"type":"telemetry","samples":[{"ts":1760450001000,"lat":33.1,"lon":-117.3,"readings":{"O2":20.9,"CO":0.0},"alarms":[]}]}
{"type":"overrides","version":3,"overrides":[{"channelId":"CO","mode":"RAMP","value":250.0,"rampSeconds":30.0,"startedAtMillis":1760450005000}]}
```

---

## 3. Console socket — `wss://<host>/ws/console`

Authentication: the instructor session cookie. It is `SameSite=Strict`, so other sites can't
open the socket with an instructor's session.

| type | direction | fields |
|---|---|---|
| `watch` | console → server | `exerciseId`. Replaces any previous watch. Refused with `error` `not_found` for an exercise the instructor can't reach. |
| `unwatch` | console → server | |
| `devices` | server → console | `exerciseId`, `devices[]` of `{deviceId, name, online, appVersion?, lastSample?}`. Sent after `watch`. `online` = heard from in the last 30 s. |
| `telemetry` | server → console | `deviceId`, `sample` (newest sample of each device message) |
| `status` | server → console | `deviceId`, `online` (socket opened/closed) |
| `event` | server → console | `deviceId`, `event` |
| `exerciseChanged` | server → console | `exerciseId`: settings or the device list changed. Reload over REST. |
| `error` | server → console | `code`, `message` |

---

## 4. Between server instances (internal)

Every instance publishes the changes it makes to Redis (`simsensor:ex:<exerciseId>` or
`simsensor:dev:<deviceId>`) and pattern-subscribes to `simsensor:*`. Each instance delivers an
event only to the devices and consoles connected to it, reloading state from PostgreSQL. With
no `SIM_REDIS_URL` a single instance uses an in-memory bus.
