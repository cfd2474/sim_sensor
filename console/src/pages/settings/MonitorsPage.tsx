import { useState } from "react";
import { Api, type Channel, type Profile, type SensorProfile } from "../../api/endpoints";
import { useResource } from "../../api/useResource";
import { useAuth } from "../../auth/AuthContext";
import { AsyncButton, Card, ErrorBox, Loading, formatTime } from "../../components/ui";
import { ALARMS, LATCH_HELP, latches, RESPONSE_HELP } from "../../meter/alarms";

/** Screens the phone can draw (no manufacturer logos): [key, name]. */
const SCREENS: [NonNullable<SensorProfile["screen"]>, string][] = [
  ["generic", "Sim Sensor (generic)"],
  ["g8", "Tiles, green / yellow / red (like a Blackline G8)"],
  ["xam", "Grey LCD rows, A1 / A2 alternating (like a Dräger X-am)"],
  ["io6", "Grey LCD, 2 × 3 gas cells under a status bar (like an MSA ALTAIR io 6)"],
];

/** Sensor names in the picker where the label alone doesn't say what it is. */
const SENSOR_NAMES: Record<string, string> = { VOC: "VOC (PID, isobutylene)" };

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

/** A new monitor starts as the common 4-gas set. */
const STARTER = ["O2", "LEL", "CO", "H2S"];

/** "%VOL" → "%vol", as shown next to readings. */
function unitLabel(unit: string): string {
  return unit === "%VOL" ? "%vol" : unit === "PPM" ? "ppm" : unit;
}

/**
 * Monitors: the gas meters phones imitate, set up like the agency's real ones (stored as
 * profiles). Everyone reads them; admins edit.
 */
export function MonitorsPage() {
  const { isAdmin } = useAuth();
  const profiles = useResource(Api.profiles, []);
  const sensors = useResource(Api.sensors, []);
  const [editing, setEditing] = useState<{ id: string | null; definition: SensorProfile } | null>(null);

  if (editing) {
    return (
      <ProfileEditor
        id={editing.id}
        initial={editing.definition}
        sensors={sensors.data ?? []}
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
            disabled={!sensors.data}
            onClick={() =>
              setEditing({
                id: null,
                definition: { name: "", model: "", screen: "generic", channels: (sensors.data ?? []).filter((c) => STARTER.includes(c.id)) },
              })
            }
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
              {p.builtin && <span className="badge subtle">built-in</span>}{" "}
              <span className="badge subtle">screen: {SCREENS.find(([k]) => k === (p.definition.screen ?? "generic"))?.[1].split(" (")[0]}</span>
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

function ProfileEditor({
  id,
  initial,
  sensors,
  onDone,
}: {
  id: string | null;
  initial: SensorProfile;
  /** The sensor catalog (GET /api/sensors): what a monitor's rows can be. */
  sensors: Channel[];
  onDone: () => void;
}) {
  const [profile, setProfile] = useState<SensorProfile>(initial);
  const [error, setError] = useState<unknown>();

  const setChannel = (index: number, patch: Partial<Channel>) =>
    setProfile((p) => ({ ...p, channels: p.channels.map((c, i) => (i === index ? { ...c, ...patch } : c)) }));
  const used = new Set(profile.channels.map((c) => c.id));
  const unused = sensors.filter((s) => !used.has(s.id));
  /** Choosing another sensor starts the row from that sensor's defaults. */
  const chooseSensor = (index: number, sensorId: string) => {
    const sensor = sensors.find((s) => s.id === sensorId);
    if (sensor) setProfile((p) => ({ ...p, channels: p.channels.map((c, i) => (i === index ? { ...sensor } : c)) }));
  };

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
          <label>
            Phone screen
            <select
              value={profile.screen ?? "generic"}
              onChange={(e) => setProfile({ ...profile, screen: e.target.value as SensorProfile["screen"] })}
            >
              {SCREENS.map(([key, name]) => (
                <option key={key} value={key}>
                  {name}
                </option>
              ))}
            </select>
          </label>
        </div>
        <p className="muted small">
          Pick each sensor from the list; it starts with typical settings (alarm levels from OSHA / NIOSH limits) that you can
          change to match the real device. A1 is the low alarm, A2 the high alarm; leave a level blank to turn that alarm off.{" "}
          {LATCH_HELP} {RESPONSE_HELP}
        </p>
        <div className="scroll-x">
          <table className="table compact edit">
            <thead>
              <tr>
                <th>Sensor</th>
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
                <th />
              </tr>
            </thead>
            <tbody>
              {profile.channels.map((c, i) => (
                <tr key={i}>
                  <td>
                    <select aria-label={`Sensor ${i + 1}`} value={c.id} onChange={(e) => chooseSensor(i, e.target.value)}>
                      {sensors
                        .filter((s) => s.id === c.id || !used.has(s.id))
                        .map((s) => (
                          <option key={s.id} value={s.id}>
                            {SENSOR_NAMES[s.id] ?? s.label}
                          </option>
                        ))}
                    </select>
                  </td>
                  <td className="nowrap">
                    {unitLabel(c.unit)} {c.lowAlarmFalling && <span className="muted small" title="A1 fires when the reading falls below it">A1 ↓</span>}
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
        <button
          className="btn secondary small"
          disabled={unused.length === 0}
          onClick={() => setProfile((p) => ({ ...p, channels: [...p.channels, { ...unused[0] }] }))}
        >
          Add sensor
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
