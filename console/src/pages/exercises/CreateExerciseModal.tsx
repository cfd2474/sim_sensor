import { useState } from "react";
import { useNavigate } from "react-router";
import { Api, type ExerciseLocation } from "../../api/endpoints";
import { useResource } from "../../api/useResource";
import { LocationPicker } from "../../components/LocationPicker";
import { Modal } from "../../components/Modal";
import { ErrorBox, Loading } from "../../components/ui";

/**
 * "Create new exercise" (operator 2026-10-16): name, monitor, location, phones and beacons in one
 * dialog. A phone already in another exercise is shown as such and is moved here only when ticked.
 * Beacons may be in several exercises at once.
 */
export function CreateExerciseModal({ onClose }: { onClose: () => void }) {
  const navigate = useNavigate();
  const profiles = useResource(Api.profiles, []);
  const devices = useResource(Api.devices, []);
  const exercises = useResource(Api.exercises, []);
  const beacons = useResource(Api.beacons, []);
  const [name, setName] = useState("");
  const [profileId, setProfileId] = useState("");
  const [location, setLocation] = useState<ExerciseLocation | null>(null);
  const [phones, setPhones] = useState<Set<string>>(new Set());
  const [chosenBeacons, setChosenBeacons] = useState<Set<string>>(new Set());
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>();
  const [problems, setProblems] = useState<string[]>([]);

  const monitor = profileId || profiles.data?.[0]?.id || "";
  const activePhones = (devices.data ?? []).filter((d) => !d.revokedAt);
  const exerciseOf = (id: string | null | undefined) => exercises.data?.find((x) => x.id === id);
  const toggle = (set: Set<string>, id: string, update: (s: Set<string>) => void) => {
    const next = new Set(set);
    if (next.has(id)) next.delete(id);
    else next.add(id);
    update(next);
  };

  const create = async () => {
    setBusy(true);
    setError(undefined);
    setProblems([]);
    try {
      const created = await Api.createExercise(name.trim(), monitor, location);
      const failed: string[] = [];
      for (const id of phones) {
        const phone = activePhones.find((d) => d.id === id);
        await Api.assignDevice(id, created.id).catch(() => failed.push(`phone ${phone?.name ?? id}`));
      }
      for (const id of chosenBeacons) {
        const beacon = beacons.data?.find((b) => b.id === id);
        await Api.addExerciseBeacon(created.id, id).catch(() => failed.push(`beacon ${beacon?.label ?? id}`));
      }
      if (failed.length) {
        setProblems(failed);
        setBusy(false);
        // The exercise exists; let the instructor open it and finish there.
        setTimeout(() => navigate(`/exercises/${created.id}`), 4000);
        return;
      }
      navigate(`/exercises/${created.id}`);
    } catch (e) {
      setError(e);
      setBusy(false);
    }
  };

  const loading = !profiles.data || !devices.data || !exercises.data || !beacons.data;
  return (
    <Modal
      title="Create new exercise"
      onClose={onClose}
      busy={busy}
      wide
      footer={
        <>
          <button className="btn secondary" onClick={onClose} disabled={busy}>
            Cancel
          </button>
          <button className="btn primary" onClick={() => void create()} disabled={busy || !name.trim() || !monitor}>
            {busy ? "Creating…" : "Create exercise"}
          </button>
        </>
      }
    >
      {loading ? (
        <Loading />
      ) : (
        <div className="stack">
          <section className="stack">
            <h3>1 · Details</h3>
            <label>
              Exercise name
              <input value={name} onChange={(e) => setName(e.target.value)} placeholder="e.g. Rail yard drill" maxLength={120} required />
            </label>
            <label>
              Default monitor
              <select value={monitor} onChange={(e) => setProfileId(e.target.value)}>
                {profiles.data!.map((p) => (
                  <option key={p.id} value={p.id}>
                    {p.definition.name}
                  </option>
                ))}
              </select>
            </label>
            <div>
              <div className="field-label">Location (optional; the map starts here)</div>
              <LocationPicker value={location} onChange={setLocation} />
            </div>
          </section>

          <section className="stack">
            <h3>2 · Phones</h3>
            {activePhones.length === 0 ? (
              <p className="muted small">No phones enrolled yet. Enroll them on the Enroll phones page; you can add them later.</p>
            ) : (
              <ul className="pick-list">
                {activePhones.map((d) => {
                  const other = exerciseOf(d.exerciseId);
                  const chosen = phones.has(d.id);
                  const live = other && (other.state === "running" || other.state === "paused");
                  return (
                    <li key={d.id}>
                      <label className="check">
                        <input type="checkbox" checked={chosen} onChange={() => toggle(phones, d.id, setPhones)} />
                        <strong>{d.name}</strong>
                        <span className="muted small">{d.model ?? ""}</span>
                      </label>
                      {other && (
                        <div className={`pick-note${chosen ? " moving" : ""}`}>
                          {chosen ? (
                            <>
                              Will be <strong>moved here from “{other.name}”</strong>
                              {live ? ", which is running: the phone leaves it as soon as you create this exercise." : "."}
                            </>
                          ) : (
                            <>
                              ⚠ In “{other.name}” ({other.state}). Tick it to remove it from there and add it here.
                            </>
                          )}
                        </div>
                      )}
                    </li>
                  );
                })}
              </ul>
            )}
          </section>

          <section className="stack">
            <h3>3 · Beacons</h3>
            {beacons.data!.length === 0 ? (
              <p className="muted small">No beacons yet (Devices → Beacons). You can add them later.</p>
            ) : (
              <>
                <ul className="pick-list">
                  {beacons.data!.map((b) => (
                    <li key={b.id}>
                      <label className="check">
                        <input type="checkbox" checked={chosenBeacons.has(b.id)} onChange={() => toggle(chosenBeacons, b.id, setChosenBeacons)} />
                        <strong>{b.label}</strong>
                        <span className="muted small">
                          major {b.major} · minor {b.minor}
                        </span>
                      </label>
                    </li>
                  ))}
                </ul>
                <p className="muted small">A beacon can be in several exercises at once; it stays in the others.</p>
              </>
            )}
          </section>

          <ErrorBox error={error} />
          {problems.length > 0 && (
            <p className="warn-text">
              The exercise was created, but these could not be added: {problems.join(", ")}. Opening it so you can add them on its
              Devices tab…
            </p>
          )}
        </div>
      )}
    </Modal>
  );
}
