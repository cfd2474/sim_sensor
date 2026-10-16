import { useEffect, useRef, useState } from "react";

// Console WebSocket messages (docs/PROTOCOL.md §3; Kotlin source of truth:
// shared/.../protocol/ConsoleMessages.kt).

export interface TelemetrySample {
  ts: number;
  lat?: number | null;
  lon?: number | null;
  accuracyM?: number | null;
  battery?: number | null;
  readings: Record<string, number>;
  alarms: string[];
  /** The exercise's beacons the phone hears (phones v0.17.0+). */
  beacons?: { beaconId: string; rssi: number; distanceM: number; presence?: number | null }[];
}

export interface DeviceEvent {
  ts: number;
  kind: string;
  channelId?: string | null;
  detail?: string | null;
}

type ServerMessage =
  | { type: "devices"; exerciseId: string; devices: { deviceId: string; name: string; online: boolean; appVersion?: string | null; lastSample?: TelemetrySample | null }[] }
  | { type: "telemetry"; deviceId: string; sample: TelemetrySample }
  | { type: "status"; deviceId: string; online: boolean }
  | { type: "event"; deviceId: string; event: DeviceEvent }
  | { type: "exerciseChanged"; exerciseId: string }
  | { type: "error"; code: string; message: string };

export interface LiveDevice {
  deviceId: string;
  name: string;
  online: boolean;
  last?: TelemetrySample;
  /** Recent positions, oldest first, for the map trail. */
  trail: [number, number][];
  /** Local time the last sample arrived. */
  heardAt?: number;
}

export interface LiveState {
  connected: boolean;
  devices: Record<string, LiveDevice>;
  /** Recent device events (alarms, acks), newest first. */
  events: { deviceId: string; event: DeviceEvent; at: number }[];
  /** Increments when the exercise or its device list changed: reload REST data. */
  changed: number;
  error?: string;
}

const TRAIL_POINTS = 120;
const EVENT_LIMIT = 50;

/** Live devices of one exercise over `/ws/console`, reconnecting with backoff. */
export function useExerciseLive(exerciseId: string): LiveState {
  const [state, setState] = useState<LiveState>({ connected: false, devices: {}, events: [], changed: 0 });
  const retry = useRef(0);

  useEffect(() => {
    let socket: WebSocket | null = null;
    let timer: number | undefined;
    let closed = false;

    const connect = () => {
      const scheme = window.location.protocol === "https:" ? "wss" : "ws";
      socket = new WebSocket(`${scheme}://${window.location.host}/ws/console`);
      socket.onopen = () => {
        retry.current = 0;
        setState((s) => ({ ...s, connected: true, error: undefined }));
        socket?.send(JSON.stringify({ type: "watch", exerciseId }));
      };
      socket.onmessage = (e) => {
        const message = JSON.parse(e.data as string) as ServerMessage;
        setState((s) => apply(s, message));
      };
      socket.onclose = () => {
        setState((s) => ({ ...s, connected: false }));
        if (closed) return;
        const delay = Math.min(30_000, 1000 * 2 ** retry.current++) * (0.5 + Math.random() / 2);
        timer = window.setTimeout(connect, delay);
      };
    };
    connect();

    return () => {
      closed = true;
      window.clearTimeout(timer);
      socket?.close();
    };
  }, [exerciseId]);

  return state;
}

function apply(state: LiveState, message: ServerMessage): LiveState {
  switch (message.type) {
    case "devices": {
      const devices: Record<string, LiveDevice> = {};
      for (const d of message.devices) {
        const previous = state.devices[d.deviceId];
        const last = d.lastSample ?? previous?.last;
        devices[d.deviceId] = {
          deviceId: d.deviceId,
          name: d.name,
          online: d.online,
          last: last ?? undefined,
          trail: previous?.trail ?? (last && last.lat != null && last.lon != null ? [[last.lon, last.lat]] : []),
          heardAt: previous?.heardAt ?? (d.online ? Date.now() : undefined),
        };
      }
      return { ...state, devices };
    }
    case "telemetry": {
      const device = state.devices[message.deviceId];
      if (!device) return state;
      const s = message.sample;
      const trail = s.lat != null && s.lon != null ? [...device.trail, [s.lon, s.lat] as [number, number]].slice(-TRAIL_POINTS) : device.trail;
      return { ...state, devices: { ...state.devices, [message.deviceId]: { ...device, last: s, trail, online: true, heardAt: Date.now() } } };
    }
    case "status": {
      const device = state.devices[message.deviceId];
      if (!device) return { ...state, changed: state.changed + 1 };
      return { ...state, devices: { ...state.devices, [message.deviceId]: { ...device, online: message.online } } };
    }
    case "event":
      return { ...state, events: [{ deviceId: message.deviceId, event: message.event, at: Date.now() }, ...state.events].slice(0, EVENT_LIMIT) };
    case "exerciseChanged":
      return { ...state, changed: state.changed + 1 };
    case "error":
      return { ...state, error: message.message };
  }
}

/** Severity of a device's alarms, for colors: 0 ok, 1 low, 2 high/STEL/TWA/over-range/fault. */
export function alarmSeverity(alarms: string[] | undefined): 0 | 1 | 2 {
  if (!alarms?.length) return 0;
  return alarms.some((a) => !a.endsWith(":LOW")) ? 2 : 1;
}

/** A device is stale when it hasn't reported for 15 s. */
export function isStale(device: LiveDevice, now = Date.now()): boolean {
  return !device.online || device.heardAt === undefined || now - device.heardAt > 15_000;
}
