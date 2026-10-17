import type { Channel, PreviewGrid } from "../api/endpoints";
import { color } from "../map/PreviewLayer";
import { clock } from "./timing";

const swatch = (level: number) => {
  const [r, g, b, a] = color(level);
  return { background: `rgba(${r},${g},${b},${Math.max(a, 60) / 255})` };
};

/** Picks the gas shown on the map and explains the colours. */
export function PreviewControl({
  channels,
  gas,
  onGas,
  grid,
  error,
}: {
  channels: Channel[];
  gas: string;
  onGas: (gas: string) => void;
  grid: PreviewGrid | null;
  error?: string;
}) {
  const channel = channels.find((c) => c.id === gas);
  const falling = channel?.lowAlarmFalling;
  return (
    <div className="preview-control">
      <label className="row">
        Show
        <select value={gas} onChange={(e) => onGas(e.target.value)}>
          <option value="">no gas overlay</option>
          {channels.map((c) => (
            <option key={c.id} value={c.id}>
              {c.label}
            </option>
          ))}
        </select>
      </label>
      {channel && (
        <div className="legend small">
          {falling ? (
            <>
              <span><i style={swatch(0.6)} /> O₂ lowered or raised</span>
              <span>
                <i style={swatch(3)} /> at or below A1 {channel.lowAlarm}
                {channel.highAlarm != null && <>, or at or above A2 {channel.highAlarm}</>}
              </span>
            </>
          ) : (
            <>
              <span><i style={swatch(0.6)} /> below A1</span>
              {channel.lowAlarm != null && <span><i style={swatch(2)} /> ≥ A1 {channel.lowAlarm}</span>}
              {channel.highAlarm != null && <span><i style={swatch(3)} /> ≥ A2 {channel.highAlarm}</span>}
            </>
          )}
          {grid && (
            <span className="muted">
              {falling ? `lowest ${(channel.baseline + Math.min(0, ...grid.values)).toFixed(1)}` : `highest ${grid.max.toFixed(0)}`} {channel.unit} in view ·{" "}
              {grid.atS == null ? "planning view: every leak on, full strength" : `now (exercise time ${clock(grid.atS)})`}
            </span>
          )}
          {error && <span className="error-text">{error}</span>}
        </div>
      )}
    </div>
  );
}
