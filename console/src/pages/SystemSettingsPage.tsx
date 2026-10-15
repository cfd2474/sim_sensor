import { useState } from "react";
import type { Units } from "../api/endpoints";
import { useAuth } from "../auth/AuthContext";
import { AsyncButton, Card, ErrorBox } from "../components/ui";
import { useUnits } from "../units/UnitsContext";

/** System-wide settings. Everyone can see them; only admins change them. */
export function SystemSettingsPage() {
  const { isAdmin } = useAuth();
  const current = useUnits();
  const [units, setUnits] = useState<Units>(current.units);
  const [error, setError] = useState<unknown>();
  const [saved, setSaved] = useState(false);

  return (
    <div className="stack narrow">
      <Card title="Display units">
        <p className="muted small">
          Distances, leak sizes, wind speed and GPS accuracy in the console and on the phones. Data is always stored in metres; changing this
          only changes what people see. Phones update at once.
        </p>
        <label className="check">
          <input type="radio" name="units" checked={units === "IMPERIAL"} disabled={!isAdmin} onChange={() => setUnits("IMPERIAL")} />
          Imperial (feet, mph)
        </label>
        <label className="check">
          <input type="radio" name="units" checked={units === "METRIC"} disabled={!isAdmin} onChange={() => setUnits("METRIC")} />
          Metric (metres, m/s)
        </label>
        <ErrorBox error={error} />
        {saved && <p className="ok-text small">Saved.</p>}
        {isAdmin ? (
          <AsyncButton
            disabled={units === current.units}
            onClick={async () => {
              setError(undefined);
              setSaved(false);
              await current.save(units);
              setSaved(true);
            }}
            onError={setError}
          >
            Save
          </AsyncButton>
        ) : (
          <p className="muted small">Only an administrator can change this.</p>
        )}
      </Card>
    </div>
  );
}
