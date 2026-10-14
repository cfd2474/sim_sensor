import { useState } from "react";
import { Api } from "../api/endpoints";
import { useAuth } from "../auth/AuthContext";
import { Card, ErrorBox } from "../components/ui";

export function AccountPage() {
  const { me, logout } = useAuth();
  const [current, setCurrent] = useState("");
  const [next, setNext] = useState("");
  const [error, setError] = useState<unknown>();

  return (
    <div className="stack narrow">
      <h1>Account</h1>
      <Card title="You">
        <p>
          {me?.name} · {me?.email} · {me?.role}
        </p>
      </Card>
      <Card title="Change password">
        <form
          className="stack"
          onSubmit={async (e) => {
            e.preventDefault();
            setError(undefined);
            try {
              await Api.changePassword(current, next);
              window.alert("Password changed. Log in again with the new password.");
              await logout();
            } catch (err) {
              setError(err);
            }
          }}
        >
          <label>
            Current password
            <input type="password" autoComplete="current-password" value={current} onChange={(e) => setCurrent(e.target.value)} required />
          </label>
          <label>
            New password (at least 12 characters)
            <input type="password" autoComplete="new-password" minLength={12} value={next} onChange={(e) => setNext(e.target.value)} required />
          </label>
          <ErrorBox error={error} />
          <button className="btn primary" type="submit">
            Change password
          </button>
        </form>
      </Card>
    </div>
  );
}
