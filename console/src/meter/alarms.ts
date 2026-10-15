import type { Channel } from "../api/endpoints";

type AlarmField = "lowAlarm" | "highAlarm" | "stelAlarm" | "twaAlarm";
type LatchField = "lowAlarmLatching" | "highAlarmLatching" | "stelAlarmLatching" | "twaAlarmLatching";

export interface AlarmKind {
  field: AlarmField;
  latch: LatchField;
  /** What people see: the low and high alarms are A1 and A2 (industry names). */
  label: string;
  /** Latching default when a channel doesn't say (matches the shared Kotlin model). */
  latchDefault: boolean;
}

/** Every alarm a sensor can have, in display order. */
export const ALARMS: AlarmKind[] = [
  { field: "lowAlarm", latch: "lowAlarmLatching", label: "A1", latchDefault: false },
  { field: "highAlarm", latch: "highAlarmLatching", label: "A2", latchDefault: true },
  { field: "stelAlarm", latch: "stelAlarmLatching", label: "STEL", latchDefault: true },
  { field: "twaAlarm", latch: "twaAlarmLatching", label: "TWA", latchDefault: true },
];

export function latches(channel: Channel, kind: AlarmKind): boolean {
  return channel[kind.latch] ?? kind.latchDefault;
}

const LEVEL_LABELS: Record<string, string> = { LOW: "A1", HIGH: "A2", OVER_RANGE: "OR" };

/** "CO:HIGH" → "CO A2" (wire codes keep the low/high names). */
export function alarmLabel(code: string): string {
  const [channel, level] = code.split(":");
  return level ? `${channel} ${LEVEL_LABELS[level] ?? level}` : code;
}

/** Labels for a list of codes, e.g. an "alarm.raised" event's "CO:HIGH, H2S:LOW". */
export function alarmLabels(codes: string): string {
  return codes
    .split(/,\s*/)
    .map((c) => (c.includes(":") ? alarmLabel(c) : c))
    .join(", ");
}

export const RESPONSE_HELP =
  "Response time: seconds for the meter to show 90 % of a change in gas. The reading moves fastest when far from the real value and slows as it gets close, like a real sensor. Real meters quote about 10–15 s; the built-in setup uses 5 s.";

export const LATCH_HELP =
  "A latching alarm stays on until the trainee presses ACK, even after the gas clears. A non-latching alarm clears itself. A2 replaces A1 on the meter.";
