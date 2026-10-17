import { Navigate, NavLink, Route, Routes } from "react-router";
import { useAuth } from "../../auth/AuthContext";
import { InstructorsPage } from "../InstructorsPage";
import { SystemSettingsPage } from "../SystemSettingsPage";
import { BeaconsPage } from "./BeaconsPage";
import { EmailSettingsPage } from "./EmailSettingsPage";
import { MonitorsPage } from "./MonitorsPage";
import { ServerUpdatePage } from "./ServerUpdatePage";

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
        {isAdmin && <NavLink to="/settings/email">Email</NavLink>}
        {isAdmin && <NavLink to="/settings/update">Server update</NavLink>}
      </nav>
      <Routes>
        <Route index element={<Navigate to="monitors" replace />} />
        <Route path="monitors" element={<MonitorsPage />} />
        <Route path="beacons" element={<BeaconsPage />} />
        <Route path="units" element={<SystemSettingsPage />} />
        {isAdmin && <Route path="instructors" element={<InstructorsPage />} />}
        {isAdmin && <Route path="email" element={<EmailSettingsPage />} />}
        {isAdmin && <Route path="update" element={<ServerUpdatePage />} />}
        <Route path="*" element={<Navigate to="/settings/monitors" replace />} />
      </Routes>
    </div>
  );
}
