import type { Exercise, GasSource } from "../api/endpoints";

// Leak timing for the instructor's list. Mirrors SourceModel.sinceStart / strengthAt in
// shared/.../source/SourceModel.kt (the phones and the preview use that one).

/** Exercise time in seconds now (0 before the first start). */
export function exerciseElapsedS(exercise: Exercise, now = Date.now()): number {
  const base = exercise.clockElapsedS ?? 0;
  if (!exercise.clockResumedAt) return base;
  return base + Math.max(0, now - new Date(exercise.clockResumedAt).getTime()) / 1000;
}

/** Whether leaks emit at all: only while the exercise runs or is paused. */
export function emitting(exercise: Exercise): boolean {
  return exercise.state === "running" || exercise.state === "paused";
}

export type LeakPhase = "off" | "planned" | "waiting" | "manual" | "ramping" | "active" | "fading" | "over";

export interface LeakStatus {
  phase: LeakPhase;
  /** 0..1 of the peak. */
  strength: number;
  label: string;
}

function sinceStart(source: GasSource, t: number): number | null {
  const timing = source.timing;
  const start = timing?.activation === "AT_TIME" ? timing.startAtS : timing?.activation === "MANUAL" ? source.activatedAtS : 0;
  if (start == null) return null;
  return t >= start ? t - start : null;
}

/** What the leak is doing at exercise time [t]. */
export function leakStatus(source: GasSource, exercise: Exercise, t: number): LeakStatus {
  if (!source.enabled) return { phase: "off", strength: 0, label: "Off" };
  if (!emitting(exercise)) return { phase: "planned", strength: 0, label: startLabel(source) };
  const timing = source.timing;
  const since = sinceStart(source, t);
  if (since == null) {
    if (timing?.activation === "MANUAL") return { phase: "manual", strength: 0, label: "Waiting for you" };
    return { phase: "waiting", strength: 0, label: `Starts in ${clock((timing?.startAtS ?? 0) - t)}` };
  }
  const rampUp = timing?.rampUpS ?? 0;
  const ramp = rampUp > 0 ? Math.min(1, since / rampUp) : 1;
  const duration = timing?.durationS;
  if (duration == null || since < duration) {
    return ramp < 1 ? { phase: "ramping", strength: ramp, label: `Rising ${Math.round(ramp * 100)} %` } : { phase: "active", strength: 1, label: "Leaking" };
  }
  const decay = timing?.decayS ?? 0;
  const atStop = rampUp > 0 ? Math.min(1, duration / rampUp) : 1;
  const strength = decay > 0 ? Math.max(0, atStop * (1 - (since - duration) / decay)) : 0;
  return strength > 0 ? { phase: "fading", strength, label: `Fading ${Math.round(strength * 100)} %` } : { phase: "over", strength: 0, label: "Stopped" };
}

function startLabel(source: GasSource): string {
  switch (source.timing?.activation) {
    case "AT_TIME":
      return `Starts at ${clock(source.timing.startAtS)}`;
    case "MANUAL":
      return "Starts when you trigger it";
    default:
      return "Starts with the exercise";
  }
}

/** Seconds as m:ss or h:mm:ss. */
export function clock(seconds: number): string {
  const s = Math.max(0, Math.round(seconds));
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  const ss = String(s % 60).padStart(2, "0");
  return h > 0 ? `${h}:${String(m).padStart(2, "0")}:${ss}` : `${m}:${ss}`;
}
