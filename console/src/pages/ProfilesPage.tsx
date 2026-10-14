import { useState } from "react";
import { Api, type Channel, type Profile, type SensorProfile } from "../api/endpoints";
import { useResource } from "../api/useResource";
import { useAuth } from "../auth/AuthContext";
import { AsyncButton, Card, ErrorBox, Loading, formatTime } from "../components/ui";

/** Numeric channel columns: [field, header, optional]. */
const NUMBER_FIELDS: [keyof Channel, string, boolean][] = [
  ["rangeMin", "Min", false],
  ["rangeMax", "Max", false],
  ["resolution", "Res.", false],
  ["baseline", "Baseline", false],
  ["lowAlarm", "Low", true],
  ["highAlarm", "High", true],
  ["stelAlarm", "STEL", true],
  ["twaAlarm", "TWA", true],
  ["t90Seconds", "T90 s", false],
  ["noise", "Noise", false],
];

const EMPTY_CHANNEL: Channel = {
  id: "",
  label: "",
  unit: "PPM",
  rangeMin: 0,
  rangeMax: 100,
  resolution: 1,
  baseline: 0,
  lowAlarm: null,
  highAlarm: null,
  stelAlarm: null,
  twaAlarm: null,
  lowAlarmFalling: false,
  t90Seconds: 30,
  noise: 0,
};

/** Meter setups (profile templates). Everyone reads them; admins edit. */
export function ProfilesPage() {
  const { isAdmin } = useAuth();
  const profiles = useResource(Api.profiles, []);
  const [editing, setEditing] = useState<{ id: string | null; definition: SensorProfile } | null>(null);

  if (editing) {
    return (
      <ProfileEditor
        id={editing.id}
        initial={editing.definition}
        onDone={() => {
          setEditing(null);
          void profiles.reload();
        }}
      />
    );
  }

  return (
    <div className="stack">
      <h1>Meter setups</h1>
      <p className="muted">
        Templates for the simulated meters. Each exercise picks one and can change its alarm levels on the exercise&apos;s
        Criteria tab.
      </p>
      <ErrorBox error={profiles.error} />
      {profiles.loading && !profiles.data && <Loading />}
      {profiles.data?.map((p: Profile) => (
        <Card
          key={p.id}
          title={
            <>
              {p.definition.name} {p.builtin && <span className="badge subtle">built-in</span>}
            </>
          }
          actions={
            isAdmin && (
              <>
                <button className="btn secondary small" onClick={() => setEditing({ id: p.id, definition: p.definition })}>
                  Edit
                </button>
                <button
                  className="btn secondary small"
                  onClick={() => setEditing({ id: null, definition: { ...p.definition, name: `${p.definition.name} copy` } })}
                >
                  Duplicate
                </button>
                {!p.builtin && (
                  <AsyncButton kind="danger" confirm={`Delete ${p.definition.name}?`} onClick={() => Api.deleteProfile(p.id).then(profiles.reload)}>
                    Delete
                  </AsyncButton>
                )}
              </>
            )
          }
        >
          <ChannelTable channels={p.definition.channels} />
          <p className="muted small">
            Version {p.version} · updated {formatTime(p.updatedAt)} · alarms {p.definition.alarmLatching ? "latch until acknowledged" : "clear on their own"}
          </p>
        </Card>
      ))}
    </div>
  );
}

function ChannelTable({ channels }: { channels: Channel[] }) {
  return (
    <table className="table compact">
      <thead>
        <tr>
          <th>Gas</th>
          <th>Unit</th>
          {NUMBER_FIELDS.map(([, header]) => (
            <th key={header}>{header}</th>
          ))}
        </tr>
      </thead>
      <tbody>
        {channels.map((c) => (
          <tr key={c.id}>
            <td>
              <strong>{c.label}</strong> {c.lowAlarmFalling && <span className="muted small">(low falls)</span>}
            </td>
            <td>{c.unit}</td>
            {NUMBER_FIELDS.map(([field]) => (
              <td key={field}>{(c[field] as number | null | undefined) ?? "—"}</td>
            ))}
          </tr>
        ))}
      </tbody>
    </table>
  );
}

function ProfileEditor({ id, initial, onDone }: { id: string | null; initial: SensorProfile; onDone: () => void }) {
  const [profile, setProfile] = useState<SensorProfile>(initial);
  const [error, setError] = useState<unknown>();

  const setChannel = (index: number, patch: Partial<Channel>) =>
    setProfile((p) => ({ ...p, channels: p.channels.map((c, i) => (i === index ? { ...c, ...patch } : c)) }));

  return (
    <div className="stack">
      <h1>{id ? "Edit meter setup" : "New meter setup"}</h1>
      <Card>
        <div className="row wrap">
          <label>
            Name
            <input value={profile.name} onChange={(e) => setProfile({ ...profile, name: e.target.value })} />
          </label>
          <label className="check">
            <input
              type="checkbox"
              checked={profile.alarmLatching ?? true}
              onChange={(e) => setProfile({ ...profile, alarmLatching: e.target.checked })}
            />
            Alarms latch until acknowledged
          </label>
        </div>
        <div className="scroll-x">
          <table className="table compact edit">
            <thead>
              <tr>
                <th>Id</th>
                <th>Label</th>
                <th>Unit</th>
                {NUMBER_FIELDS.map(([, header]) => (
                  <th key={header}>{header}</th>
                ))}
                <th>Low falls</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {profile.channels.map((c, i) => (
                <tr key={i}>
                  <td>
                    <input value={c.id} onChange={(e) => setChannel(i, { id: e.target.value.toUpperCase() })} size={4} />
                  </td>
                  <td>
                    <input value={c.label} onChange={(e) => setChannel(i, { label: e.target.value })} size={5} />
                  </td>
                  <td>
                    <input value={c.unit} onChange={(e) => setChannel(i, { unit: e.target.value })} size={5} />
                  </td>
                  {NUMBER_FIELDS.map(([field, , optional]) => (
                    <td key={field}>
                      <input
                        type="number"
                        step="any"
                        size={5}
                        value={(c[field] as number | null | undefined) ?? ""}
                        placeholder={optional ? "off" : ""}
                        onChange={(e) =>
                          setChannel(i, { [field]: e.target.value === "" ? (optional ? null : 0) : Number(e.target.value) } as Partial<Channel>)
                        }
                      />
                    </td>
                  ))}
                  <td>
                    <input type="checkbox" checked={c.lowAlarmFalling ?? false} onChange={(e) => setChannel(i, { lowAlarmFalling: e.target.checked })} />
                  </td>
                  <td>
                    <button
                      className="btn secondary small"
                      onClick={() => setProfile((p) => ({ ...p, channels: p.channels.filter((_, j) => j !== i) }))}
                    >
                      Remove
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        <button className="btn secondary small" onClick={() => setProfile((p) => ({ ...p, channels: [...p.channels, { ...EMPTY_CHANNEL }] }))}>
          Add channel
        </button>
        <ErrorBox error={error} />
        <div className="row">
          <AsyncButton
            onClick={async () => {
              setError(undefined);
              await (id ? Api.updateProfile(id, profile) : Api.createProfile(profile));
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
    </div>
  );
}
