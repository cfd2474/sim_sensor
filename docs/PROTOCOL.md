# Device protocol

The WebSocket link between a phone and the server. The source of truth is
`shared/src/commonMain/kotlin/com/taksolutions/simsensor/protocol/`. This document explains it.
When the two disagree, the code wins, and this file should be fixed.

## Transport

- Endpoint: `wss://<host>/ws/device` (`ws://` only for local debug builds).
- One JSON object per text frame. The `type` field selects the message.
- Unknown fields are ignored, so a newer peer may add fields without breaking an older one.
- `protocolVersion` (currently **1**) changes only for breaking changes.
- WebSocket ping every 15 s. The server drops the connection after 30 s without a pong.

## Handshake

```
device                                 server
  | -- hello {appVersion, deviceId?} -->  |
  | <-- welcome {serverTimeMillis, ...} --|
```

The device works out its clock offset as `serverTimeMillis − localNow` when it receives
`welcome`.

## Messages (protocol v1)

| type | direction | fields |
|---|---|---|
| `hello` | device → server | `deviceId` (string, optional until enrollment exists), `appVersion`, `protocolVersion` |
| `welcome` | server → device | `serverTimeMillis`, `serverVersion`, `protocolVersion` |
| `error` | server → device | `code` (`bad_message`, `unexpected_message`), `message` |

Example:

```json
{"type":"hello","deviceId":null,"appVersion":"0.1.0","protocolVersion":1}
{"type":"welcome","serverTimeMillis":1760450000000,"serverVersion":"0.1.0","protocolVersion":1}
```

Planned for Phase 1 (`docs/DESIGN.md` §10.3): `telemetry`, `event`, `profile`, `exercise`,
`override`, `command`, and authentication by device token.
