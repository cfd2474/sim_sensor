import { NavLink, Outlet } from "react-router";
import { useAuth } from "../auth/AuthContext";

export function Layout() {
  const { me, logout, isAdmin } = useAuth();
  return (
    <div className="app">
      <header className="topbar">
        <span className="brand">Sim Sensor</span>
        <span className="badge">Instructor console</span>
        <nav className="nav">
          <NavLink to="/exercises">Exercises</NavLink>
          <NavLink to="/devices">Devices</NavLink>
          <NavLink to="/enroll">Enroll phones</NavLink>
          <NavLink to="/profiles">Meter setups</NavLink>
          {isAdmin && <NavLink to="/instructors">Instructors</NavLink>}
          <NavLink to="/settings">System</NavLink>
        </nav>
        <span className="spacer" />
        <NavLink to="/account" className="muted">
          {me?.name}
        </NavLink>
        <button className="btn secondary small" onClick={() => void logout()}>
          Log out
        </button>
      </header>
      <main className="content">
        <Outlet />
      </main>
    </div>
  );
}
