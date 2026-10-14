import { useEffect, useRef, useState } from "react";
import QRCode from "qrcode";
import { Api, type CreatedToken } from "../api/endpoints";
import { useResource } from "../api/useResource";
import { useAuth } from "../auth/AuthContext";
import { AsyncButton, Card, ErrorBox, formatTime } from "../components/ui";

/** Enrollment: QR codes for phones (single use) and MDM fleet tokens (admins). */
export function EnrollPage() {
  const { isAdmin } = useAuth();
  const tokens = useResource(Api.enrollmentTokens, []);
  const [qr, setQr] = useState<CreatedToken | null>(null);
  const [fleet, setFleet] = useState<CreatedToken | null>(null);
  const [label, setLabel] = useState("");
  const [minutes, setMinutes] = useState(15);
  const [error, setError] = useState<unknown>();

  return (
    <div className="stack">
      <h1>Enroll phones</h1>

      <Card title="QR code (one phone)">
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
            (<code>com.taksolutions.simsensor</code>) from the MDM. Revoke the token to stop new enrollments; phones already
            enrolled keep working.
          </p>
          <AsyncButton
            onClick={async () => {
              setFleet(await Api.createEnrollmentToken({ kind: "fleet", label: "MDM fleet" }));
              await tokens.reload();
            }}
          >
            Create fleet token
          </AsyncButton>
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
              <th>Expires</th>
              <th>Status</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {tokens.data?.map((t) => {
              const expired = t.expiresAt != null && new Date(t.expiresAt) < new Date();
              const status = t.revokedAt ? "revoked" : t.usedAt ? "used" : expired ? "expired" : "active";
              return (
                <tr key={t.id} className={status === "active" ? "" : "dim"}>
                  <td>{t.kind === "single" ? "QR" : "Fleet"}</td>
                  <td>{t.label ?? "—"}</td>
                  <td>{formatTime(t.createdAt)}</td>
                  <td>{t.expiresAt ? formatTime(t.expiresAt) : "never"}</td>
                  <td>{status}</td>
                  <td>
                    {status === "active" && (
                      <AsyncButton kind="secondary" onClick={() => Api.revokeEnrollmentToken(t.id).then(tokens.reload)}>
                        Revoke
                      </AsyncButton>
                    )}
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

function QrImage({ text }: { text: string }) {
  const canvas = useRef<HTMLCanvasElement>(null);
  useEffect(() => {
    if (canvas.current) void QRCode.toCanvas(canvas.current, text, { width: 280, margin: 2, errorCorrectionLevel: "M" });
  }, [text]);
  return <canvas ref={canvas} className="qr" aria-label="Enrollment QR code" />;
}
