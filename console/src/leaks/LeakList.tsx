import { useEffect, useState } from "react";
import { Api, type Beacon, type Channel, type Exercise, type GasSource } from "../api/endpoints";
import { AsyncButton, Card } from "../components/ui";
import type { PlaceKind } from "../map/PlacementLayer";
import { isOxygen, oxygenAmountText } from "../meter/oxygen";
import { INERT_ID, o2WithInert } from "../meter/inert";
import { useUnits } from "../units/UnitsContext";
import { clock, emitting, exerciseElapsedS, leakStatus } from "./timing";

function sizeText(s: GasSource, formatDistance: (m: number) => string, beaconName: (id: string) => string): string {
  switch (s.shape.type) {
    case "point":
      return `round, ${formatDistance(s.shape.radiusM)} reach`;
    case "plume":
      return `plume ${formatDistance(s.shape.lengthM)} × ${formatDistance(s.shape.widthM)}`;
    case "zone":
      return `area, ${s.shape.polygon.length} corners`;
    case "beacon": {
      const id = s.shape.beaconId;
      return `beacon ${beaconName(id)}, ${formatDistance(s.shape.radiusM)} reach`;
    }
  }
}

/** The exercise's leaks: what each releases and what it is doing now, with quick actions. */
export function LeakList({
  exercise,
  sources,
  channels,
  selectedId,
  placing,
  beacons,
  onSelect,
  onEdit,
  onPlace,
  onChanged,
}: {
  exercise: Exercise;
  sources: GasSource[];
  channels: Channel[];
  selectedId: string | null;
  placing: PlaceKind | null;
  /** The exercise's beacons (a beacon leak needs one). */
  beacons: Beacon[];
  onSelect: (id: string) => void;
  onEdit: (source: GasSource) => void;
  onPlace: (kind: PlaceKind) => void;
  onChanged: () => Promise<void>;
}) {
  const units = useUnits();
  const live = emitting(exercise);
  const [now, setNow] = useState(Date.now());

  // Leak states change with the exercise clock.
  useEffect(() => {
    if (!live) return;
    const timer = window.setInterval(() => setNow(Date.now()), 1000);
    return () => window.clearInterval(timer);
  }, [live]);

  const t = exerciseElapsedS(exercise, now);
  const unitOf = (gas: string) => channels.find((c) => c.id === gas)?.unit ?? "";

  return (
    <Card
      title={`Leaks (${sources.length})`}
      actions={live && <span className="muted small">Exercise time {clock(t)}</span>}
    >
      <div className="row wrap">
        <span className="small">Add:</span>
        {(["point", "plume", "zone", "beacon"] as const).map((k) => (
          <button
            key={k}
            className={`btn small ${placing === k ? "primary" : "secondary"}`}
            disabled={k === "beacon" && beacons.length === 0}
            title={k === "beacon" && beacons.length === 0 ? "Add a beacon to this exercise on the Devices tab first" : undefined}
            onClick={() => onPlace(k)}
          >
            {{ point: "Round leak", plume: "Plume", zone: "Area", beacon: "Beacon" }[k]}
          </button>
        ))}
      </div>
      {sources.length === 0 ? (
        <p className="muted small">No leaks yet. Pick a kind above, then click the map.</p>
      ) : (
        <ul className="leak-list">
          {sources.map((s) => {
            const status = leakStatus(s, exercise, t);
            return (
              <li key={s.id} className={`leak-row phase-${status.phase}${s.id === selectedId ? " selected" : ""}`} onClick={() => onSelect(s.id)}>
                <div className="row">
                  <strong>{s.name}</strong>
                  <span className={`leak-state phase-${status.phase}`}>{status.label}</span>
                  <span className="spacer" />
                  <button
                    className="btn secondary small"
                    onClick={(e) => {
                      e.stopPropagation();
                      onEdit(s);
                    }}
                  >
                    Edit
                  </button>
                </div>
                <span className="muted small">
                  {s.gases.map((g) => gasText(g, channels, unitOf)).join(", ")} · {sizeText(s, units.formatDistance, (id) => beacons.find((b) => b.id === id)?.label ?? "(removed)")}
                </span>
                <div className="row wrap" onClick={(e) => e.stopPropagation()}>
                  {status.phase === "manual" && (
                    <AsyncButton onClick={async () => { await Api.activateSource(exercise.id, s.id); await onChanged(); }}>
                      Start leak now
                    </AsyncButton>
                  )}
                  <AsyncButton
                    kind="secondary"
                    onClick={async () => {
                      await Api.updateSource(exercise.id, { ...s, enabled: !s.enabled });
                      await onChanged();
                    }}
                  >
                    {s.enabled ? "Turn off" : "Turn on"}
                  </AsyncButton>
                </div>
              </li>
            );
          })}
        </ul>
      )}
      {!live && sources.length > 0 && <p className="muted small">Leaks release gas only while the exercise runs.</p>}
    </Card>
  );
}

/** "CO 400 PPM", "O2 4.9 below baseline", "inert gas 20 %vol (O₂ 16.7)". */
function gasText(g: { gas: string; peak: number }, channels: Channel[], unitOf: (gas: string) => string): string {
  if (g.gas === INERT_ID) return `inert gas ${g.peak} %vol (O₂ ${o2WithInert(channels, g.peak)})`;
  if (isOxygen(channels.find((c) => c.id === g.gas))) return `${g.gas} ${oxygenAmountText(g.peak)}`;
  return `${g.gas} ${g.peak} ${unitOf(g.gas)}`;
}
