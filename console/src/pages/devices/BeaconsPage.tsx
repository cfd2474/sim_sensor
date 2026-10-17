import { useState } from "react";
import { Api, type Beacon } from "../../api/endpoints";
import { useResource } from "../../api/useResource";
import { useAuth } from "../../auth/AuthContext";
import { AsyncButton, Card, ErrorBox, Loading } from "../../components/ui";
import { NumberInput } from "../../leaks/NumberInput";

/** Path-loss presets: how quickly the signal weakens with distance where the beacon is used. */
const ENVIRONMENTS: [number, string][] = [
  [2.0, "Open area"],
  [2.5, "Indoors"],
  [3.0, "Cluttered (machinery, many walls)"],
];

/** The environment a new or reset beacon has (BeaconRef.DEFAULT_PATH_LOSS). */
const DEFAULT_PATH_LOSS = 2.5;
const EMPTY: Beacon = { id: "", label: "", uuid: "", major: 0, minor: 0, txPowerAt1m: null, pathLossN: DEFAULT_PATH_LOSS, notes: "", edgeRssi: null };

/** Nothing to reset: the beacon's own power, the default environment, no gas edge. */
function isUncalibrated(b: Beacon): boolean {
  return b.txPowerAt1m == null && b.pathLossN === DEFAULT_PATH_LOSS && b.edgeRssi == null;
}

/**
 * The beacon inventory: iBeacons the agency owns, with their identity (UUID / major / minor)
 * and calibration. Leaks are attached to them on an exercise's Live map.
 */
