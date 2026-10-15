import { useState } from "react";
import { Link } from "react-router";
import { errorMessage } from "../../api/client";
import { Api, type Exercise, type Profile } from "../../api/endpoints";
import { useResource } from "../../api/useResource";
import { AsyncButton, Card, ErrorBox, Loading, relativeTime } from "../../components/ui";
import { monitorLabel } from "../../meter/monitors";

const DEFAULT = "";

/**
 * Which phones are in this exercise and which monitor each imitates. Add from the fleet,
 * remove to standby. A phone without its own monitor uses the exercise's default.
 */
export function DevicesTab({ exercise, onChanged }: { exercise: Exercise; onChanged: () => Promise<void> }) {
  const all = useResource(Api.devices, []);
  const monitors = useResource(Api.profiles, []);
  const [bulk, setBulk] = useState(DEFAULT);
  const [error, setError] = useState<unknown>();
  if (all.error) return <ErrorBox error={all.error} />;
  if (!all.data || !monitors.data) return <Loading />;

  const active = all.data.filter((d) => !d.revokedAt);
  const inExercise = active.filter((d) => d.exerciseId === exercise.id);
  const available = active.filter((d) => d.exerciseId == null);
  const elsewhere = active.filter((d) => d.exerciseId != null && d.exerciseId !== exercise.id);
  const defaultMonitor = monitors.data.find((m) => m.id === exercise.profileId);

  const assign = async (id: string, exerciseId: string | null) => {
    try {
      await Api.assignDevice(id, exerciseId);
    } catch (e) {
      window.alert(errorMessage(e));
    }
    await all.reload();
  };
  const setMonitor = async (deviceIds: string[], monitorId: string) => {
    setError(undefined);
    try {
      await Api.setDeviceMonitor(exercise.id, deviceIds, monitorId === DEFAULT ? null : monitorId);
      await Promise.all([all.reload(), onChanged()]);
    } catch (e) {
      setError(e);
    }
  };

  return (
    <div className="grid2">
      <Card title={`In this exercise (${inExercise.length})`}>
        {inExercise.length === 0 && <p className="muted small">No phones yet.</p>}
        <ErrorBox error={error} />
        <ul className="plain device-monitors">
          {inExercise.map((d) => (
            <li key={d.id} className="row wrap">
              <strong>{d.name}</strong>
              <span className="muted small">seen {relativeTime(d.lastSeenAt)}</span>
              <span className="spacer" />
              <MonitorSelect
                label={`Monitor for ${d.name}`}
                value={d.monitorId ?? DEFAULT}
                monitors={monitors.data!}
                defaultMonitor={defaultMonitor}
                onChange={(m) => void setMonitor([d.id], m)}
              />
              <AsyncButton kind="secondary" onClick={() => assign(d.id, null)}>
                Remove
              </AsyncButton>
            </li>
          ))}
        </ul>
        {inExercise.length > 1 && (
          <div className="row wrap">
            <span className="small">Every phone here:</span>
            <MonitorSelect label="Monitor for every phone" value={bulk} monitors={monitors.data} defaultMonitor={defaultMonitor} onChange={setBulk} />
            <AsyncButton kind="secondary" onClick={() => setMonitor(inExercise.map((d) => d.id), bulk)}>
              Apply to all
            </AsyncButton>
          </div>
        )}
        <p className="muted small">
          Phones get the monitor&apos;s sensors and alarm levels within about a second. Set up monitors under Settings → Monitors; the
          default is chosen on this exercise&apos;s Settings tab.
        </p>
      </Card>
      <Card title={`On standby (${available.length})`}>
        {available.length > 1 && (
          <AsyncButton kind="secondary" onClick={async () => { for (const d of available) await assign(d.id, exercise.id); }}>
            Add all
          </AsyncButton>
        )}
        <ul className="plain">
          {available.map((d) => (
            <li key={d.id} className="row">
              <strong>{d.name}</strong>
              <span className="muted small">seen {relativeTime(d.lastSeenAt)}</span>
              <span className="spacer" />
              <AsyncButton onClick={() => assign(d.id, exercise.id)}>Add</AsyncButton>
            </li>
          ))}
        </ul>
        {elsewhere.length > 0 && (
          <p className="muted small">{elsewhere.length} other phone(s) are in other exercises (Devices page).</p>
        )}
      </Card>
      <BeaconsCard exerciseId={exercise.id} onChanged={onChanged} />
    </div>
  );
}

function MonitorSelect({
  label,
  value,
  monitors,
  defaultMonitor,
  onChange,
}: {
  label: string;
  value: string;
  monitors: Profile[];
  defaultMonitor?: Profile;
  onChange: (monitorId: string) => void;
}) {
  return (
    <select aria-label={label} value={value} onChange={(e) => onChange(e.target.value)}>
      <option value={DEFAULT}>Default{defaultMonitor ? ` (${defaultMonitor.definition.name})` : ""}</option>
      {monitors.map((m) => (
        <option key={m.id} value={m.id}>
          {monitorLabel(m.definition)}
        </option>
      ))}
    </select>
  );
}

/**
 * The exercise's beacons. Its phones listen only for these: any other beacon in range makes no
 * readings. A beacon may also be in other exercises.
 */
function BeaconsCard({ exerciseId, onChanged }: { exerciseId: string; onChanged: () => Promise<void> }) {
  const members = useResource(() => Api.exerciseBeacons(exerciseId), [exerciseId]);
  const inventory = useResource(Api.beacons, []);
  const [choice, setChoice] = useState("");
  const [error, setError] = useState<unknown>();
  if (!members.data || !inventory.data) return <Loading />;

  const memberIds = new Set(members.data.map((b) => b.id));
  const available = inventory.data.filter((b) => !memberIds.has(b.id));
  const chosen = choice || available[0]?.id || "";
  const run = async (call: () => Promise<unknown>) => {
    setError(undefined);
    try {
      await call();
      await Promise.all([members.reload(), onChanged()]);
    } catch (e) {
      setError(e);
    }
  };

  return (
    <Card title={`Beacons (${members.data.length})`}>
      <p className="muted small">Phones in this exercise react only to these beacons. Put leaks on them from the Live map.</p>
      <ErrorBox error={error} />
      {members.data.length === 0 && <p className="muted small">No beacons in this exercise.</p>}
      <ul className="plain">
        {members.data.map((b) => (
          <li key={b.id} className="row">
            <strong>{b.label}</strong>
            <span className="muted small">
              {b.major} / {b.minor}
            </span>
            <span className="spacer" />
            <AsyncButton kind="secondary" onClick={() => run(() => Api.removeExerciseBeacon(exerciseId, b.id))}>
              Remove
            </AsyncButton>
          </li>
        ))}
      </ul>
      {inventory.data.length === 0 ? (
        <p className="muted small">
          No beacons yet: add them in <Link to="/settings/beacons">Settings → Beacons</Link>.
        </p>
      ) : available.length > 0 ? (
        <div className="row">
          <select aria-label="Beacon to add" value={chosen} onChange={(e) => setChoice(e.target.value)}>
            {available.map((b) => (
              <option key={b.id} value={b.id}>
                {b.label}
              </option>
            ))}
          </select>
          <AsyncButton onClick={() => run(() => Api.addExerciseBeacon(exerciseId, chosen))}>Add beacon</AsyncButton>
        </div>
      ) : (
        <p className="muted small">Every beacon is in this exercise.</p>
      )}
    </Card>
  );
}
