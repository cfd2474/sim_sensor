import { useEffect, useState } from "react";
import { Api, type TokenInfo } from "../api/endpoints";
import { ErrorBox } from "../components/ui";

/**
 * Opened from an email link (/reset?token=…): a password reset or a new account's invite.
 * Works without being signed in.
 */
export function ResetPasswordPage() {
  const token = new URLSearchParams(window.location.search).get("token") ?? "";
  const [info, setInfo] = useState<TokenInfo | null>();
  const [password, setPassword] = useState("");
  const [repeat, setRepeat] = useState("");
  const [error, setError] = useState<unknown>();
  const [done, setDone] = useState(false);

  useEffect(() => {
    Api.tokenInfo(token).then(setInfo, () => setInfo(null));
  }, [token]);

  const invite = info?.purpose === "invite";
  return (
    <div className="login">
      <div className="card login-card">
        <h1>Sim Sensor</h1>
        {info === undefined && <p className="muted">Checking the link…</p>}
        {info === null && (
          <>
            <p>This link has expired or was already used.</p>
            <p className="muted small">Ask for a new one with &quot;Forgot password?&quot; on the sign-in page.</p>
            <a href="/">Go to sign in</a>
          </>
        )}
        {info && done && (
          <>
            <p className="ok-text">Your password is set. Sign in with {info.email}.</p>
            <a className="btn primary" href="/">
              Sign in
            </a>
          </>
        )}
        {info && !done && (
          <form
            className="stack"
            onSubmit={async (e) => {
              e.preventDefault();
              setError(undefined);
              if (password !== repeat) {
                setError(new Error("The two passwords differ"));
                return;
              }
              try {
                await Api.resetPassword(token, password);
                setDone(true);
              } catch (err) {
                setError(err);
              }
            }}
          >
            <p>
              {invite ? "Welcome" : "Hello"}, {info.name}. {invite ? "Choose a password for your account" : "Choose a new password"} ({info.email}).
            </p>
            <label>
              New password (at least 8 characters)
              <input type="password" autoComplete="new-password" minLength={8} value={password} onChange={(e) => setPassword(e.target.value)} required />
            </label>
            <label>
              Repeat it
              <input type="password" autoComplete="new-password" minLength={8} value={repeat} onChange={(e) => setRepeat(e.target.value)} required />
            </label>
            <ErrorBox error={error} />
            <button className="btn primary" type="submit">
              {invite ? "Set password" : "Change password"}
            </button>
          </form>
        )}
      </div>
    </div>
  );
}
