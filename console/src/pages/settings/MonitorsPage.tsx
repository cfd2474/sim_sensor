import { useState } from "react";
import { Api, type Channel, type Profile, type SensorProfile } from "../../api/endpoints";
import { useResource } from "../../api/useResource";
import { useAuth } from "../../auth/AuthContext";
import { AsyncButton, Card, ErrorBox, Loading, formatTime } from "../../components/ui";
import { ALARMS, LATCH_HELP, latches, RESPONSE_HELP } from "../../meter/alarms";

/** Numeric channel columns before and after the alarms: [field, header]. */
const RANGE_FIELDS: [keyof Channel, string][] = [
  ["rangeMin", "Min"],
  ["rangeMax", "Max"],
  ["resolution", "Res."],
  ["baseline", "Baseline"],
];
const SENSOR_FIELDS: [keyof Channel, string, string?][] = [
  ["t90Seconds", "Response s", RESPONSE_HELP],
  ["noise", "Noise"],
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
  lowAlarmLatching: false,
  highAlarmLatching: true,
  stelAlarmLatching: true,
  twaAlarmLatching: true,
  t90Seconds: 5,
  noise: 0,
};

/**
 * Monitors: the gas meters phones imitate, set up like the agency's real ones (stored as
 * profiles). Everyone reads them; admins edit.
 */
export function MonitorsPage() {
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
      <div className="row wrap">
        <p className="muted grow">
          Set up a monitor for each model the agency uses (for example a Dräger X-am 2800 or a Blackline G8) with the sensors
          and alarm levels it is configured with. In an exercise, each phone imitates one monitor: its own, or the exercise&apos;s
          default. A1 is the low alarm and A2 the high alarm. {LATCH_HELP}
        </p>
        {isAdmin && (
          <button
            className="btn primary"
            onClick={() => setEditing({ id: null, definition: { name: "", model: "", screen: "generic", channels: [{ ...EMPTY_CHANNEL }] } })}
          >
            New monitor
          </button>
        )}
      </div>
      <ErrorBox error={profiles.error} />
      {profiles.loading && !profiles.data && <Loading />}
      {profiles.data?.map((p: Profile) => (
        <Card
          key={p.id}
          title={
            <>
              {p.definition.name} {p.definition.model && p.definition.model !== p.definition.name && <span className="muted small">{p.definition.model}</span>}{" "}
              {p.builtin && <span className="badge subtle">built-in</span>}
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
            Version {p.version} · updated {formatTime(p.updatedAt)} · <span className="badge subtle">latch</span> = stays on until acknowledged
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
          {RANGE_FIELDS.map(([, header]) => (
            <th key={header}>{header}</th>
          ))}
          {ALARMS.map((a) => (
            <th key={a.label}>{a.label}</th>
          ))}
          {SENSOR_FIELDS.map(([, header, help]) => (
            <th key={header} title={help}>
              {header}
            </th>
          ))}
        </tr>
      </thead>
      <tbody>
        {channels.map((c) => (
          <tr key={c.id}>
            <td>
              <strong>{c.label}</strong> {c.lowAlarmFalling && <span className="muted small">(A1 falls)</span>}
            </td>
            <td>{c.unit}</td>
            {RANGE_FIELDS.map(([field]) => (
              <td key={field}>{c[field] as number}</td>
            ))}
            {ALARMS.map((a) => (
              <td key={a.label} className="nowrap">
                {c[a.field] ?? "—"} {c[a.field] != null && latches(c, a) && <span className="badge subtle">latch</span>}
              </td>
            ))}
            {SENSOR_FIELDS.map(([field]) => (
              <td key={field}>{c[field] as number}</td>
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
      <h2>{id ? `Edit ${initial.name}` : "New monitor"}</h2>
      <Card>
        <div className="row wrap">
          <label>
            Name
            <input value={profile.name} placeholder="e.g. Engine 7 X-am" onChange={(e) => setProfile({ ...profile, name: e.target.value })} />
          </label>
          <label>
            Make and model
            <input
              value={profile.model ?? ""}
              maxLength={80}
              placeholder="e.g. Dräger X-am 2800"
              onChange={(e) => setProfile({ ...profile, model: e.target.value })}
            />
          </label>
        </div>
        <p className="muted small">
          A1 is the low alarm, A2 the high alarm; leave a level blank to turn that alarm off. {LATCH_HELP} {RESPONSE_HELP}
        </p>
        <div className="scroll-x">
          <table className="table compact edit">
            <thead>
              <tr>
                <th>Id</th>
                <th>Label</th>
                <th>Unit</th>
                {RANGE_FIELDS.map(([, header]) => (
                  <th key={header}>{header}</th>
                ))}
                {ALARMS.map((a) => (
                  <th key={a.label}>{a.label} · latch</th>
                ))}
                {SENSOR_FIELDS.map(([, header, help]) => (
                  <th key={header} title={help}>
                    {header}
                  </th>
                ))}
                <th title="O₂: A1 fires when the reading falls below it">A1 falls</th>
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
                  {RANGE_FIELDS.map(([field]) => (
                    <td key={field}>
                      <NumberCell value={c[field] as number} onChange={(v) => setChannel(i, { [field]: v ?? 0 } as Partial<Channel>)} />
                    </td>
                  ))}
                  {ALARMS.map((a) => (
                    <td key={a.label} className="nowrap">
                      <NumberCell value={c[a.field]} optional onChange={(v) => setChannel(i, { [a.field]: v } as Partial<Channel>)} />
                      <input
                        type="checkbox"
                        title={`${a.label} latches until acknowledged`}
                        aria-label={`${c.label || c.id} ${a.label} latches`}
                        checked={latches(c, a)}
                        disabled={c[a.field] == null}
                        onChange={(e) => setChannel(i, { [a.latch]: e.target.checked } as Partial<Channel>)}
                      />
                    </td>
                  ))}
                  {SENSOR_FIELDS.map(([field]) => (
                    <td key={field}>
                      <NumberCell value={c[field] as number} onChange={(v) => setChannel(i, { [field]: v ?? 0 } as Partial<Channel>)} />
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

function NumberCell({ value, optional, onChange }: { value: number | null | undefined; optional?: boolean; onChange: (v: number | null) => void }) {
  return (
    <input
      type="number"
      step="any"
      size={5}
      value={value ?? ""}
      placeholder={optional ? "off" : ""}
      onChange={(e) => onChange(e.target.value === "" ? null : Number(e.target.value))}
    />
  );
}
