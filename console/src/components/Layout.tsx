import { useEffect } from "react";
import { Link, NavLink, Outlet, useLocation } from "react-router";
import { useAuth } from "../auth/AuthContext";

export function Layout() {
  const { me, logout, refresh } = useAuth();
  const location = useLocation();
  // Picks up the default admin being deactivated (Instructors page) on the next page change.
  useEffect(() => {
    if (me?.defaultAdminActive) void refresh();
  }, [location.pathname]); // eslint-disable-line react-hooks/exhaustive-deps
  return (
    <div className="app">
      <header className="topbar">
        <span className="brand">Sim Sensor</span>
        <span className="badge">Instructor console</span>
        <nav className="nav">
          <NavLink to="/exercises">Exercises</NavLink>
          <NavLink to="/devices">Devices</NavLink>
          <NavLink to="/enroll">Enroll phones</NavLink>
          <NavLink to="/settings">Settings</NavLink>
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
        {me?.defaultAdminActive && (
          <div className="default-admin-warning" role="alert">
            <strong>The default admin account (admin@simsensor.local) is still active.</strong> Its password is published in
            the install guide, so anyone can sign in with it. <Link to="/settings/instructors">Create your own admin account</Link>,
            sign in with it, then deactivate the default account.
          </div>
        )}
        <Outlet />
      </main>
    </div>
  );
}
