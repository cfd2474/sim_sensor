import { Navigate, NavLink, Route, Routes } from "react-router";
import { useAuth } from "../../auth/AuthContext";
import { InstructorsPage } from "../InstructorsPage";
import { SystemSettingsPage } from "../SystemSettingsPage";
import { EmailSettingsPage } from "./EmailSettingsPage";
import { MonitorsPage } from "./MonitorsPage";
import { ServerUpdatePage } from "./ServerUpdatePage";

/** Settings: monitors and display units (everyone reads, admins change) and, for admins, instructors, email and server update. */
export function SettingsPage() {
  const { isAdmin } = useAuth();
  return (
    <div className="stack">
      <h1>Settings</h1>
      <nav className="tabs">
        <NavLink to="/settings/monitors">Monitors</NavLink>
        <NavLink to="/settings/units">Display units</NavLink>
        {isAdmin && <NavLink to="/settings/instructors">Instructors</NavLink>}
        {isAdmin && <NavLink to="/settings/email">Email</NavLink>}
        {isAdmin && <NavLink to="/settings/update">Server update</NavLink>}
      </nav>
      <Routes>
        <Route index element={<Navigate to="monitors" replace />} />
        <Route path="monitors" element={<MonitorsPage />} />
        {/* Beacons moved to Devices (2026-10-17); keep old links working. */}
        <Route path="beacons" element={<Navigate to="/devices/beacons" replace />} />
        <Route path="units" element={<SystemSettingsPage />} />
        {isAdmin && <Route path="instructors" element={<InstructorsPage />} />}
        {isAdmin && <Route path="email" element={<EmailSettingsPage />} />}
        {isAdmin && <Route path="update" element={<ServerUpdatePage />} />}
        <Route path="*" element={<Navigate to="/settings/monitors" replace />} />
      </Routes>
    </div>
  );
}
