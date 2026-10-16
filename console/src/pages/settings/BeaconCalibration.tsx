import { useEffect, useRef, useState } from "react";
import { Api, type Beacon } from "../../api/endpoints";
import { useResource } from "../../api/useResource";
import { AsyncButton, Card, ErrorBox, Loading } from "../../components/ui";
import { NumberInput } from "../../leaks/NumberInput";
import { useExerciseLive } from "../../live/useExerciseLive";
import { useUnits } from "../../units/UnitsContext";

/** How long each distance is recorded. The phone reports about once a second. */
const RECORD_S = 15;

interface Point {
  distanceM: number;
  rssi?: number;
  samples?: number;
}

type Fit = { txPowerAt1m: number; pathLossN: number; rmsErrorDb: number };

function median(values: number[]): number {
  const sorted = [...values].sort((a, b) => a - b);
  return sorted[Math.floor(sorted.length / 2)];
}

/**
 * Calibrates one beacon with a phone that hears it: the phone is held at known distances
 * (1 m, then e.g. 3 m), the signal it reports is recorded at each, and the server fits the
 * beacon's power at 1 m and how fast its signal fades at this site.
 */
export function BeaconCalibration({ beacon, onDone }: { beacon: Beacon; onDone: () => void }) {
  const uses = useResource(() => Api.beaconExercises(beacon.id), [beacon.id]);
  const [exerciseId, setExerciseId] = useState<string>("");
  const chosen = exerciseId || uses.data?.[0]?.exerciseId || "";

  return (
    <div className="stack">
      <h2>Calibrate {beacon.label}</h2>
      <p className="muted">
        Use the phone and site the beacon will be used with. Hold the phone at chest height, screen facing the beacon, with a
        clear line between them, and stand still while recording. Currently: power at 1 m{" "}
        {beacon.txPowerAt1m != null ? `${beacon.txPowerAt1m} dBm` : "from the beacon"}, path loss n = {beacon.pathLossN}.
      </p>
      <ErrorBox error={uses.error} />
      {!uses.data && <Loading />}
      {uses.data?.length === 0 && (
        <p className="muted">Add the beacon to an exercise (its Devices tab) and put a phone in that exercise first.</p>
      )}
      {uses.data && uses.data.length > 0 && (
        <>
          <label>
            Exercise{" "}
            <select value={chosen} onChange={(e) => setExerciseId(e.target.value)}>
              {uses.data.map((u) => (
                <option key={u.exerciseId} value={u.exerciseId}>
                  {u.exerciseName}
                </option>
              ))}
            </select>
          </label>
          {chosen && <Recorder key={chosen} exerciseId={chosen} beacon={beacon} onDone={onDone} />}
        </>
      )}
      <div className="row">
        <button className="btn secondary" onClick={onDone}>
          Back to beacons
        </button>
      </div>
    </div>
  );
}

