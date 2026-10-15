import type { Units } from "../api/endpoints";

const FEET_PER_METRE = 3.28084;
const MPH_PER_MPS = 2.23694;

/** A quantity stored in SI and shown in the admin's display units. */
export interface UnitScale {
  label: string;
  /** Stored (SI) value → display value. */
  toDisplay: (si: number) => number;
  /** Display value → stored (SI) value. */
  fromDisplay: (shown: number) => number;
}

export interface UnitSystem {
  units: Units;
  distance: UnitScale;
  speed: UnitScale;
  /** "120 ft" / "37 m", rounded for reading at a glance. */
  formatDistance: (metres: number) => string;
  formatSpeed: (mps: number) => string;
}

function scale(label: string, factor: number): UnitScale {
  return { label, toDisplay: (si) => si * factor, fromDisplay: (shown) => shown / factor };
}

/** Conversions for [units]. Data is always metric; only what the instructor sees changes. */
export function unitSystem(units: Units): UnitSystem {
  const imperial = units === "IMPERIAL";
  const distance = imperial ? scale("ft", FEET_PER_METRE) : scale("m", 1);
  const speed = imperial ? scale("mph", MPH_PER_MPS) : scale("m/s", 1);
  return {
    units,
    distance,
    speed,
    formatDistance: (m) => `${Math.round(distance.toDisplay(m))} ${distance.label}`,
    formatSpeed: (mps) => `${round1(speed.toDisplay(mps))} ${speed.label}`,
  };
}

/** One decimal, without a trailing ".0". */
export function round1(value: number): number {
  return Math.round(value * 10) / 10;
}
