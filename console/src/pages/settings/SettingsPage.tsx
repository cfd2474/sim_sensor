import { Navigate, NavLink, Route, Routes } from "react-router";
import { useAuth } from "../../auth/AuthContext";
import { InstructorsPage } from "../InstructorsPage";
import { SystemSettingsPage } from "../SystemSettingsPage";
import { BeaconsPage } from "./BeaconsPage";
import { MonitorsPage } from "./MonitorsPage";

/** Settings: display units, monitors (everyone reads, admins change) and, for admins, instructors. */
export function SettingsPage() {
  const { isAdmin } = useAuth();
  return (
    <div className="stack">
      <h1>Settings</h1>
      <nav className="tabs">
        <NavLink to="/settings/monitors">Monitors</NavLink>
        <NavLink to="/settings/beacons">Beacons</NavLink>
        <NavLink to="/settings/units">Display units</NavLink>
        {isAdmin && <NavLink to="/settings/instructors">Instructors</NavLink>}
      </nav>
      <Routes>
        <Route index element={<Navigate to="monitors" replace />} />
        <Route path="monitors" element={<MonitorsPage />} />
        <Route path="beacons" element={<BeaconsPage />} />
        <Route path="units" element={<SystemSettingsPage />} />
        {isAdmin && <Route path="instructors" element={<InstructorsPage />} />}
        <Route path="*" element={<Navigate to="/settings/monitors" replace />} />
      </Routes>
    </div>
  );
}
