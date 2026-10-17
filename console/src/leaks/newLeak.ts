import type { Beacon, Channel, GasSource, LatLon, SourceShape } from "../api/endpoints";
import type { PlaceKind } from "../map/PlacementLayer";
import { defaultOxygenAmount, isOxygen } from "../meter/oxygen";

/** Starting sizes: big enough for phone GPS (a few metres off) to cross them clearly. */
const POINT_RADIUS_M = 30;
const PLUME_LENGTH_M = 150;
const PLUME_WIDTH_M = 50;
const ZONE_EDGE_M = 15;
/** Beacon reach: signal-based distance is rough, so a generous few metres. */
const BEACON_REACH_M = 8;

/** The gas a new leak releases: CO when the meter has it, else the first toxic or LEL channel. */
export function defaultGas(channels: Channel[]): Channel | undefined {
  return channels.find((c) => c.id === "CO") ?? channels.find((c) => !c.lowAlarmFalling) ?? channels[0];
}

/** A peak that clearly sets off the high alarm near the source (O₂: a drop well under A1). */
export function defaultPeak(channel: Channel): number {
  if (isOxygen(channel)) return defaultOxygenAmount(channel);
  const alarm = channel.highAlarm ?? channel.lowAlarm;
  const peak = alarm != null ? alarm * 2 : channel.rangeMax / 2;
  return Math.min(channel.rangeMax, Math.round(peak));
}

function shapeFor(kind: PlaceKind, points: LatLon[], beacons: Beacon[]): SourceShape {
  switch (kind) {
    case "point":
      return { type: "point", center: points[0], radiusM: POINT_RADIUS_M };
    case "plume":
      return { type: "plume", origin: points[0], lengthM: PLUME_LENGTH_M, widthM: PLUME_WIDTH_M, windFromDeg: null };
    case "zone":
      return { type: "zone", polygon: points, edgeM: ZONE_EDGE_M };
    case "beacon":
      return { type: "beacon", beaconId: beacons[0]?.id ?? "", radiusM: BEACON_REACH_M, anchor: points[0] };
  }
}

export const NEW_LEAK_ID = "";

/** An unsaved leak where the instructor clicked, ready for the form. */
export function newLeak(kind: PlaceKind, points: LatLon[], channels: Channel[], existing: GasSource[], beacons: Beacon[] = []): GasSource {
  const gas = defaultGas(channels);
  const kindName = { point: "Leak", plume: "Plume", zone: "Area", beacon: "Beacon" }[kind];
  return {
    id: NEW_LEAK_ID,
    name: `${kindName} ${existing.length + 1}`,
    shape: shapeFor(kind, points, beacons),
    gases: gas ? [{ gas: gas.id, peak: defaultPeak(gas) }] : [],
    falloff: "SMOOTH",
    timing: { activation: "IMMEDIATE", startAtS: 0, rampUpS: 0, growthMPerMin: 0, durationS: null, decayS: 0 },
    enabled: true,
  };
}
