import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from "react";
import { Api, type Units } from "../api/endpoints";
import { unitSystem, type UnitSystem } from "./units";

interface UnitsState extends UnitSystem {
  /** Saves the system setting (admin) and applies it here at once. */
  save: (units: Units) => Promise<void>;
}

const UnitsContext = createContext<UnitsState | null>(null);

/** Display units from the system settings (imperial until loaded). Mount inside the login. */
export function UnitsProvider({ children }: { children: ReactNode }) {
  const [units, setUnits] = useState<Units>("IMPERIAL");

  useEffect(() => {
    Api.settings()
      .then((s) => setUnits(s.units))
      .catch(() => undefined); // keep the default; the pages still work
  }, []);

  const save = useCallback(async (next: Units) => {
    setUnits((await Api.updateSettings({ units: next })).units);
  }, []);

  const value = useMemo(() => ({ ...unitSystem(units), save }), [units, save]);
  return <UnitsContext.Provider value={value}>{children}</UnitsContext.Provider>;
}

export function useUnits(): UnitsState {
  const value = useContext(UnitsContext);
  if (!value) throw new Error("useUnits outside UnitsProvider");
  return value;
}
