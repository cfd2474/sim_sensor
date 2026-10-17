import { useEffect, useRef, useState } from "react";
import QRCode from "qrcode";
import { Api, type CreatedToken, type EnrollmentToken } from "../api/endpoints";
import { useResource } from "../api/useResource";
import { useAuth } from "../auth/AuthContext";
import { AsyncButton, Card, ErrorBox, formatTime } from "../components/ui";

/** Enrollment: QR codes for phones (single use) and MDM fleet tokens (admins). */
export function EnrollPage() {
  const { isAdmin } = useAuth();
  const tokens = useResource(Api.enrollmentTokens, []);
  const app = useResource(Api.app, []);
  const [qr, setQr] = useState<CreatedToken | null>(null);
  const [fleet, setFleet] = useState<CreatedToken | null>(null);
  const [label, setLabel] = useState("");
  const [minutes, setMinutes] = useState(15);
  const [fleetLabel, setFleetLabel] = useState("MDM fleet");
  const [fleetRetire, setFleetRetire] = useState("");
  const [error, setError] = useState<unknown>();

  return (
    <div className="stack">
      <h1>Enroll phones</h1>

      <Card title="1 · Install the app">
        {app.data?.available && app.data.app ? (
          <div className="qr-block">
            <QrImage text={app.data.url} />
            <div className="stack small">
              <div>
                Scan with the phone&apos;s camera to download <strong>Sim Sensor {app.data.app.version}</strong> (
                {(app.data.app.sizeBytes / 1_048_576).toFixed(1)} MB), or open{" "}
                <a href={app.data.url}>{app.data.url}</a>.
              </div>
              <div className="muted">
                Android asks to allow installs from the browser the first time. Phones managed by ATLAS get the app pushed
                instead.
              </div>
              <div className="muted">
                Published {formatTime(app.data.app.publishedAt)} · versionCode {app.data.app.versionCode} · SHA-256{" "}
                <code className="wrap-anywhere">{app.data.app.sha256}</code>
              </div>
            </div>
          </div>
        ) : (
          <p className="muted small">The app has not been published on this server yet.</p>
        )}
      </Card>

      <Card title="2 · Enroll with a QR code (one phone)">
        <p className="muted small">
          The trainee opens Sim Sensor and taps <strong>Scan QR code</strong>, or scans with the phone&apos;s camera app. Each
          code enrolls one phone and expires.
        </p>
        <div className="row wrap">
          <input placeholder="Label (optional)" value={label} onChange={(e) => setLabel(e.target.value)} />
          <label className="row">
            Valid for
            <select value={minutes} onChange={(e) => setMinutes(Number(e.target.value))}>
              <option value={15}>15 minutes</option>
              <option value={60}>1 hour</option>
              <option value={480}>8 hours</option>
              <option value={1440}>24 hours</option>
            </select>
          </label>
          <AsyncButton
            onClick={async () => {
              setError(undefined);
              setQr(await Api.createEnrollmentToken({ kind: "single", label: label || undefined, ttlMinutes: minutes }));
              await tokens.reload();
            }}
            onError={setError}
          >
            Create QR code
          </AsyncButton>
        </div>
        <ErrorBox error={error} />
        {qr && (
          <div className="qr-block">
            <QrImage text={qr.link} />
            <div className="stack small">
              <div>
                <strong>Expires</strong> {formatTime(qr.info.expiresAt)}
              </div>
              <div className="muted">Shown once. Create another code for each phone.</div>
              <code className="wrap-anywhere">{qr.link}</code>
            </div>
          </div>
        )}
      </Card>

      {isAdmin && (
        <Card title="MDM fleet token (ATLAS)">
          <p className="muted small">
            One token enrolls any number of phones with no taps. Push these managed-configuration keys to Sim Sensor
            (<code>com.taksolutions.simsensor</code>) from the MDM. A phone that loses its enrollment (removed on the phone or
            revoked here) enrolls again by itself while the token is valid.
          </p>
          <p className="muted small">
            <strong>Retiring a token</strong> stops new enrollments with it; phones already enrolled keep working. To rotate:
            create a new token, push it through the MDM, then retire the old one on a date after the push (or now).
          </p>
          <div className="row wrap">
            <label className="row">
              Label <input value={fleetLabel} maxLength={64} onChange={(e) => setFleetLabel(e.target.value)} />
            </label>
            <label className="row">
              Retire on (optional) <input type="datetime-local" value={fleetRetire} onChange={(e) => setFleetRetire(e.target.value)} />
            </label>
            <AsyncButton
              onClick={async () => {
                const ttlMinutes = fleetRetire ? Math.ceil((new Date(fleetRetire).getTime() - Date.now()) / 60000) : undefined;
                setFleet(await Api.createEnrollmentToken({ kind: "fleet", label: fleetLabel.trim() || undefined, ttlMinutes }));
                setFleetRetire("");
                await tokens.reload();
              }}
            >
              Create fleet token
            </AsyncButton>
          </div>
          {fleet && (
            <pre className="code">
              {JSON.stringify(
                {
                  server_url: window.location.origin,
                  enrollment_token: fleet.token,
                  device_name: "(optional, per phone)",
                  settings_pin: "(optional)",
                },
                null,
                2,
              )}
            </pre>
          )}
        </Card>
      )}

      <Card title="Codes and tokens">
        <ErrorBox error={tokens.error} />
        <table className="table">
          <thead>
            <tr>
              <th>Kind</th>
              <th>Label</th>
              <th>Created</th>
              <th>Expires / retires</th>
              <th>Status</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {tokens.data?.map((t) => {
              const status = tokenStatus(t);
              return (
                <tr key={t.id} className={status === "active" ? "" : "dim"}>
                  <td>{t.kind === "single" ? "QR" : "Fleet"}</td>
                  <td>{t.label ?? "—"}</td>
                  <td>{formatTime(t.createdAt)}</td>
                  <td>{t.expiresAt ? formatTime(t.expiresAt) : "never"}</td>
                  <td>{status}</td>
                  <td>
                    {status === "active" &&
                      (t.kind === "fleet" && isAdmin ? (
                        <FleetRetire token={t} onChanged={tokens.reload} />
                      ) : (
                        <AsyncButton kind="secondary" onClick={() => Api.revokeEnrollmentToken(t.id).then(tokens.reload)}>
                          Revoke
                        </AsyncButton>
                      ))}
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </Card>
    </div>
  );
}

/** Fleet tokens "retire" (no new phones); QR codes are "used" or "expired". */
function tokenStatus(t: EnrollmentToken): string {
  const past = t.expiresAt != null && new Date(t.expiresAt) < new Date();
  if (t.kind === "fleet") return t.revokedAt || past ? "retired" : "active";
  return t.revokedAt ? "revoked" : t.usedAt ? "used" : past ? "expired" : "active";
}

/** `datetime-local` value for an ISO instant, in the browser's time zone. */
function localInput(iso: string): string {
  const d = new Date(iso);
  return new Date(d.getTime() - d.getTimezoneOffset() * 60000).toISOString().slice(0, 16);
}

/** Retirement date of an active fleet token: set, change, clear, or retire now. */
function FleetRetire({ token, onChanged }: { token: EnrollmentToken; onChanged: () => Promise<unknown> }) {
  const [when, setWhen] = useState(token.expiresAt ? localInput(token.expiresAt) : "");
  const [error, setError] = useState<unknown>();
  return (
    <div className="stack">
      <div className="row wrap">
        <input type="datetime-local" value={when} onChange={(e) => setWhen(e.target.value)} aria-label="Retire on" />
        <AsyncButton
          kind="secondary"
          disabled={!when}
          onClick={() => Api.setTokenRetirement(token.id, new Date(when).toISOString()).then(onChanged)}
          onError={setError}
        >
          Retire on date
        </AsyncButton>
        {token.expiresAt && (
          <AsyncButton kind="secondary" onClick={() => Api.setTokenRetirement(token.id, null).then(onChanged)} onError={setError}>
            Clear date
          </AsyncButton>
        )}
        <AsyncButton
          kind="danger"
          confirm={`Retire "${token.label ?? "fleet token"}" now? New phones can no longer enroll with it; enrolled phones keep working.`}
          onClick={() => Api.revokeEnrollmentToken(token.id).then(onChanged)}
        >
          Retire now
        </AsyncButton>
      </div>
      <ErrorBox error={error} />
    </div>
  );
}

function QrImage({ text }: { text: string }) {
  const canvas = useRef<HTMLCanvasElement>(null);
  useEffect(() => {
    if (canvas.current) void QRCode.toCanvas(canvas.current, text, { width: 280, margin: 2, errorCorrectionLevel: "M" });
  }, [text]);
  return <canvas ref={canvas} className="qr" aria-label="Enrollment QR code" />;
}
