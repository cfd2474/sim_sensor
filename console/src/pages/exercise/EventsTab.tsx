import { useState } from "react";
import { Api, type Event, type Exercise } from "../../api/endpoints";
import { useResource } from "../../api/useResource";
import { AsyncButton, Card, ErrorBox, Loading } from "../../components/ui";

const LABELS: Record<string, string> = {
  "device.connected": "Phone connected",
  "device.disconnected": "Phone disconnected",
  "device.assigned": "Phone assigned",
  "device.monitor": "Monitor set",
  "device.revoked": "Phone revoked",
  "device.reenrolled": "Phone enrolled again",
  "device.event": "Phone",
  "override.set": "Override set",
  "override.released": "Override released",
  "command.sent": "Command",
  "exercise.state": "Exercise",
  "exercise.criteria": "Criteria changed (retired)",
  "source.created": "Leak added",
  "source.updated": "Leak changed",
  "source.deleted": "Leak removed",
  "source.activated": "Leak started",
  "beacon.added": "Beacon added",
  "beacon.removed": "Beacon removed",
  "beacon.calibrated": "Beacon calibrated",
  "beacon.edge": "Beacon gas edge set",
  "settings.changed": "Settings changed",
};

/** The exercise's event log, newest first, for after-action review. */
export function EventsTab({ exercise }: { exercise: Exercise }) {
  const first = useResource(() => Api.events(exercise.id), [exercise.id]);
  const [older, setOlder] = useState<Event[]>([]);
  const [done, setDone] = useState(false);

  if (first.error) return <ErrorBox error={first.error} />;
  if (!first.data) return <Loading />;
  const events = [...first.data, ...older];

  return (
    <Card
      title="Event log"
      actions={
        <button className="btn secondary small" onClick={() => { setOlder([]); setDone(false); void first.reload(); }}>
          Refresh
        </button>
      }
    >
      <table className="table compact">
        <thead>
          <tr>
            <th>Time</th>
            <th>Event</th>
            <th>Phone</th>
            <th>By</th>
            <th>Details</th>
          </tr>
        </thead>
        <tbody>
          {events.map((e) => (
            <tr key={e.id}>
              <td className="nowrap">{new Date(e.ts).toLocaleString()}</td>
              <td>{LABELS[e.type] ?? e.type}</td>
              <td>{e.deviceName ?? ""}</td>
              <td>{e.actorName ?? ""}</td>
              <td className="muted small">
                {Object.entries(e.data)
                  .filter(([, v]) => v != null && v !== "")
                  .map(([k, v]) => `${k}: ${v}`)
                  .join(" · ")}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      {!done && events.length > 0 && (
        <AsyncButton
          kind="secondary"
          onClick={async () => {
            const page = await Api.events(exercise.id, events[events.length - 1].id);
            if (page.length === 0) setDone(true);
            setOlder((o) => [...o, ...page]);
          }}
        >
          Load older
        </AsyncButton>
      )}
      {events.length === 0 && <p className="muted">Nothing yet.</p>}
    </Card>
  );
}
