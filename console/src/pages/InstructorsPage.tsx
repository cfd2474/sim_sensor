import { useState } from "react";
import { Api } from "../api/endpoints";
import { useResource } from "../api/useResource";
import { useAuth } from "../auth/AuthContext";
import { AsyncButton, Card, ErrorBox, Loading, formatTime } from "../components/ui";

/** Admin: instructor accounts. */
export function InstructorsPage() {
  const { me, isAdmin } = useAuth();
  const list = useResource(Api.instructors, []);
  const [form, setForm] = useState({ email: "", name: "", password: "", role: "instructor" });
  const smtp = useResource(Api.smtpSettings, []);
  const emailReady = smtp.data?.configured === true;
  const [invite, setInvite] = useState(true);
  const sendInvite = emailReady && invite;
  const [error, setError] = useState<unknown>();
  const [status, setStatus] = useState<string>();

  if (!isAdmin) return <p className="muted">Admins only.</p>;

  return (
    <div className="stack">
      <Card title="Add an instructor">
        <form
          className="row wrap"
          onSubmit={async (e) => {
            e.preventDefault();
            setError(undefined);
            setStatus(undefined);
            try {
              const created = await Api.createInstructor({ ...form, password: sendInvite ? undefined : form.password, sendInvite });
              setForm({ email: "", name: "", password: "", role: "instructor" });
              if (sendInvite) {
                setStatus(created.inviteSent ? `Invite sent to ${created.email}.` : `Account created, but the invite email to ${created.email} failed: check Settings → Email.`);
              }
              await list.reload();
            } catch (err) {
              setError(err);
            }
          }}
        >
          <input placeholder="Name" value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} required />
          <input type="email" placeholder="Email" value={form.email} onChange={(e) => setForm({ ...form, email: e.target.value })} required />
          {!sendInvite && (
            <input
              type="password"
              placeholder="Initial password (8+)"
              autoComplete="new-password"
              minLength={8}
              value={form.password}
              onChange={(e) => setForm({ ...form, password: e.target.value })}
              required
            />
          )}
          <select value={form.role} onChange={(e) => setForm({ ...form, role: e.target.value })}>
            <option value="instructor">Instructor</option>
            <option value="admin">Admin</option>
          </select>
          <button className="btn primary" type="submit">
            Add
          </button>
          {emailReady ? (
            <label className="check small">
              <input type="checkbox" checked={invite} onChange={(e) => setInvite(e.target.checked)} />
              Email them a link to set their password
            </label>
          ) : (
            <span className="muted small">Set up Settings → Email to send invites instead of passwords.</span>
          )}
        </form>
        {status && <p className="ok-text small">{status}</p>}
        <ErrorBox error={error} />
      </Card>

      <Card title="Accounts">
        <ErrorBox error={list.error} />
        {list.loading && !list.data ? (
          <Loading />
        ) : (
          <table className="table">
            <thead>
              <tr>
                <th>Name</th>
                <th>Email</th>
                <th>Role</th>
                <th>Status</th>
                <th>Since</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {list.data?.map((i) => {
                const self = i.id === me?.id;
                return (
                  <tr key={i.id} className={i.disabled ? "dim" : ""}>
                    <td>{i.name}</td>
                    <td>{i.email}</td>
                    <td>
                      <select
                        value={i.role}
                        disabled={self}
                        onChange={(e) => Api.updateInstructor(i.id, { role: e.target.value }).then(list.reload, (err) => window.alert(String(err.message)))}
                      >
                        <option value="instructor">Instructor</option>
                        <option value="admin">Admin</option>
                      </select>
                    </td>
                    <td>{i.disabled ? "Disabled" : "Active"}</td>
                    <td>{formatTime(i.createdAt)}</td>
                    <td className="row">
                      {!self && (
                        <AsyncButton kind="secondary" onClick={() => Api.updateInstructor(i.id, { disabled: !i.disabled }).then(list.reload)}>
                          {i.disabled ? "Enable" : "Disable"}
                        </AsyncButton>
                      )}
                      <AsyncButton
                        kind="secondary"
                        onClick={async () => {
                          const password = window.prompt(`New password for ${i.name} (at least 8 characters):`);
                          if (password) await Api.updateInstructor(i.id, { password });
                        }}
                      >
                        Reset password
                      </AsyncButton>
                      {emailReady && !i.disabled && (
                        <AsyncButton
                          kind="secondary"
                          confirm={`Email ${i.name} a link to choose a new password?`}
                          onClick={async () => {
                            await Api.sendResetEmail(i.id);
                            setStatus(`Reset link sent to ${i.email}.`);
                          }}
                        >
                          Email reset link
                        </AsyncButton>
                      )}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        )}
      </Card>
    </div>
  );
}
