import { errorMessage } from "../../api/client";
import { Api, type Exercise } from "../../api/endpoints";
import { useResource } from "../../api/useResource";
import { AsyncButton, Card, ErrorBox, Loading, relativeTime } from "../../components/ui";

/** Which phones are in this exercise; add from the fleet, remove to standby. */
export function DevicesTab({ exercise }: { exercise: Exercise }) {
  const all = useResource(Api.devices, []);
  if (all.error) return <ErrorBox error={all.error} />;
  if (!all.data) return <Loading />;

  const active = all.data.filter((d) => !d.revokedAt);
  const inExercise = active.filter((d) => d.exerciseId === exercise.id);
  const available = active.filter((d) => d.exerciseId == null);
  const elsewhere = active.filter((d) => d.exerciseId != null && d.exerciseId !== exercise.id);

  const assign = async (id: string, exerciseId: string | null) => {
    try {
      await Api.assignDevice(id, exerciseId);
    } catch (e) {
      window.alert(errorMessage(e));
    }
    await all.reload();
  };

  return (
    <div className="grid2">
      <Card title={`In this exercise (${inExercise.length})`}>
        {inExercise.length === 0 && <p className="muted small">No phones yet.</p>}
        <ul className="plain">
          {inExercise.map((d) => (
            <li key={d.id} className="row">
              <strong>{d.name}</strong>
              <span className="muted small">seen {relativeTime(d.lastSeenAt)}</span>
              <span className="spacer" />
              <AsyncButton kind="secondary" onClick={() => assign(d.id, null)}>
                Remove
              </AsyncButton>
            </li>
          ))}
        </ul>
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
