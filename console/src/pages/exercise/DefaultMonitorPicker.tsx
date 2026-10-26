import { useState } from "react";
import { Api, type Exercise } from "../../api/endpoints";
import { useResource } from "../../api/useResource";
import { ErrorBox } from "../../components/ui";
import { monitorLabel } from "../../meter/monitors";

/**
 * The exercise's default monitor, on the Live page (operator, 2026-10-26). The same setting as the
 * Settings tab: phones added to the exercise use it, and so does every phone without its own
 * monitor (Devices tab), straight away.
 */
export function DefaultMonitorPicker({ exercise, editable, onSaved }: { exercise: Exercise; editable: boolean; onSaved: (x: Exercise) => void }) {
  const profiles = useResource(Api.profiles, []);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<unknown>();
  return (
    <div className="default-monitor">
      <label className="row wrap">
        <span>Default monitor</span>
        <select
          aria-label="Default monitor"
          value={exercise.profileId}
          disabled={!editable || saving || !profiles.data}
          onChange={async (e) => {
            setSaving(true);
            setError(undefined);
            try {
              onSaved(await Api.updateExercise(exercise.id, { profileId: e.target.value }));
            } catch (err) {
              setError(err);
            } finally {
              setSaving(false);
            }
          }}
        >
          {(profiles.data ?? []).map((p) => (
            <option key={p.id} value={p.id}>
              {monitorLabel(p.definition)}
            </option>
          ))}
          {!profiles.data && <option value={exercise.profileId}>…</option>}
        </select>
      </label>
      <p className="muted small">Phones added to the exercise use it until you give them their own on the Devices tab.</p>
      <ErrorBox error={error} />
    </div>
  );
}