function Recorder({ exerciseId, beacon, onDone }: { exerciseId: string; beacon: Beacon; onDone: () => void }) {
  const live = useExerciseLive(exerciseId);
  const units = useUnits();
  const distance = { toDisplay: units.distance.toDisplay, fromDisplay: units.distance.fromDisplay };
  const [deviceId, setDeviceId] = useState("");
  const [points, setPoints] = useState<Point[]>([{ distanceM: 1 }, { distanceM: 3 }]);
  const [recording, setRecording] = useState<{ index: number; endsAt: number } | null>(null);
  const [now, setNow] = useState(Date.now());
  const [fit, setFit] = useState<Fit | null>(null);
  const collected = useRef<number[]>([]);

  const hearing = Object.values(live.devices).filter((d) => d.last?.beacons?.some((b) => b.beaconId === beacon.id));
  const phones = Object.values(live.devices).filter((d) => d.online);
  const device = live.devices[deviceId] ?? hearing[0] ?? phones[0];
  const heard = device?.last?.beacons?.find((b) => b.beaconId === beacon.id);

  // Collect each new sample from the chosen phone while recording.
  const lastTs = device?.last?.ts;
  useEffect(() => {
    if (recording && heard) collected.current.push(heard.rssi);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [lastTs]);

  // Finish the recording after RECORD_S seconds.
  useEffect(() => {
    if (!recording) return;
    const timer = window.setInterval(() => {
      const t = Date.now();
      setNow(t);
      if (t >= recording.endsAt) {
        const values = collected.current;
        setPoints((ps) =>
          ps.map((p, i) => (i === recording.index ? { ...p, rssi: values.length ? median(values) : undefined, samples: values.length } : p)),
        );
        setRecording(null);
      }
    }, 250);
    return () => window.clearInterval(timer);
  }, [recording]);

  const record = (index: number) => {
    collected.current = [];
    setFit(null);
    setRecording({ index, endsAt: Date.now() + RECORD_S * 1000 });
  };
  const measured = points.filter((p) => p.rssi != null);
  const distinct = new Set(measured.map((p) => p.distanceM)).size;

  return (
    <>
      <Card title="Phone">
        {phones.length === 0 ? (
          <p className="muted small">No phone of this exercise is online.</p>
        ) : (
          <div className="row wrap">
            <select value={device?.deviceId ?? ""} onChange={(e) => setDeviceId(e.target.value)} disabled={!!recording}>
              {phones.map((d) => (
                <option key={d.deviceId} value={d.deviceId}>
                  {d.name}
                </option>
              ))}
            </select>
            <span className={heard ? "" : "muted"} data-testid="calibration-live">
              {heard
                ? `Hears it: ${heard.rssi} dBm, estimated ${units.formatDistance(heard.distanceM)}`
                : "Doesn't hear the beacon (Bluetooth, Nearby devices permission, or too far)"}
            </span>
          </div>
        )}
      </Card>
      <Card title="Measurements">
        <table className="table compact">
          <thead>
            <tr>
              <th>Distance from the beacon</th>
              <th>Signal</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {points.map((p, i) => (
              <tr key={i}>
                <td>
                  <NumberInput
                    value={p.distanceM}
                    onChange={(v) => setPoints((ps) => ps.map((q, j) => (j === i ? { distanceM: v ?? 0 } : q)))}
                    {...distance}
                  />{" "}
                  {units.distance.label}
                </td>
                <td>
                  {recording?.index === i
                    ? `Recording… ${Math.max(0, Math.ceil((recording.endsAt - now) / 1000))} s`
                    : p.rssi != null
                      ? `${p.rssi} dBm (${p.samples} readings)`
                      : p.samples === 0
                        ? "Not heard — try again"
                        : "—"}
                </td>
                <td className="nowrap">
                  <button className="btn secondary small" disabled={!!recording || !heard || !(p.distanceM > 0)} onClick={() => record(i)}>
                    {p.rssi != null ? "Record again" : "Record"}
                  </button>{" "}
                  {points.length > 1 && (
                    <button className="btn secondary small" disabled={!!recording} onClick={() => setPoints((ps) => ps.filter((_, j) => j !== i))}>
                      Remove
                    </button>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
        <div className="row">
          <button className="btn secondary small" disabled={!!recording} onClick={() => setPoints((ps) => [...ps, { distanceM: 5 }])}>
            Add a distance
          </button>
        </div>
        <p className="muted small">
          Walk to each distance, wait a few seconds for the reading to settle, then press Record and stay still for {RECORD_S} s.
          {distinct < 2 && " With one distance only the power at 1 m is set; two or more also set how fast the signal fades."}
        </p>
      </Card>
      {fit ? (
        <Card title="Saved">
          <p>
            Power at 1 m <strong>{fit.txPowerAt1m.toFixed(1)} dBm</strong>, path loss <strong>n = {fit.pathLossN.toFixed(2)}</strong>{" "}
            (measurements are within about {fit.rmsErrorDb.toFixed(1)} dB of the fitted curve). Phones in its exercises use it now.
          </p>
          {fit.rmsErrorDb > 3 && <p className="warn-text">The measurements don&apos;t fit well. Check the distances and record again.</p>}
          {(fit.pathLossN <= 1.5 || fit.pathLossN >= 5) && (
            <p className="warn-text">The fade came out at its limit. A measurement was probably blocked; record again.</p>
          )}
          <button className="btn primary" onClick={onDone}>
            Done
          </button>
        </Card>
      ) : (
        <div className="row">
          <AsyncButton
            kind="primary"
            disabled={!!recording || measured.length === 0}
            onClick={async () => {
              const r = await Api.calibrateBeacon(beacon.id, measured.map((p) => ({ distanceM: p.distanceM, rssi: p.rssi! })));
              setFit(r.fit);
            }}
          >
            Save calibration
          </AsyncButton>
        </div>
      )}
    </>
  );
}
