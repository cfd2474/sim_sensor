import { useCallback, useEffect, useState } from "react";
import { Api, type BackupFile, type BackupsInfo } from "../../api/endpoints";
import { Modal } from "../../components/Modal";
import { Card, ErrorBox, formatTime } from "../../components/ui";

/** While the list is out of date (a backup running, a new list asked for), ask this often. */
const POLL_MS = 4000;
/** After the retention changes, the updater lists the backups again within a few seconds. */
const RELIST_MS = 20000;
const CONFIRM_WORD = "restore";

function size(bytes: number): string {
  if (bytes < 1024 * 1024) return `${Math.max(1, Math.round(bytes / 1024))} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

/** What made the backup, from the name the updater gives it. */
export function backupKind(b: BackupFile): string {
  switch (b.kind) {
    case "before-update": {
      const tag = /^pre-(v\d+\.\d+\.\d+)-/.exec(b.name)?.[1];
      return tag ? `Before the update to ${tag}` : "Before an update";
    }
    case "manual":
      return "Made from this page";
    case "before-restore":
      return "Before a restore (the data it replaced)";
    default:
      return "Other";
  }
}

/**
 * Settings → Server update → Backups (admins, chunk 4.9): the database backups on the server, a
 * backup now, a restore, and how long backups are kept. The updater does the work; progress shows
 * in the page's progress card ([onStarted] tells the page to watch).
 */
export function BackupsCard({ running, onStarted }: { running: boolean; onStarted: (action: "backup" | "restore", file?: string) => void }) {
  const [info, setInfo] = useState<BackupsInfo | null>(null);
  const [error, setError] = useState<unknown>();
  const [busy, setBusy] = useState(false);
  const [restoring, setRestoring] = useState<BackupFile | null>(null);
  const [days, setDays] = useState("");
  const [savedNote, setSavedNote] = useState<string | null>(null);
  const [relistUntil, setRelistUntil] = useState(0);

  const load = useCallback(async () => {
    try {
      const next = await Api.backups();
      setInfo(next);
      setDays((d) => (d === "" ? String(next.retentionDays) : d));
      setError(undefined);
    } catch (e) {
      // The server is down during a restore; the page's progress card says so.
      if (!running) setError(e);
    }
  }, [running]);

  // Again whenever an action finishes (running → false), so a new backup appears.
  useEffect(() => {
    void load();
  }, [load, running]);

  const stale = !!info && (info.busy || (info.updaterReady && !info.inventoryAt) || Date.now() < relistUntil);
  useEffect(() => {
    if (!stale || running) return;
    const timer = window.setInterval(() => void load(), POLL_MS);
    return () => window.clearInterval(timer);
  }, [stale, running, load]);

  if (!info) return <Card title="Backups">{error ? <ErrorBox error={error} /> : <p className="muted">Loading…</p>}</Card>;
  const canAct = info.updaterReady && !info.busy && !running && !busy;
  const daysNumber = Number(days);
  const daysValid = Number.isInteger(daysNumber) && daysNumber >= 1 && daysNumber <= 365;

  return (
    <Card title="Backups">
      <p className="muted small">
        Database backups kept on the server: one is made before every update, and you can make one at any time. Restoring one puts the
        database back as it was then.
      </p>
      {info.backups.length ? (
        <table className="table compact backups-table">
          <thead>
            <tr>
              <th>Made</th>
              <th>Why</th>
              <th>Size</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {info.backups.map((b) => (
              <tr key={b.name}>
                <td title={b.name}>{formatTime(b.createdAt)}</td>
                <td>{backupKind(b)}</td>
                <td>{size(b.sizeBytes)}</td>
                <td>
                  <button className="btn secondary small" disabled={!canAct} onClick={() => setRestoring(b)}>
                    Restore…
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      ) : (
        <p className="muted">{info.updaterReady && !info.inventoryAt ? "Asking the updater for the list…" : "No backups yet."}</p>
      )}
      <div className="row">
        <button
          className="btn primary"
          disabled={!canAct}
          onClick={async () => {
            setBusy(true);
            try {
              setInfo(await Api.makeBackup());
              onStarted("backup");
            } catch (e) {
              setError(e);
            } finally {
              setBusy(false);
            }
          }}
        >
          Make backup now
        </button>
        {info.busy && <span className="muted small">An update, backup or restore is under way.</span>}
      </div>

      <h3 className="backups-heading">Keep backups for</h3>
      <form
        className="row wrap"
        onSubmit={async (e) => {
          e.preventDefault();
          if (!daysValid) return;
          setBusy(true);
          setSavedNote(null);
          try {
            const next = await Api.setBackupRetention(daysNumber);
            setInfo(next);
            setDays(String(next.retentionDays));
            setSavedNote(`Saved: backups older than ${next.retentionDays} days are deleted.`);
            setRelistUntil(Date.now() + RELIST_MS);
          } catch (err) {
            setError(err);
          } finally {
            setBusy(false);
          }
        }}
      >
        <label className="row">
          <input
            type="number"
            min={1}
            max={365}
            value={days}
            aria-label="Days to keep backups"
            onChange={(e) => {
              setDays(e.target.value);
              setSavedNote(null);
            }}
            size={4}
          />
          days
        </label>
        <button className="btn secondary" type="submit" disabled={busy || !daysValid || daysNumber === info.retentionDays || !info.updaterReady}>
          Save
        </button>
      </form>
      <p className={daysValid ? "muted small" : "warn-text"}>
        {daysValid
          ? "Older backups are deleted automatically; the newest one is always kept, however old. Default 7 days."
          : "Enter 1 to 365 days."}
      </p>
      {savedNote && <p className="small" aria-live="polite">{savedNote}</p>}
      {!info.updaterReady && <p className="warn-text">{info.updaterMessage ?? "Backups are not available."}</p>}
      <ErrorBox error={error} />

      {restoring && (
        <ConfirmRestore
          backup={restoring}
          onClose={() => setRestoring(null)}
          onStarted={(next) => {
            onStarted("restore", restoring.name);
            setRestoring(null);
            setInfo(next);
          }}
        />
      )}
    </Card>
  );
}

function ConfirmRestore({ backup, onClose, onStarted }: { backup: BackupFile; onClose: () => void; onStarted: (i: BackupsInfo) => void }) {
  const [typed, setTyped] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>();
  const confirmed = typed.trim().toLowerCase() === CONFIRM_WORD;
  return (
    <Modal
      title="Restore this backup?"
      onClose={onClose}
      busy={busy}
      footer={
        <>
          <button className="btn secondary" onClick={onClose} disabled={busy}>
            Cancel
          </button>
          <button
            className="btn danger"
            disabled={busy || !confirmed}
            onClick={async () => {
              setBusy(true);
              try {
                onStarted(await Api.restoreBackup(backup.name));
              } catch (e) {
                setError(e);
                setBusy(false);
              }
            }}
          >
            {busy ? "Starting…" : "Restore"}
          </button>
        </>
      }
    >
      <div className="stack">
        <p>
          The database goes back to <strong>{formatTime(backup.createdAt)}</strong> ({backupKind(backup).toLowerCase()}).
        </p>
        <ul>
          <li>
            <strong>Everything recorded since then is replaced:</strong> exercises, readings, phones enrolled, beacons, accounts and
            settings go back to how they were.
          </li>
          <li>The current data is backed up first, so this can be undone by restoring that backup.</li>
          <li>The server stops for a minute or two; phones reconnect by themselves. You may have to sign in again.</li>
          <li>If the restore fails, the current data is put back by itself.</li>
        </ul>
        <label>
          <span>
            Type <strong>{CONFIRM_WORD}</strong> to confirm
          </span>
          <input value={typed} onChange={(e) => setTyped(e.target.value)} autoFocus autoComplete="off" />
        </label>
        <ErrorBox error={error} />
      </div>
    </Modal>
  );
}
