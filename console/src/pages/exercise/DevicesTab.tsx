import { useState } from "react";
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
