import { useState } from "react";
import { useNavigate } from "react-router";
import { Api, type Exercise } from "../../api/endpoints";
import { useResource } from "../../api/useResource";
import { AsyncButton, Card, ErrorBox } from "../../components/ui";
import { NumberInput } from "../../leaks/NumberInput";
import { useUnits } from "../../units/UnitsContext";

export function SettingsTab({ exercise, onSaved }: { exercise: Exercise; onSaved: (x: Exercise) => void }) {
  const profiles = useResource(Api.profiles, []);
  const instructors = useResource(Api.instructors, []);
  const navigate = useNavigate();
  const units = useUnits();
  const [form, setForm] = useState({
    name: exercise.name,
    profileId: exercise.profileId,
    o2Displacement: exercise.o2Displacement,
    windDirDeg: exercise.windDirDeg ?? "",
  });
  const [windSpeedMps, setWindSpeedMps] = useState<number | null>(exercise.windSpeedMps ?? null);
  const [team, setTeam] = useState<string[]>(exercise.instructorIds);
  const [error, setError] = useState<unknown>();

  return (
    <div className="stack">
      <Card title="Exercise">
        <div className="form-grid">
          <label>
            Name
            <input value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} />
          </label>
          <label>
            Meter setup
            <select value={form.profileId} onChange={(e) => setForm({ ...form, profileId: e.target.value })}>
              {profiles.data?.map((p) => (
                <option key={p.id} value={p.id}>
                  {p.definition.name}
                </option>
              ))}
            </select>
          </label>
          <label>
            Wind from (degrees, 0 = north)
            <input type="number" min={0} max={359} value={form.windDirDeg} onChange={(e) => setForm({ ...form, windDirDeg: e.target.value })} />
          </label>
          <label>
            Wind speed ({units.speed.label})
            <NumberInput value={windSpeedMps} optional onChange={setWindSpeedMps} toDisplay={units.speed.toDisplay} fromDisplay={units.speed.fromDisplay} />
          </label>
          <label className="check">
            <input type="checkbox" checked={form.o2Displacement} onChange={(e) => setForm({ ...form, o2Displacement: e.target.checked })} />
            Other gases lower O₂ (displacement)
          </label>
        </div>
        <p className="muted small">
          Plumes drift away from the wind direction; the wind can also be changed on the Live map. With O₂ displacement on, O₂ drops where
          other gases are high.
        </p>
        <ErrorBox error={error} />
        <AsyncButton
          onClick={async () => {
            setError(undefined);
            onSaved(
              await Api.updateExercise(exercise.id, {
                name: form.name,
                profileId: form.profileId,
                o2Displacement: form.o2Displacement,
                windDirDeg: form.windDirDeg === "" ? undefined : Number(form.windDirDeg),
                windSpeedMps: windSpeedMps ?? undefined,
              }),
            );
          }}
          onError={setError}
        >
          Save
        </AsyncButton>
      </Card>

      <Card title="Instructors">
        <p className="muted small">Instructors listed here see and control this exercise.</p>
        <ul className="plain">
          {instructors.data
            ?.filter((i) => !i.disabled)
            .map((i) => (
              <li key={i.id}>
                <label className="check">
                  <input
                    type="checkbox"
                    checked={team.includes(i.id)}
                    onChange={(e) => setTeam(e.target.checked ? [...team, i.id] : team.filter((t) => t !== i.id))}
                  />
                  {i.name} <span className="muted small">{i.email}</span>
                </label>
              </li>
            ))}
        </ul>
        <AsyncButton onClick={async () => onSaved(await Api.setExerciseInstructors(exercise.id, team))} onError={setError}>
          Save instructors
        </AsyncButton>
      </Card>

      <Card title="Delete">
        <p className="muted small">Phones in the exercise go to standby. The event log and telemetry of this exercise are deleted.</p>
        <AsyncButton
          kind="danger"
          confirm={`Delete ${exercise.name}? This can't be undone.`}
          onClick={async () => {
            await Api.deleteExercise(exercise.id);
            navigate("/exercises");
          }}
        >
          Delete exercise
        </AsyncButton>
      </Card>
    </div>
  );
}