export function BeaconsPage() {
  const { isAdmin } = useAuth();
  const beacons = useResource(Api.beacons, []);
  const [editing, setEditing] = useState<Beacon | null>(null);
  if (editing) {
    return (
      <BeaconEditor
        initial={editing}
        onDone={() => {
          setEditing(null);
          void beacons.reload();
        }}
      />
    );
  }

  return (
    <div className="stack">
      <div className="row wrap">
        <p className="muted grow">
          Bluetooth beacons placed at a training site act as gas sources: a phone&apos;s readings rise as it gets close. Add each
          beacon with the UUID, major and minor shown in its configuration app, then add it to an exercise on that exercise&apos;s
          Devices tab (a beacon can be in several exercises). Distance comes from signal strength, so treat it as near / far
          rather than exact. To calibrate a beacon, open Settings on a phone in one of its exercises and choose Calibrate next to
          the beacon.
        </p>
        {isAdmin && (
          <button className="btn primary" onClick={() => setEditing({ ...EMPTY })}>
            Add beacon
          </button>
        )}
      </div>
      <ErrorBox error={beacons.error} />
      {!beacons.data && <Loading />}
      {beacons.data?.length === 0 && <p className="muted">No beacons yet.</p>}
      {beacons.data && beacons.data.length > 0 && (
        <Card>
          <table className="table compact">
            <thead>
              <tr>
                <th>Label</th>
                <th>UUID</th>
                <th>Major</th>
                <th>Minor</th>
                <th>Power at 1 m</th>
                <th>Environment</th>
                <th>Gas edge</th>
                <th>Notes</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {beacons.data.map((b) => (
                <tr key={b.id}>
                  <td>
                    <strong>{b.label}</strong>
                  </td>
                  <td className="small wrap-anywhere">
                    <code>{b.uuid}</code>
                  </td>
                  <td>{b.major}</td>
                  <td>{b.minor}</td>
                  <td>{b.txPowerAt1m != null ? `${b.txPowerAt1m} dBm` : <span className="muted">beacon&apos;s own</span>}</td>
                  <td>{ENVIRONMENTS.find(([n]) => n === b.pathLossN)?.[1] ?? `n = ${b.pathLossN}`}</td>
                  <td>{b.edgeRssi != null ? `${b.edgeRssi} dBm` : <span className="muted">none</span>}</td>
                  <td className="small">{b.notes}</td>
                  <td className="nowrap">
                    {isAdmin && (
                      <>
                        <button className="btn secondary small" onClick={() => setEditing(b)}>
                          Edit
                        </button>{" "}
                        <AsyncButton
                          kind="secondary"
                          small
                          disabled={isUncalibrated(b)}
                          confirm={
                            `Reset the calibration of ${b.label}?

` +
                            "Power at 1 m goes back to the beacon's own, the environment to the default (Indoors), and the gas edge is removed. " +
                            "Phones in its exercises use the new values at once. Calibrate it again on a phone afterwards."
                          }
                          onClick={() => Api.resetBeaconCalibration(b.id).then(beacons.reload)}
                        >
                          Reset calibration
                        </AsyncButton>{" "}
                        <AsyncButton kind="danger" small confirm={`Remove ${b.label}?`} onClick={() => Api.deleteBeacon(b.id).then(beacons.reload)}>
                          Remove
                        </AsyncButton>
                      </>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </Card>
      )}
    </div>
  );
}

function BeaconEditor({ initial, onDone }: { initial: Beacon; onDone: () => void }) {
  const [b, setB] = useState<Beacon>(initial);
  const [error, setError] = useState<unknown>();
  const custom = !ENVIRONMENTS.some(([n]) => n === b.pathLossN);

  return (
    <Card title={initial.id ? `Edit ${initial.label}` : "Add beacon"}>
      <div className="form-grid">
        <label>
          Label
          <input value={b.label} maxLength={64} placeholder="e.g. Drum 1" onChange={(e) => setB({ ...b, label: e.target.value })} />
        </label>
        <label>
          UUID
          <input value={b.uuid} placeholder="With or without dashes" onChange={(e) => setB({ ...b, uuid: e.target.value })} />
        </label>
        <label>
          Major
          <NumberInput value={b.major} onChange={(v) => setB({ ...b, major: Math.round(v ?? 0) })} />
        </label>
        <label>
          Minor
          <NumberInput value={b.minor} onChange={(v) => setB({ ...b, minor: Math.round(v ?? 0) })} />
        </label>
        <label>
          Signal at 1 m (dBm)
          <NumberInput
            value={b.txPowerAt1m}
            optional
            min={-110}
            placeholder="the beacon's own"
            onChange={(v) => setB({ ...b, txPowerAt1m: v })}
          />
        </label>
        <label>
          Environment
          <select
            value={custom ? "custom" : String(b.pathLossN)}
            onChange={(e) => setB({ ...b, pathLossN: e.target.value === "custom" ? 2.2 : Number(e.target.value) })}
          >
            {ENVIRONMENTS.map(([n, name]) => (
              <option key={n} value={String(n)}>
                {name} (n = {n})
              </option>
            ))}
            <option value="custom">Custom</option>
          </select>
        </label>
        {custom && (
          <label>
            Path loss n (1.5–4.5)
            <NumberInput value={b.pathLossN} min={1.5} onChange={(v) => setB({ ...b, pathLossN: v ?? 2.5 })} />
          </label>
        )}
        <label>
          Gas edge (dBm)
          <NumberInput value={b.edgeRssi} optional min={-110} placeholder="none" onChange={(v) => setB({ ...b, edgeRssi: v })} />
        </label>
        <label>
          Notes
          <input value={b.notes} placeholder="Where it lives, battery date…" onChange={(e) => setB({ ...b, notes: e.target.value })} />
        </label>
      </div>
      <p className="muted small">
        &quot;Signal at 1 m&quot; is set by calibration (hold an agency phone 1 m from the beacon); leave it blank to use the power the
        beacon advertises. &quot;Gas edge&quot; is the signal where the gas should stop (recorded on a phone at the doorway): weaker
        signal, for example through a wall, gives no reading. Leave it blank for no edge.
      </p>
      <ErrorBox error={error} />
      <div className="row">
        <AsyncButton
          onClick={async () => {
            setError(undefined);
            await (b.id ? Api.updateBeacon(b) : Api.createBeacon(b));
            onDone();
          }}
          onError={setError}
        >
          Save
        </AsyncButton>
        <button className="btn secondary" onClick={onDone}>
          Cancel
        </button>
      </div>
    </Card>
  );
}
