import type { Map as MapLibre } from "maplibre-gl";
import { useCallback, useEffect, useMemo, useState } from "react";
import {
  Api,
  type DeviceTarget,
  type Exercise,
  type GasSource,
  type LatLon,
  type OverrideMode,
  type PreviewGrid,
  type SourceShape,
} from "../../api/endpoints";
import { useResource } from "../../api/useResource";
import { Card, ErrorBox, Loading, relativeTime } from "../../components/ui";
import { extent } from "../../leaks/geometry";
import { LeakForm } from "../../leaks/LeakForm";
import { LeakList } from "../../leaks/LeakList";
import { NEW_LEAK_ID, newLeak } from "../../leaks/newLeak";
import { PreviewControl } from "../../leaks/PreviewControl";
import { emitting } from "../../leaks/timing";
import { alarmSeverity, isStale, useExerciseLive, type LiveDevice } from "../../live/useExerciseLive";
import { alarmLabel, alarmLabels } from "../../meter/alarms";
import { LeakLayer } from "../../map/LeakLayer";
import { LiveMap } from "../../map/LiveMap";
import { PlacementLayer, type PlaceKind } from "../../map/PlacementLayer";
import { PreviewLayer } from "../../map/PreviewLayer";
import { WindControl } from "../../map/WindControl";
import { useUnits } from "../../units/UnitsContext";

