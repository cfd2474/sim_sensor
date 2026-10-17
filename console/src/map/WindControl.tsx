import { useEffect, useRef, useState } from "react";
import { Api, type Exercise } from "../api/endpoints";
import { ErrorBox } from "../components/ui";
import { DEFAULT_WIND_FROM_DEG } from "../leaks/geometry";
import { round1 } from "../units/units";
import { useUnits } from "../units/UnitsContext";

const COMPASS = ["N", "NE", "E", "SE", "S", "SW", "W", "NW"];

/** Changes are saved this long after the last move, so a drag doesn't flood the phones. */
const SAVE_DELAY_MS = 400;

export function compassName(deg: number): string {
  return COMPASS[Math.round((((deg % 360) + 360) % 360) / 45) % 8];
}

type WindChange = { windDirDeg?: number; windSpeedMps?: number };

/**
 * Wind badge on the map: an arrow pointing where the gas drifts, and the direction the wind
 * comes from. Opens a small editor whose changes apply live (operator 2026-10-16: no Save
 * button): plumes and phones follow a moment after the slider stops.
 */
export function WindControl({ exercise, onSaved }: { exercise: Exercise; onSaved: (x: Exercise) => void }) {
  const units = useUnits();
  const from = exercise.windDirDeg ?? DEFAULT_WIND_FROM_DEG;
  const [open, setOpen] = useState(false);
  const [dir, setDir] = useState(from);
  const [speed, setSpeed] = useState("");
  const [status, setStatus] = useState<"idle" | "saving" | "saved">("idle");
  const [error, setError] = useState<unknown>();
  const pending = useRef<WindChange>({});
  const timer = useRef<number | undefined>(undefined);

  useEffect(() => {
    if (open) return;
    setDir(exercise.windDirDeg ?? DEFAULT_WIND_FROM_DEG);
    setSpeed(exercise.windSpeedMps != null ? String(round1(units.speed.toDisplay(exercise.windSpeedMps))) : "");
  }, [exercise.windDirDeg, exercise.windSpeedMps, open, units.speed]);

  const flush = async () => {
    window.clearTimeout(timer.current);
    timer.current = undefined;
    const change = pending.current;
    pending.current = {};
    if (change.windDirDeg == null && change.windSpeedMps == null) return;
    setStatus("saving");
    try {
      onSaved(await Api.updateExercise(exercise.id, change));
      setError(undefined);
      setStatus(timer.current ? "saving" : "saved");
    } catch (e) {
      setError(e);
      setStatus("idle");
    }
  };

  /** Queues a change and saves it once the control has been still for [SAVE_DELAY_MS]. */
  const queue = (change: WindChange) => {
    pending.current = { ...pending.current, ...change };
    setStatus("saving");
    window.clearTimeout(timer.current);
    timer.current = window.setTimeout(() => void flush(), SAVE_DELAY_MS);
  };

  // Leaving the page or closing the editor must not drop a change still waiting to be saved.
  useEffect(() => () => void flush(), []); // eslint-disable-line react-hooks/exhaustive-deps

  const setDirection = (d: number) => {
    setDir(d);
    queue({ windDirDeg: d });
  };

  const shown = open ? dir : from;
  return (
    <div className="wind">
      <button className="wind-badge" onClick={() => (open ? void flush().then(() => setOpen(false)) : setOpen(true))} title="Wind: click to change">
        <span className="wind-arrow" style={{ transform: `rotate(${shown + 180}deg)` }}>
          ↑
        </span>
        <span>
          Wind from {compassName(shown)} ({Math.round(shown)}°)
          {exercise.windDirDeg == null && !open && <em> default</em>}
          {exercise.windSpeedMps != null && !open && <> · {units.formatSpeed(exercise.windSpeedMps)}</>}
        </span>
      </button>
      {open && (
        <div className="wind-editor stack">
          <label>
            Wind comes from: {compassName(dir)} ({dir}°)
            <input type="range" min={0} max={355} step={5} value={dir} onChange={(e) => setDirection(Number(e.target.value))} />
          </label>
          <div className="row wrap">
            {[0, 90, 180, 270].map((d) => (
              <button key={d} className="btn secondary small" onClick={() => setDirection(d)}>
                {compassName(d)}
              </button>
            ))}
          </div>
          <label className="row">
            Speed
            <input
              type="number"
              min={0}
              step="any"
              value={speed}
              onChange={(e) => {
                setSpeed(e.target.value);
                const v = Number(e.target.value);
                if (e.target.value !== "" && Number.isFinite(v) && v >= 0) queue({ windSpeedMps: units.speed.fromDisplay(v) });
              }}
              size={5}
            />
            {units.speed.label}
          </label>
          <p className="muted small">
            Changes apply at once: plumes drift downwind and phones follow. The speed is shown to instructors only.
          </p>
          <ErrorBox error={error} />
          <div className="row">
            <button className="btn secondary" onClick={() => void flush().then(() => setOpen(false))}>
              Done
            </button>
            <span className="muted small" aria-live="polite">
              {status === "saving" ? "Saving…" : status === "saved" ? "Saved" : ""}
            </span>
          </div>
        </div>
      )}
    </div>
  );
}
