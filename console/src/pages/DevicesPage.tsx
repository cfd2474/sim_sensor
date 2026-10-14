import { useState } from "react";
import { Link } from "react-router";
import { errorMessage } from "../api/client";
import { Api, type Device } from "../api/endpoints";
import { useResource } from "../api/useResource";
import { useAuth } from "../auth/AuthContext";
import { AsyncButton, Card, ErrorBox, Loading, relativeTime } from "../components/ui";

/** The phone fleet: rename, group, assign to an exercise, revoke. */
export function DevicesPage() {
  const { isAdmin } = useAuth();
  const devices = useResource(Api.devices, []);
  const groups = useResource(Api.groups, []);
  const exercises = useResource(Api.exercises, []);
  const [groupName, setGroupName] = useState("");
  const [showRevoked, setShowRevoked] = useState(false);

  const update = async (action: () => Promise<Device>) => {
    try {
      await action();
    } catch (e) {
      window.alert(errorMessage(e));
    }
    await devices.reload();
  };

  const shown = (devices.data ?? []).filter((d) => showRevoked || !d.revokedAt);

  return (
    <div className="stack">
      <h1>Devices</h1>
      <Card
        title="Phones"
        actions={
          <label className="check">
            <input type="checkbox" checked={showRevoked} onChange={(e) => setShowRevoked(e.target.checked)} /> Show revoked
          </label>
        }
      >
        <ErrorBox error={devices.error} />
        {devices.loading && !devices.data ? (
          <Loading />
        ) : shown.length === 0 ? (
          <p className="muted">
            No phones yet. <Link to="/enroll">Enroll one</Link>.
          </p>
        ) : (
          <table className="table">
            <thead>
              <tr>
                <th>Name</th>
                <th>Exercise</th>
                <th>Group</th>
                <th>Last seen</th>
                <th>Model · app</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {shown.map((d) => (
                <tr key={d.id} className={d.revokedAt ? "dim" : ""}>
                  <td>
                    <InlineName device={d} onRename={(name) => update(() => Api.updateDevice(d.id, { name }))} />
                  </td>
                  <td>
                    <select
                      value={d.exerciseId ?? ""}
                      disabled={!!d.revokedAt}
                      onChange={(e) => update(() => Api.assignDevice(d.id, e.target.value || null))}
                    >
                      <option value="">— standby —</option>
                      {exercises.data?.map((x) => (
                        <option key={x.id} value={x.id}>
                          {x.name}
                        </option>
                      ))}
                      {d.exerciseId && !exercises.data?.some((x) => x.id === d.exerciseId) && (
                        <option value={d.exerciseId}>Another instructor&apos;s exercise</option>
                      )}
                    </select>
                  </td>
                  <td>
                    <select
                      value={d.groupId ?? ""}
                      disabled={!!d.revokedAt}
                      onChange={(e) => update(() => Api.updateDevice(d.id, { groupId: e.target.value }))}
                    >
                      <option value="">—</option>
                      {groups.data?.map((g) => (
                        <option key={g.id} value={g.id}>
                          {g.name}
                        </option>
                      ))}
                    </select>
                  </td>
                  <td>{relativeTime(d.lastSeenAt)}</td>
                  <td className="muted small">
                    {d.model ?? "?"} · {d.appVersion ?? "?"}
                  </td>
                  <td>
                    {isAdmin && !d.revokedAt && (
                      <AsyncButton
                        kind="danger"
                        confirm={`Revoke ${d.name}? It is disconnected at once and must be enrolled again.`}
                        onClick={() => update(() => Api.revokeDevice(d.id))}
                      >
                        Revoke
                      </AsyncButton>
                    )}
                    {d.revokedAt && <span className="muted small">revoked</span>}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </Card>

      <Card title="Groups">
        <p className="muted small">Groups let you target several phones at once, e.g. an entry team.</p>
        <div className="row wrap">
          {groups.data?.map((g) => (
            <span key={g.id} className="chip">
              {g.name}
              {isAdmin && (
                <button className="chip-x" title="Delete group" onClick={() => Api.deleteGroup(g.id).then(groups.reload)}>
                  ×
                </button>
              )}
            </span>
          ))}
        </div>
        <form
          className="row"
          onSubmit={async (e) => {
            e.preventDefault();
            try {
              await Api.createGroup(groupName);
              setGroupName("");
              await groups.reload();
            } catch (err) {
              window.alert(errorMessage(err));
            }
          }}
        >
          <input placeholder="New group, e.g. Entry team" value={groupName} onChange={(e) => setGroupName(e.target.value)} required />
          <button className="btn secondary" type="submit">
            Add group
          </button>
        </form>
      </Card>
    </div>
  );
}

function InlineName({ device, onRename }: { device: Device; onRename: (name: string) => Promise<void> }) {
  const [editing, setEditing] = useState(false);
  const [name, setName] = useState(device.name);
  if (!editing || device.revokedAt) {
    return (
      <button className="link" onClick={() => setEditing(true)} title="Rename">
        {device.name}
      </button>
    );
  }
  return (
    <form
      className="row"
      onSubmit={async (e) => {
        e.preventDefault();
        await onRename(name);
        setEditing(false);
      }}
    >
      <input value={name} onChange={(e) => setName(e.target.value)} autoFocus size={12} />
      <button className="btn primary small" type="submit">
        Save
      </button>
    </form>
  );
}