/** Live map, leaks, device list and instructor controls for one exercise. */
export function LiveTab({
  exercise,
  onExerciseChanged,
  onExerciseSaved,
}: {
  exercise: Exercise;
  onExerciseChanged: () => Promise<void>;
  onExerciseSaved: (x: Exercise) => void;
}) {
  const live = useExerciseLive(exercise.id);
  const units = useUnits();
  const sources = useResource(() => Api.sources(exercise.id), [exercise.id]);
  const [map, setMap] = useState<MapLibre | null>(null);
  const [leakId, setLeakId] = useState<string | null>(null);
  const [draft, setDraft] = useState<GasSource | null>(null);
  const [placing, setPlacing] = useState<PlaceKind | null>(null);
  const [previewGas, setPreviewGas] = useState("");
  const [preview, setPreview] = useState<{ grid: PreviewGrid | null; error?: string }>({ grid: null });
  const [leakError, setLeakError] = useState<unknown>();
  const [focus, setFocus] = useState<[number, number][]>([]);
  const config = useResource(Api.config, []);
  const profile = useResource(() => Api.effectiveProfile(exercise.id), [exercise.id, exercise.version]);
  const groups = useResource(Api.groups, []);
  const exerciseDevices = useResource(() => Api.exerciseDevices(exercise.id), [exercise.id]);
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [, tick] = useState(0);

  // Device list or exercise changed elsewhere: reload the REST data.
  useEffect(() => {
    if (live.changed > 0) {
      void exerciseDevices.reload();
      void sources.reload();
      void onExerciseChanged();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [live.changed]);

  // Re-render every 5 s so "stale" markers and "x s ago" stay current.
  useEffect(() => {
    const timer = window.setInterval(() => tick((n) => n + 1), 5000);
    return () => window.clearInterval(timer);
  }, []);

  const devices: LiveDevice[] = useMemo(() => Object.values(live.devices).sort((a, b) => a.name.localeCompare(b.name)), [live.devices]);
  const groupIds = new Set(exerciseDevices.data?.map((d) => d.groupId).filter(Boolean));
  const toggle = useCallback(
    (id: string) =>
      setSelected((s) => {
        const next = new Set(s);
        if (next.has(id)) next.delete(id);
        else next.add(id);
        return next;
      }),
    [],
  );

  // Zoom to the leaks once they first load (together with the phones, if any).
  useEffect(() => {
    if (focus.length === 0 && sources.data?.length) setFocus(extent(sources.data, exercise.windDirDeg));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [sources.data]);

  const channels = useMemo(() => profile.data?.channels ?? [], [profile.data]);
  const stored = useMemo(() => sources.data ?? [], [sources.data]);
  // The leak being edited is drawn as edited (or added, when new).
  const shown = useMemo(
    () => (draft ? (draft.id === NEW_LEAK_ID ? [...stored, draft] : stored.map((s) => (s.id === draft.id ? draft : s))) : stored),
    [draft, stored],
  );
  const editable = exercise.state !== "archived";

  const placed = useCallback(
    (points: LatLon[]) => {
      if (!placing) return;
      setDraft(newLeak(placing, points, channels, stored));
      setLeakId(NEW_LEAK_ID);
      setPlacing(null);
    },
    [placing, channels, stored],
  );
  const moved = useCallback(
    (source: GasSource, shape: SourceShape) => {
      if (draft && source.id === draft.id) {
        setDraft({ ...draft, shape });
        return;
      }
      setLeakError(undefined);
      Api.updateSource(exercise.id, { ...source, shape })
        .catch(setLeakError)
        .finally(() => void sources.reload());
    },
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [draft, exercise.id],
  );
  const saveDraft = async () => {
    if (!draft) return;
    const saved = draft.id === NEW_LEAK_ID ? await Api.createSource(exercise.id, draft) : await Api.updateSource(exercise.id, draft);
    await sources.reload();
    setDraft(null);
    setLeakId(saved.id);
  };
  const deleteDraft = async () => {
    if (!draft) return;
    await Api.deleteSource(exercise.id, draft.id);
    await sources.reload();
    setDraft(null);
    setLeakId(null);
  };
  const previewChannel = channels.find((c) => c.id === previewGas);
  const onGrid = useCallback((grid: PreviewGrid | null, error?: string) => setPreview({ grid, error }), []);
  // Refetch the preview whenever a leak or the exercise changed.
  const revision = `${exercise.version}:${JSON.stringify(stored)}`;

  return (
    <div className="live">
      <div className="live-map">
        {config.data ? (
          <LiveMap config={config.data} devices={devices} selected={selected} onSelect={toggle} units={units.units} focus={focus} onMap={setMap} />
        ) : (
          <Loading />
        )}
        {map && (
          <LeakLayer
            map={map}
            sources={shown}
            windFromDeg={exercise.windDirDeg}
            selectedId={leakId}
            editable={editable && !placing}
            onSelect={setLeakId}
            onMove={moved}
          />
        )}
        {map && previewChannel && (
          <PreviewLayer map={map} exerciseId={exercise.id} channel={previewChannel} revision={revision} live={emitting(exercise)} onGrid={onGrid} />
        )}
        {map && placing && <PlacementLayer map={map} kind={placing} onPlaced={placed} onCancel={() => setPlacing(null)} />}
        <div className="map-overlay top-left">
          <div className={`conn ${live.connected ? "ok" : "bad"}`}>{live.connected ? "Live" : "Reconnecting…"}</div>
          <WindControl exercise={exercise} onSaved={onExerciseSaved} />
        </div>
        {channels.length > 0 && (
          <div className="map-overlay bottom-right">
            <PreviewControl channels={channels} gas={previewGas} onGas={setPreviewGas} grid={preview.grid} error={preview.error} />
          </div>
        )}
      </div>
      <div className="live-side stack">
        <ErrorBox error={live.error} />
        <ErrorBox error={sources.error ?? leakError} />
        {draft ? (
          <LeakForm draft={draft} channels={channels} onChange={setDraft} onSave={saveDraft} onCancel={() => setDraft(null)} onDelete={deleteDraft} />
        ) : (
          <LeakList
            exercise={exercise}
            sources={stored}
            channels={channels}
            selectedId={leakId}
            placing={placing}
            onSelect={setLeakId}
            onEdit={(s) => {
              setPlacing(null);
              setLeakId(s.id);
              setDraft(s);
            }}
            onPlace={(k) => setPlacing(placing === k ? null : k)}
            onChanged={sources.reload}
          />
        )}
        <Card title={`Phones (${devices.length})`}>
          {devices.length === 0 ? (
            <p className="muted small">No phones in this exercise. Add them on the Devices tab.</p>
          ) : (
            <ul className="device-list">
              {devices.map((d) => (
                <DeviceRow
                  key={d.deviceId}
                  device={d}
                  channels={channels.map((c) => c.id)}
                  selected={selected.has(d.deviceId)}
                  onToggle={toggle}
                  formatDistance={units.formatDistance}
                />
              ))}
            </ul>
          )}
        </Card>
        {profile.data && (
          <ControlPanel
            exerciseId={exercise.id}
            channels={profile.data.channels.map((c) => ({ id: c.id, label: c.label, unit: c.unit }))}
            selected={[...selected]}
            groups={(groups.data ?? []).filter((g) => groupIds.has(g.id))}
          />
        )}
        <Card title="Alarms and actions">
          {live.events.length === 0 ? (
            <p className="muted small">Phone events appear here as they happen.</p>
          ) : (
            <ul className="events">
              {live.events.map((e, i) => (
                <li key={i}>
                  <span className="muted small">{new Date(e.at).toLocaleTimeString()}</span> <strong>{live.devices[e.deviceId]?.name ?? "Phone"}</strong>{" "}
                  {e.event.kind} {e.event.detail ? alarmLabels(e.event.detail) : ""}
                </li>
              ))}
            </ul>
          )}
        </Card>
      </div>
    </div>
  );
}

function DeviceRow({
  device,
  channels,
  selected,
  onToggle,
  formatDistance,
}: {
  device: LiveDevice;
  channels: string[];
  selected: boolean;
  onToggle: (id: string) => void;
  formatDistance: (metres: number) => string;
}) {
  const stale = isStale(device);
  const severity = alarmSeverity(device.last?.alarms);
  return (
    <li className={`device-row sev-${severity}${stale ? " stale" : ""}`}>
      <label className="check">
        <input type="checkbox" checked={selected} onChange={() => onToggle(device.deviceId)} />
        <strong>{device.name}</strong>
      </label>
      <span className="muted small">
        {stale ? `offline · ${device.heardAt ? relativeTime(new Date(device.heardAt).toISOString()) : "no data"}` : "online"}
        {device.last?.battery != null && ` · ${Math.round(device.last.battery * 100)} %`}
        {device.last?.accuracyM != null && ` · ±${formatDistance(device.last.accuracyM)}`}
      </span>
      <div className="readings">
        {channels.map((id) => {
          const value = device.last?.readings[id];
          const alarm = device.last?.alarms.filter((a) => a.startsWith(`${id}:`)).map((a) => a.split(":")[1]);
          return (
            <span key={id} className={`reading${alarm?.length ? " alarm" : ""}`} title={alarm?.map((a) => alarmLabel(`${id}:${a}`)).join(" ")}>
              {id} {value ?? (alarm?.includes("FAULT") ? "FAULT" : "—")}
            </span>
          );
        })}
      </div>
    </li>
  );
}

type TargetKind = "selected" | "group" | "all";

function ControlPanel({
  exerciseId,
  channels,
  selected,
  groups,
}: {
  exerciseId: string;
  channels: { id: string; label: string; unit: string }[];
  selected: string[];
  groups: { id: string; name: string }[];
}) {
  const [targetKind, setTargetKind] = useState<TargetKind>("all");
  const [groupId, setGroupId] = useState("");
  const [channelId, setChannelId] = useState<string>(channels[0]?.id ?? "");
  const [mode, setMode] = useState<OverrideMode>("RAMP");
  const [value, setValue] = useState("");
  const [ramp, setRamp] = useState("30");
  const [message, setMessage] = useState("");
  const [status, setStatus] = useState<string>();
  const [error, setError] = useState<unknown>();

  const target: DeviceTarget | null =
    targetKind === "all" ? { all: true } : targetKind === "group" ? (groupId ? { groupId } : null) : selected.length ? { deviceIds: selected } : null;
  const unit = channels.find((c) => c.id === channelId)?.unit ?? "";

  const run = async (label: string, call: () => Promise<{ deviceIds: string[] }>) => {
    setError(undefined);
    setStatus(undefined);
    try {
      const result = await call();
      setStatus(`${label}: ${result.deviceIds.length} phone(s)`);
    } catch (e) {
      setError(e);
    }
  };

  return (
    <Card title="Control readings">
      <div className="stack">
        <div className="row wrap">
          <strong>Phones</strong>
          <label className="check">
            <input type="radio" checked={targetKind === "all"} onChange={() => setTargetKind("all")} /> All in exercise
          </label>
          <label className="check">
            <input type="radio" checked={targetKind === "selected"} onChange={() => setTargetKind("selected")} /> Selected ({selected.length})
          </label>
          {groups.length > 0 && (
            <label className="check">
              <input type="radio" checked={targetKind === "group"} onChange={() => setTargetKind("group")} /> Group
              <select value={groupId} onChange={(e) => setGroupId(e.target.value)} disabled={targetKind !== "group"}>
                <option value="">—</option>
                {groups.map((g) => (
                  <option key={g.id} value={g.id}>
                    {g.name}
                  </option>
                ))}
              </select>
            </label>
          )}
        </div>
        <div className="row wrap">
          <select value={channelId} onChange={(e) => setChannelId(e.target.value)}>
            {channels.map((c) => (
              <option key={c.id} value={c.id}>
                {c.label}
              </option>
            ))}
            <option value="">All gases</option>
          </select>
          <select value={mode} onChange={(e) => setMode(e.target.value as OverrideMode)}>
            <option value="RAMP">Ramp to</option>
            <option value="FIXED">Set to</option>
            <option value="OFFSET">Add</option>
            <option value="FAULT">Sensor fault</option>
          </select>
          {mode !== "FAULT" && (
            <label className="row">
              <input type="number" step="any" value={value} placeholder="value" onChange={(e) => setValue(e.target.value)} size={6} />
              {unit}
            </label>
          )}
          {mode === "RAMP" && (
            <label className="row">
              over <input type="number" min={1} max={3600} value={ramp} onChange={(e) => setRamp(e.target.value)} size={4} /> s
            </label>
          )}
        </div>
        <div className="row wrap">
          <button
            className="btn primary"
            disabled={!target || (mode !== "FAULT" && value === "")}
            onClick={() =>
              run("Applied", () =>
                Api.setOverride(exerciseId, {
                  target: target!,
                  channelId: channelId || null,
                  mode,
                  value: mode === "FAULT" ? undefined : Number(value),
                  rampSeconds: mode === "RAMP" ? Number(ramp) : undefined,
                }),
              )
            }
          >
            Apply
          </button>
          <button className="btn secondary" disabled={!target} onClick={() => run("Released", () => Api.releaseOverrides(exerciseId, target!, channelId || null))}>
            Release {channelId || "all gases"}
          </button>
          <button className="btn secondary" disabled={!target} onClick={() => run("Released all", () => Api.releaseOverrides(exerciseId, target!, null))}>
            Release everything
          </button>
          <button className="btn secondary" disabled={!target} onClick={() => run("Silenced", () => Api.command(exerciseId, { target: target!, kind: "SILENCE_ALARMS" }))}>
            Silence alarms
          </button>
        </div>
        <form
          className="row"
          onSubmit={(e) => {
            e.preventDefault();
            if (!target) return;
            void run("Message sent", () => Api.command(exerciseId, { target, kind: "SHOW_MESSAGE", text: message })).then(() => setMessage(""));
          }}
        >
          <input placeholder="Message to the phone screens" value={message} onChange={(e) => setMessage(e.target.value)} maxLength={200} required />
          <button className="btn secondary" type="submit" disabled={!target}>
            Send
          </button>
        </form>
        {status && <p className="ok-text small">{status}</p>}
        <ErrorBox error={error} />
      </div>
    </Card>
  );
}
