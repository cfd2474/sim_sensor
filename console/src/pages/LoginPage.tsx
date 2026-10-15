import { useEffect, useState } from "react";
import { Api } from "../api/endpoints";
import { useAuth } from "../auth/AuthContext";
import { ErrorBox } from "../components/ui";

export function LoginPage() {
  const { login } = useAuth();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<unknown>();
  const [busy, setBusy] = useState(false);
  const [canReset, setCanReset] = useState(false);
  const [forgot, setForgot] = useState(false);
  const [sent, setSent] = useState(false);

  useEffect(() => {
    Api.authOptions().then((o) => setCanReset(o.forgotPassword), () => setCanReset(false));
  }, []);

  if (forgot) {
    return (
      <div className="login">
        <form
          className="card login-card"
          onSubmit={async (e) => {
            e.preventDefault();
            setError(undefined);
            try {
              await Api.forgotPassword(email);
              setSent(true);
            } catch (err) {
              setError(err);
            }
          }}
        >
          <h1>Reset password</h1>
          {sent ? (
            <p>If an account exists for {email}, an email with a reset link is on its way. The link works for one hour.</p>
          ) : (
            <>
              <p className="muted">Enter your account&apos;s email address. We&apos;ll send you a link to choose a new password.</p>
              <label>
                Email
                <input type="email" autoComplete="username" value={email} onChange={(e) => setEmail(e.target.value)} required />
              </label>
              <ErrorBox error={error} />
              <button className="btn primary" type="submit">
                Send reset link
              </button>
            </>
          )}
          <button type="button" className="link" onClick={() => { setForgot(false); setSent(false); }}>
            Back to sign in
          </button>
        </form>
      </div>
    );
  }

  return (
    <div className="login">
      <form
        className="card login-card"
        onSubmit={async (e) => {
          e.preventDefault();
          setBusy(true);
          setError(undefined);
          try {
            await login(email, password);
          } catch (err) {
            setError(err);
          } finally {
            setBusy(false);
          }
        }}
      >
        <h1>Sim Sensor</h1>
        <p className="muted">Instructor console · simulated gas detectors for training</p>
        <label>
          Email
          <input type="email" autoComplete="username" value={email} onChange={(e) => setEmail(e.target.value)} required />
        </label>
        <label>
          Password
          <input type="password" autoComplete="current-password" value={password} onChange={(e) => setPassword(e.target.value)} required />
        </label>
        <ErrorBox error={error} />
        <button className="btn primary" type="submit" disabled={busy}>
          {busy ? "Logging in…" : "Log in"}
        </button>
        {canReset && (
          <button type="button" className="link" onClick={() => setForgot(true)}>
            Forgot password?
          </button>
        )}
      </form>
    </div>
  );
}
