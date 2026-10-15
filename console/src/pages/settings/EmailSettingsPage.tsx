import { useEffect, useState } from "react";
import { Api, type SmtpSettingsInput } from "../../api/endpoints";
import { useResource } from "../../api/useResource";
import { AsyncButton, Card, ErrorBox, Loading } from "../../components/ui";

type Security = NonNullable<SmtpSettingsInput["security"]>;

/** Provider presets (from the providers' SMTP documentation). */
const PRESETS: Record<string, { host: string; port: number; security: Security; username?: string; hint: string }> = {
  brevo: {
    host: "smtp-relay.brevo.com",
    port: 587,
    security: "STARTTLS",
    hint: "Brevo → SMTP & API → SMTP: use the SMTP login as the username and an SMTP key (not an API key) as the password.",
  },
  resend: {
    host: "smtp.resend.com",
    port: 465,
    security: "SSL",
    username: "resend",
    hint: "Resend: the username is \"resend\" and the password is a Resend API key.",
  },
  other: { host: "", port: 587, security: "STARTTLS", hint: "Any SMTP server: ask your provider for the server, port and login." },
};

function presetOf(host: string): string {
  return Object.entries(PRESETS).find(([key, p]) => key !== "other" && p.host === host)?.[0] ?? "other";
}

/**
 * Admin: the SMTP connection the server uses for "Forgot password" and new-account emails.
 * The password is write-only: the server never sends it back.
 */
export function EmailSettingsPage() {
  const current = useResource(Api.smtpSettings, []);
  const [form, setForm] = useState<SmtpSettingsInput>({ host: "", port: 587, security: "STARTTLS", username: "", password: "", fromAddress: "", fromName: "Sim Sensor" });
  const [preset, setPreset] = useState("brevo");
  const [error, setError] = useState<unknown>();
  const [status, setStatus] = useState<string>();

  useEffect(() => {
    const c = current.data;
    if (!c) return;
    if (c.configured) {
      setForm({ host: c.host ?? "", port: c.port ?? 587, security: c.security ?? "STARTTLS", username: c.username ?? "", password: "", fromAddress: c.fromAddress ?? "", fromName: c.fromName ?? "Sim Sensor" });
      setPreset(presetOf(c.host ?? ""));
    } else {
      const p = PRESETS.brevo;
      setForm((f) => ({ ...f, host: p.host, port: p.port, security: p.security }));
    }
  }, [current.data]);

  if (current.error) return <ErrorBox error={current.error} />;
  if (!current.data) return <Loading />;
  const passwordSet = current.data.passwordSet;

  return (
    <div className="stack narrow">
      <Card title="Email (SMTP)">
        <p className="muted small">
          Used to send &quot;Forgot password&quot; links and to tell new instructors their account is ready. Works with any SMTP
          provider; the sender address must be verified with the provider.
        </p>
        <label>
          Provider
          <select
            value={preset}
            onChange={(e) => {
              const p = PRESETS[e.target.value];
              setPreset(e.target.value);
              setForm({ ...form, host: p.host || form.host, port: p.port, security: p.security, username: p.username ?? (preset === "resend" ? "" : form.username) });
            }}
          >
            <option value="brevo">Brevo</option>
            <option value="resend">Resend</option>
            <option value="other">Other SMTP server</option>
          </select>
        </label>
        <p className="muted small">{PRESETS[preset].hint}</p>
        <div className="form-grid">
          <label>
            Server
            <input value={form.host} onChange={(e) => setForm({ ...form, host: e.target.value })} required />
          </label>
          <label>
            Port
            <input type="number" value={form.port} onChange={(e) => setForm({ ...form, port: Number(e.target.value) })} />
          </label>
          <label>
            Security
            <select value={form.security} onChange={(e) => setForm({ ...form, security: e.target.value as Security })}>
              <option value="STARTTLS">STARTTLS (587)</option>
              <option value="SSL">SSL/TLS (465)</option>
              <option value="NONE">None (test servers only)</option>
            </select>
          </label>
          <label>
            Username
            <input value={form.username ?? ""} autoComplete="off" onChange={(e) => setForm({ ...form, username: e.target.value })} />
          </label>
          <label>
            Password / key
            <input
              type="password"
              autoComplete="new-password"
              placeholder={passwordSet ? "saved (leave blank to keep)" : ""}
              value={form.password ?? ""}
              onChange={(e) => setForm({ ...form, password: e.target.value })}
            />
          </label>
          <label>
            Sender address
            <input type="email" placeholder="noreply@your-domain" value={form.fromAddress} onChange={(e) => setForm({ ...form, fromAddress: e.target.value })} required />
          </label>
          <label>
            Sender name
            <input value={form.fromName ?? ""} onChange={(e) => setForm({ ...form, fromName: e.target.value })} />
          </label>
        </div>
        <ErrorBox error={error} />
        {status && <p className="ok-text small">{status}</p>}
        <div className="row">
          <AsyncButton
            onClick={async () => {
              setError(undefined);
              setStatus(undefined);
              current.setData(await Api.saveSmtpSettings(form));
              setForm((f) => ({ ...f, password: "" }));
              setStatus("Saved.");
            }}
            onError={setError}
          >
            Save
          </AsyncButton>
          <AsyncButton
            kind="secondary"
            disabled={!current.data.configured}
            onClick={async () => {
              setError(undefined);
              setStatus(undefined);
              const r = await Api.testSmtp();
              setStatus(`Test email sent to ${r.sentTo}. Check that inbox (and spam).`);
            }}
            onError={setError}
          >
            Send test email
          </AsyncButton>
        </div>
      </Card>
    </div>
  );
}
