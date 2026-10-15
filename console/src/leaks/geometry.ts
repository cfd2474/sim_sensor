import type { GasSource, LatLon, SourceShape } from "../api/endpoints";

// Outlines of leaks for the map. They mirror where the shared model
// (shared/.../source/SourceModel.kt) reaches zero; the concentration itself comes from the
// server's preview, never from here.

const EARTH_RADIUS_M = 6_371_008.8;
const DEG = Math.PI / 180;
/** Must match SourceModel: default wind (from the west), plume width near the origin, upwind spread. */
export const DEFAULT_WIND_FROM_DEG = 270;
const MIN_WIDTH_FRACTION = 0.15;
const UPWIND_FRACTION = 0.25;

export type Ring = [number, number][]; // [lon, lat], closed

/** The point [eastM] / [northM] metres from [origin] (flat-earth, fine for a training site). */
export function offset(origin: LatLon, eastM: number, northM: number): LatLon {
  const lat = origin.lat + (northM / EARTH_RADIUS_M) / DEG;
  const lon = origin.lon + (eastM / (EARTH_RADIUS_M * Math.cos(origin.lat * DEG))) / DEG;
  return { lat, lon };
}

/** Metres east and north of [origin]. */
export function toEnu(origin: LatLon, p: LatLon): { east: number; north: number } {
  return {
    east: (p.lon - origin.lon) * DEG * EARTH_RADIUS_M * Math.cos(origin.lat * DEG),
    north: (p.lat - origin.lat) * DEG * EARTH_RADIUS_M,
  };
}

function ring(points: LatLon[]): Ring {
  const r = points.map((p) => [p.lon, p.lat] as [number, number]);
  if (r.length) r.push(r[0]);
  return r;
}

function circle(center: LatLon, radiusM: number, steps = 64): Ring {
  const points: LatLon[] = [];
  for (let i = 0; i < steps; i++) {
    const a = (2 * Math.PI * i) / steps;
    points.push(offset(center, radiusM * Math.sin(a), radiusM * Math.cos(a)));
  }
  return ring(points);
}

/** Downwind teardrop of a plume: where the model's reading reaches zero. */
function plume(origin: LatLon, lengthM: number, widthM: number, windFromDeg: number): Ring {
  const toward = (windFromDeg + 180) * DEG;
  const axis = { e: Math.sin(toward), n: Math.cos(toward) };
  const side = { e: axis.n, n: -axis.e }; // to the right of the axis
  const at = (along: number, across: number) => offset(origin, axis.e * along + side.e * across, axis.n * along + side.n * across);
  const steps = 24;
  const right: LatLon[] = [];
  const left: LatLon[] = [];
  for (let i = 0; i <= steps; i++) {
    const x = i / steps;
    const half = (widthM / 2) * Math.max(MIN_WIDTH_FRACTION, Math.sqrt(x));
    right.push(at(x * lengthM, half));
    left.push(at(x * lengthM, -half));
  }
  // The small round spread behind the origin, swept from the left side round to the right.
  const back = widthM * UPWIND_FRACTION;
  const behind: LatLon[] = [];
  for (let i = 1; i < 12; i++) {
    const a = -Math.PI / 2 - (Math.PI * i) / 12; // angle from the downwind axis
    behind.push(at(back * Math.cos(a), back * Math.sin(a)));
  }
  return ring([...right, ...left.reverse(), ...behind]);
}

/** Map outline of a shape (in the given exercise wind). */
export function outline(shape: SourceShape, exerciseWindFromDeg: number | null | undefined): Ring {
  switch (shape.type) {
    case "point":
      return circle(shape.center, shape.radiusM);
    case "plume":
      return plume(shape.origin, shape.lengthM, shape.widthM, shape.windFromDeg ?? exerciseWindFromDeg ?? DEFAULT_WIND_FROM_DEG);
    case "zone":
      return ring(shape.polygon);
  }
}

/** Where the leak is anchored (the handle that moves it). */
export function anchor(shape: SourceShape): LatLon {
  switch (shape.type) {
    case "point":
      return shape.center;
    case "plume":
      return shape.origin;
    case "zone": {
      const n = shape.polygon.length;
      return {
        lat: shape.polygon.reduce((s, p) => s + p.lat, 0) / n,
        lon: shape.polygon.reduce((s, p) => s + p.lon, 0) / n,
      };
    }
  }
}

/** The same shape with its anchor at [to]; zones keep their form on the ground. */
export function moveTo(shape: SourceShape, to: LatLon): SourceShape {
  switch (shape.type) {
    case "point":
      return { ...shape, center: to };
    case "plume":
      return { ...shape, origin: to };
    case "zone": {
      const from = anchor(shape);
      return {
        ...shape,
        polygon: shape.polygon.map((p) => {
          const d = toEnu(from, p);
          return offset(to, d.east, d.north);
        }),
      };
    }
  }
}

/** Every outline vertex of all leaks, for zooming the map to them. */
export function extent(sources: GasSource[], windFromDeg: number | null | undefined): [number, number][] {
  return sources.flatMap((s) => outline(s.shape, windFromDeg));
}
