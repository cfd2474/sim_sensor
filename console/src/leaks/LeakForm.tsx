import { useState } from "react";
import type { Channel, GasSource, SourceShape, SourceTiming } from "../api/endpoints";
import { AsyncButton, Card, ErrorBox } from "../components/ui";
import { useUnits } from "../units/UnitsContext";
import { NumberInput } from "./NumberInput";
import { defaultPeak, NEW_LEAK_ID } from "./newLeak";
import { clock } from "./timing";

const SHAPE_NAMES = { point: "Round leak", plume: "Plume (drifts downwind)", zone: "Area" };

/** Edits one leak. Changes show on the map at once; phones get them when saved. */
export function LeakForm({
  draft,
  channels,
  onChange,
  onSave,
  onCancel,
  onDelete,
}: {
  draft: GasSource;
  channels: Channel[];
  onChange: (draft: GasSource) => void;
  onSave: () => Promise<void>;
  onCancel: () => void;
  onDelete: () => Promise<void>;
}) {
  const units = useUnits();
  const [error, setError] = useState<unknown>();
  const timing: SourceTiming = draft.timing ?? { activation: "IMMEDIATE", startAtS: 0, rampUpS: 0, growthMPerMin: 0, durationS: null, decayS: 0 };
  const isNew = draft.id === NEW_LEAK_ID;

  const set = (patch: Partial<GasSource>) => onChange({ ...draft, ...patch });
  const setShape = (patch: Partial<SourceShape>) => set({ shape: { ...draft.shape, ...patch } as SourceShape });
  const setTiming = (patch: Partial<SourceTiming>) => set({ timing: { ...timing, ...patch } });
  const distance = { toDisplay: units.distance.toDisplay, fromDisplay: units.distance.fromDisplay };
  const unused = channels.filter((c) => !draft.gases.some((g) => g.gas === c.id));
  const unitOf = (gas: string) => channels.find((c) => c.id === gas)?.unit ?? "";

  return (
    <Card title={isNew ? "New leak" : `Edit ${draft.name}`}>
      <div className="stack leak-form">
        <div className="row wrap">
          <label className="grow">
            Name
            <input value={draft.name} maxLength={80} onChange={(e) => set({ name: e.target.value })} />
          </label>
          <span className="badge subtle">{SHAPE_NAMES[draft.shape.type]}</span>
        </div>

        <fieldset>
          <legend>Gases at the source</legend>
          {draft.gases.map((g, i) => (
            <div className="row" key={g.gas}>
              <select
                value={g.gas}
                onChange={(e) => {
                  const channel = channels.find((c) => c.id === e.target.value)!;
                  set({ gases: draft.gases.map((x, j) => (j === i ? { gas: channel.id, peak: defaultPeak(channel) } : x)) });
                }}
              >
                {[channels.find((c) => c.id === g.gas), ...unused].filter(Boolean).map((c) => (
                  <option key={c!.id} value={c!.id}>
                    {c!.label}
                  </option>
                ))}
              </select>
              <NumberInput value={g.peak} onChange={(v) => set({ gases: draft.gases.map((x, j) => (j === i ? { ...x, peak: v ?? 0 } : x)) })} />
              <span className="muted small">{unitOf(g.gas)} peak</span>
              {draft.gases.length > 1 && (
                <button className="chip-x" title="Remove gas" onClick={() => set({ gases: draft.gases.filter((_, j) => j !== i) })}>
                  ×
                </button>
              )}
            </div>
          ))}
          {unused.length > 0 && (
            <button className="link small" onClick={() => set({ gases: [...draft.gases, { gas: unused[0].id, peak: defaultPeak(unused[0]) }] })}>
              + add a gas
            </button>
          )}
        </fieldset>

        <fieldset>
          <legend>Size</legend>
          <div className="row wrap">
            {draft.shape.type === "point" && (
              <label className="row">
                Reaches
                <NumberInput value={draft.shape.radiusM} onChange={(v) => setShape({ radiusM: v ?? 0 })} {...distance} />
                {units.distance.label} from the centre
              </label>
            )}
            {draft.shape.type === "plume" && (
              <>
                <label className="row">
                  Length
                  <NumberInput value={draft.shape.lengthM} onChange={(v) => setShape({ lengthM: v ?? 0 })} {...distance} />
                  {units.distance.label}
                </label>
                <label className="row">
                  Width
                  <NumberInput value={draft.shape.widthM} onChange={(v) => setShape({ widthM: v ?? 0 })} {...distance} />
                  {units.distance.label}
                </label>
              </>
            )}
            {draft.shape.type === "zone" && (
              <label className="row">
                Spreads
                <NumberInput value={draft.shape.edgeM} onChange={(v) => setShape({ edgeM: v ?? 0 })} {...distance} />
                {units.distance.label} beyond the area (full strength inside)
              </label>
            )}
          </div>
          {draft.shape.type === "plume" && (
            <label className="check">
              <input
                type="checkbox"
                checked={draft.shape.windFromDeg != null}
                onChange={(e) => setShape({ windFromDeg: e.target.checked ? 270 : null })}
              />
              Own wind direction
              {draft.shape.windFromDeg != null && (
                <>
                  : from
                  <NumberInput value={draft.shape.windFromDeg} onChange={(v) => setShape({ windFromDeg: v == null ? null : ((v % 360) + 360) % 360 })} size={4} />°
                </>
              )}
              {draft.shape.windFromDeg == null && <span className="muted small">(follows the exercise wind)</span>}
            </label>
          )}
          <label className="row">
            Strength falls off
            <select value={draft.falloff} onChange={(e) => set({ falloff: e.target.value as GasSource["falloff"] })}>
              <option value="SMOOTH">smoothly (default)</option>
              <option value="LINEAR">evenly</option>
              <option value="STEEP">steeply: strong only near the source</option>
            </select>
          </label>
          <p className="muted small">Drag the leak's label on the map to move it.</p>
        </fieldset>

        <fieldset>
          <legend>Timing (exercise time)</legend>
          <div className="row wrap">
            <label className="row">
              Starts
              <select value={timing.activation} onChange={(e) => setTiming({ activation: e.target.value as SourceTiming["activation"] })}>
                <option value="IMMEDIATE">when the exercise starts</option>
                <option value="AT_TIME">at a set time</option>
                <option value="MANUAL">when I trigger it</option>
              </select>
            </label>
            {timing.activation === "AT_TIME" && (
              <label className="row">
                at
                <NumberInput value={timing.startAtS} onChange={(v) => setTiming({ startAtS: v ?? 0 })} size={5} /> s
                <span className="muted small">({clock(timing.startAtS)})</span>
              </label>
            )}
          </div>
          <div className="row wrap">
            <label className="row">
              Builds up over
              <NumberInput value={timing.rampUpS} onChange={(v) => setTiming({ rampUpS: v ?? 0 })} size={5} /> s
            </label>
            <label className="row">
              Lasts
              <NumberInput value={timing.durationS} optional placeholder="until the end" onChange={(v) => setTiming({ durationS: v })} size={8} /> s
            </label>
            {timing.durationS != null && (
              <label className="row">
                then fades over
                <NumberInput value={timing.decayS} onChange={(v) => setTiming({ decayS: v ?? 0 })} size={5} /> s
              </label>
            )}
          </div>
          <label className="row">
            Grows by
            <NumberInput
              value={timing.growthMPerMin}
              onChange={(v) => setTiming({ growthMPerMin: v ?? 0 })}
              {...distance}
              size={5}
            />
            {units.distance.label} per minute while leaking
          </label>
        </fieldset>

        <label className="check">
          <input type="checkbox" checked={draft.enabled} onChange={(e) => set({ enabled: e.target.checked })} />
          On (an off leak stays on the map but releases nothing)
        </label>

        <ErrorBox error={error} />
        <div className="row wrap">
          <AsyncButton
            onClick={async () => {
              setError(undefined);
              await onSave();
            }}
            onError={setError}
          >
            {isNew ? "Add leak" : "Save"}
          </AsyncButton>
          <button className="btn secondary" onClick={onCancel}>
            Cancel
          </button>
          <span className="spacer" />
          {!isNew && (
            <AsyncButton kind="danger" confirm={`Remove ${draft.name}?`} onClick={onDelete} onError={setError}>
              Remove
            </AsyncButton>
          )}
        </div>
      </div>
    </Card>
  );
}
