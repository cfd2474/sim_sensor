import { useEffect, useState } from "react";
import { Api, type Exercise } from "../api/endpoints";
import { AsyncButton, ErrorBox } from "../components/ui";
import { DEFAULT_WIND_FROM_DEG } from "../leaks/geometry";
import { round1 } from "../units/units";
import { useUnits } from "../units/UnitsContext";

const COMPASS = ["N", "NE", "E", "SE", "S", "SW", "W", "NW"];

export function compassName(deg: number): string {
  return COMPASS[Math.round((((deg % 360) + 360) % 360) / 45) % 8];
}

/**
 * Wind badge on the map: an arrow pointing where the gas drifts, and the direction the wind
 * comes from. Opens a small editor; plumes without their own wind follow it at once.
 */
export function WindControl({ exercise, onSaved }: { exercise: Exercise; onSaved: (x: Exercise) => void }) {
  const units = useUnits();
  const from = exercise.windDirDeg ?? DEFAULT_WIND_FROM_DEG;
  const [open, setOpen] = useState(false);
  const [dir, setDir] = useState(from);
  const [speed, setSpeed] = useState("");
  const [error, setError] = useState<unknown>();

  useEffect(() => {
    if (open) return;
    setDir(exercise.windDirDeg ?? DEFAULT_WIND_FROM_DEG);
    setSpeed(exercise.windSpeedMps != null ? String(round1(units.speed.toDisplay(exercise.windSpeedMps))) : "");
  }, [exercise.windDirDeg, exercise.windSpeedMps, open, units.speed]);

  const shown = open ? dir : from;
  return (
    <div className="wind">
      <button className="wind-badge" onClick={() => setOpen(!open)} title="Wind: click to change">
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
            <input type="range" min={0} max={355} step={5} value={dir} onChange={(e) => setDir(Number(e.target.value))} />
          </label>
          <div className="row wrap">
            {[0, 90, 180, 270].map((d) => (
              <button key={d} className="btn secondary small" onClick={() => setDir(d)}>
                {compassName(d)}
              </button>
            ))}
          </div>
          <label className="row">
            Speed
            <input type="number" min={0} step="any" value={speed} onChange={(e) => setSpeed(e.target.value)} size={5} />
            {units.speed.label}
          </label>
          <p className="muted small">Plumes drift downwind. The speed is shown to instructors only.</p>
          <ErrorBox error={error} />
          <div className="row">
            <AsyncButton
              onClick={async () => {
                setError(undefined);
                onSaved(
                  await Api.updateExercise(exercise.id, {
                    windDirDeg: dir,
                    windSpeedMps: speed === "" ? undefined : units.speed.fromDisplay(Number(speed)),
                  }),
                );
                setOpen(false);
              }}
              onError={setError}
            >
              Apply
            </AsyncButton>
            <button className="btn secondary" onClick={() => setOpen(false)}>
              Cancel
            </button>
          </div>
        </div>
      )}
    </div>
  );
}
