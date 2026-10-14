import { useMemo, useState } from "react";
import { ApiError } from "../../api/client";
import { Api, type ChannelOverride, type CriteriaOverrides, type Exercise } from "../../api/endpoints";
import { useResource } from "../../api/useResource";
import { AsyncButton, Card, ErrorBox, Loading } from "../../components/ui";

const FIELDS: [keyof ChannelOverride, string][] = [
  ["lowAlarm", "Low alarm"],
  ["highAlarm", "High alarm"],
  ["stelAlarm", "STEL"],
  ["twaAlarm", "TWA"],
  ["rangeMax", "Range max"],
  ["baseline", "Baseline"],
  ["t90Seconds", "T90 (s)"],
];

/**
 * The exercise's alarm criteria: template values next to this exercise's changes. A blank field
 * uses the template value. The server validates; its per-field errors appear under each input.
 */
export function CriteriaTab({ exercise, onSaved }: { exercise: Exercise; onSaved: (x: Exercise) => void }) {
  const profiles = useResource(Api.profiles, []);
  const template = useMemo(() => profiles.data?.find((p) => p.id === exercise.profileId)?.definition, [profiles.data, exercise.profileId]);
  const [draft, setDraft] = useState<CriteriaOverrides>(() => structuredClone(exercise.criteria));
  const [error, setError] = useState<unknown>();
  const [saved, setSaved] = useState(false);

  if (!template) return profiles.error ? <ErrorBox error={profiles.error} /> : <Loading />;

  const channels = draft.channels ?? {};
  const setField = (channelId: string, field: keyof ChannelOverride, raw: string) => {
    setSaved(false);
    setDraft((d) => {
      const current = { ...(d.channels?.[channelId] ?? {}) };
      if (raw === "") delete current[field];
      else current[field] = Number(raw);
      const next = { ...(d.channels ?? {}) };
      if (Object.keys(current).length === 0) delete next[channelId];
      else next[channelId] = current;
      return { ...d, channels: next };
    });
  };
  const apiError = error instanceof ApiError ? error : undefined;
  const changed = JSON.stringify(draft) !== JSON.stringify(exercise.criteria);

  return (
    <Card
      title={`Alarm criteria · template: ${template.name}`}
      actions={
        <>
          <button className="btn secondary small" onClick={() => setDraft({ channels: {}, alarmLatching: null })}>
            Reset all to template
          </button>
          <AsyncButton
            disabled={!changed}
            onClick={async () => {
              setError(undefined);
              onSaved(await Api.setCriteria(exercise.id, draft));
              setSaved(true);
            }}
            onError={setError}
          >
            Save criteria
          </AsyncButton>
        </>
      }
    >
      <p className="muted small">
        Blank fields use the template value (shown in grey). Changes reach every phone in the exercise within about a second,
        and are recorded in the event log.
      </p>
      <div className="scroll-x">
        <table className="table compact edit">
          <thead>
            <tr>
              <th>Gas</th>
              {FIELDS.map(([, header]) => (
                <th key={header}>{header}</th>
              ))}
            </tr>
          </thead>
          <tbody>
            {template.channels.map((c) => (
              <tr key={c.id}>
                <td>
                  <strong>{c.label}</strong> <span className="muted small">{c.unit}</span>
                </td>
                {FIELDS.map(([field]) => {
                  const templateValue = (c as Record<string, unknown>)[field] as number | null | undefined;
                  const override = channels[c.id]?.[field];
                  const fieldError = apiError?.fieldError(field, c.id);
                  return (
                    <td key={field} className={override != null ? "overridden" : ""}>
                      <input
                        type="number"
                        step="any"
                        size={6}
                        placeholder={templateValue == null ? "off" : String(templateValue)}
                        value={override ?? ""}
                        onChange={(e) => setField(c.id, field, e.target.value)}
                        disabled={templateValue == null && field.endsWith("Alarm")}
                        title={templateValue == null && field.endsWith("Alarm") ? "Not used by the template" : undefined}
                      />
                      {fieldError && <div className="field-error">{fieldError}</div>}
                    </td>
                  );
                })}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <label className="row">
        Alarm latching
        <select
          value={draft.alarmLatching == null ? "" : String(draft.alarmLatching)}
          onChange={(e) => {
            setSaved(false);
            setDraft({ ...draft, alarmLatching: e.target.value === "" ? null : e.target.value === "true" });
          }}
        >
          <option value="">Template ({template.alarmLatching === false ? "clear on their own" : "latch until acknowledged"})</option>
          <option value="true">Latch until acknowledged</option>
          <option value="false">Clear on their own</option>
        </select>
      </label>
      {apiError && apiError.errors.length === 0 && <ErrorBox error={error} />}
      {apiError && apiError.errors.length > 0 && <p className="field-error">Fix the highlighted values and save again.</p>}
      {!apiError && error != null && <ErrorBox error={error} />}
      {saved && <p className="ok-text small">Saved and sent to the phones.</p>}
    </Card>
  );
}
