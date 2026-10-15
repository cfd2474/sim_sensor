import { useMemo, useState } from "react";
import { ApiError } from "../../api/client";
import { Api, type ChannelOverride, type CriteriaOverrides, type Exercise } from "../../api/endpoints";
import { useResource } from "../../api/useResource";
import { AsyncButton, Card, ErrorBox, Loading } from "../../components/ui";
import { ALARMS, LATCH_HELP, latches, RESPONSE_HELP } from "../../meter/alarms";

type NumberField = "lowAlarm" | "highAlarm" | "stelAlarm" | "twaAlarm" | "rangeMax" | "baseline" | "t90Seconds";

const OTHER_FIELDS: [NumberField, string, string?][] = [
  ["rangeMax", "Range max"],
  ["baseline", "Baseline"],
  ["t90Seconds", "Response (s)", RESPONSE_HELP],
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
  /** Sets one override; undefined goes back to the template value. */
  const setField = <K extends keyof ChannelOverride>(channelId: string, field: K, value: ChannelOverride[K] | undefined) => {
    setSaved(false);
    setDraft((d) => {
      const current: ChannelOverride = { ...(d.channels?.[channelId] ?? {}) };
      if (value === undefined) delete current[field];
      else current[field] = value;
      const next = { ...(d.channels ?? {}) };
      if (Object.keys(current).length === 0) delete next[channelId];
      else next[channelId] = current;
      return { ...d, channels: next };
    });
  };
  const apiError = error instanceof ApiError ? error : undefined;

  /** A number override over the template value; blank = template. */
  const numberInput = (channelId: string, field: NumberField, templateValue: number | null | undefined) => {
    const override = channels[channelId]?.[field];
    const fieldError = apiError?.fieldError(field, channelId);
    const unused = templateValue == null && field.endsWith("Alarm");
    return (
      <>
        <input
          type="number"
          step="any"
          size={6}
          placeholder={templateValue == null ? "off" : String(templateValue)}
          value={override ?? ""}
          onChange={(e) => setField(channelId, field, e.target.value === "" ? undefined : Number(e.target.value))}
          disabled={unused}
          title={unused ? "Not used by the template" : undefined}
        />
        {fieldError && <div className="field-error">{fieldError}</div>}
      </>
    );
  };
  const changed = JSON.stringify(draft) !== JSON.stringify(exercise.criteria);

  return (
    <Card
      title={`Alarm criteria · template: ${template.name}`}
      actions={
        <>
          <button className="btn secondary small" onClick={() => setDraft({ channels: {} })}>
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
        and are recorded in the event log. A1 is the low alarm, A2 the high alarm. {LATCH_HELP}
      </p>
      <div className="scroll-x">
        <table className="table compact edit">
          <thead>
            <tr>
              <th>Gas</th>
              {ALARMS.map((a) => (
                <th key={a.label}>{a.label}</th>
              ))}
              {OTHER_FIELDS.map(([, header, help]) => (
                <th key={header} title={help}>
                  {header}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {template.channels.map((c) => (
              <tr key={c.id}>
                <td>
                  <strong>{c.label}</strong> <span className="muted small">{c.unit}</span>
                </td>
                {ALARMS.map((a) => {
                  const latchOverride = channels[c.id]?.[a.latch];
                  return (
                    <td key={a.label} className={channels[c.id]?.[a.field] != null || latchOverride != null ? "overridden" : ""}>
                      {numberInput(c.id, a.field, c[a.field])}
                      {c[a.field] != null && (
                        <select
                          className="latch-select"
                          aria-label={`${c.label} ${a.label} latching`}
                          value={latchOverride == null ? "" : String(latchOverride)}
                          onChange={(e) => setField(c.id, a.latch, e.target.value === "" ? undefined : e.target.value === "true")}
                        >
                          <option value="">{latches(c, a) ? "latches" : "clears itself"} (template)</option>
                          <option value="true">latches</option>
                          <option value="false">clears itself</option>
                        </select>
                      )}
                    </td>
                  );
                })}
                {OTHER_FIELDS.map(([field]) => (
                  <td key={field} className={channels[c.id]?.[field] != null ? "overridden" : ""}>
                    {numberInput(c.id, field, c[field])}
                  </td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {apiError && apiError.errors.length === 0 && <ErrorBox error={error} />}
      {apiError && apiError.errors.length > 0 && <p className="field-error">Fix the highlighted values and save again.</p>}
      {!apiError && error != null && <ErrorBox error={error} />}
      {saved && <p className="ok-text small">Saved and sent to the phones.</p>}
    </Card>
  );
}
