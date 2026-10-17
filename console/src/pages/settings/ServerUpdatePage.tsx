import { useCallback, useEffect, useRef, useState, type ReactNode } from "react";
import { Api, type UpdateInfo } from "../../api/endpoints";
import { Modal } from "../../components/Modal";
import { Card, ErrorBox, Loading, formatTime } from "../../components/ui";

/** How often the page asks while an update runs; the server restarts in the middle of it. */
const POLL_MS = 3000;

/**
 * Settings → Server update (admins, chunk 4.6): the running and the newest version, what's new,
 * and an Update button that hands the update to the updater container. Progress keeps showing
 * while the server restarts (requests fail for a while; the page keeps asking).
 */
export function ServerUpdatePage() {
  const [info, setInfo] = useState<UpdateInfo | null>(null);
  const [error, setError] = useState<unknown>();
  const [restarting, setRestarting] = useState(false);
  const [confirm, setConfirm] = useState(false);
  const watching = useRef(false);

  const load = useCallback(async () => {
    try {
      const next = await Api.updateInfo();
      setInfo(next);
      setRestarting(false);
      setError(undefined);
      return next;
    } catch (e) {
      if (watching.current) setRestarting(true);
      else setError(e);
      return null;
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  // Keep asking while an update is under way (or the server is restarting).
  const running = !!info && (info.pending || info.status?.state === "running");
  useEffect(() => {
    watching.current = running || restarting;
    if (!running && !restarting) return;
    const timer = window.setInterval(() => void load(), POLL_MS);
    return () => window.clearInterval(timer);
  }, [running, restarting, load]);

  if (!info) return error ? <ErrorBox error={error} /> : <Loading />;
  const status = info.status;
  const finished = status && (status.state === "succeeded" || status.state === "failed") ? status : null;
  const consoleIsOld = finished?.state === "succeeded" && status?.tag === `v${info.currentVersion}`;

  return (
    <div className="stack">
      <Card title="Server version">
        <table className="table compact">
          <tbody>
            <tr>
              <th>Running</th>
              <td>v{info.currentVersion}</td>
            </tr>
            <tr>
              <th>Newest on GitHub</th>
              <td>{info.latestVersion ? `v${info.latestVersion}` : <span className="muted">couldn't check (try again later)</span>}</td>
            </tr>
          </tbody>
        </table>
        {!info.updaterReady && <p className="warn-text">{info.updaterMessage ?? "Server updates are not available."}</p>}
        {info.updaterReady && !info.updateAvailable && !running && <p className="muted">The server is up to date.</p>}
        {info.updateAvailable && !running && (
          <div className="row">
            <button className="btn primary large" disabled={!info.updaterReady} onClick={() => setConfirm(true)}>
              Update to v{info.latestVersion}
            </button>
          </div>
        )}
        <ErrorBox error={error} />
      </Card>

      {(running || restarting) && (
        <Card title={`Updating to ${status?.tag ?? `v${info.latestVersion}`}`}>
          <p aria-live="polite">
            <span className="spinner" />{" "}
            {restarting ? "The server is restarting…" : status?.state === "running" ? status.message : "Waiting for the updater to start…"}
          </p>
          <p className="muted small">
            This takes several minutes. Phones keep their readings and reconnect by themselves. You can leave this page; the update
            carries on.
          </p>
        </Card>
      )}

      {finished && !running && !restarting && (
        <Card title={finished.state === "succeeded" ? "Last update: done" : "Last update: failed"}>
          <p className={finished.state === "succeeded" ? "" : "warn-text"}>{finished.message}</p>
          <p className="muted small">
            {finished.requestedBy && <>By {finished.requestedBy} · </>}
            {finished.updatedAt && formatTime(finished.updatedAt)}
          </p>
          {consoleIsOld && (
            <button className="btn secondary" onClick={() => window.location.reload()}>
              Reload the console (new version)
            </button>
          )}
        </Card>
      )}

      {info.releaseNotes && info.updateAvailable && (
        <Card title="What's new">
          <ReleaseNotes markdown={info.releaseNotes} />
        </Card>
      )}

      {confirm && info.latestVersion && (
        <ConfirmUpdate
          from={info.currentVersion}
          to={info.latestVersion}
          onClose={() => setConfirm(false)}
          onStarted={(next) => {
            setConfirm(false);
            setInfo(next);
            watching.current = true;
          }}
        />
      )}
    </div>
  );
}

function ConfirmUpdate({ from, to, onClose, onStarted }: { from: string; to: string; onClose: () => void; onStarted: (i: UpdateInfo) => void }) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>();
  return (
    <Modal
      title={`Update the server to v${to}?`}
      onClose={onClose}
      busy={busy}
      footer={
        <>
          <button className="btn secondary" onClick={onClose} disabled={busy}>
            Cancel
          </button>
          <button
            className="btn primary"
            disabled={busy}
            onClick={async () => {
              setBusy(true);
              try {
                onStarted(await Api.startUpdate(`v${to}`));
              } catch (e) {
                setError(e);
                setBusy(false);
              }
            }}
          >
            {busy ? "Starting…" : "Update now"}
          </button>
        </>
      }
    >
      <div className="stack">
        <p>
          The server goes from v{from} to v{to}: the database is backed up, the new version is downloaded from GitHub and built, and the
          server restarts.
        </p>
        <ul>
          <li>It takes several minutes. The console and phones are offline for about a minute of that.</li>
          <li>Phones reconnect by themselves; running exercises carry on.</li>
          <li>If the new version doesn't start, the server goes back to v{from} by itself.</li>
        </ul>
        <ErrorBox error={error} />
      </div>
    </Modal>
  );
}

/** Just enough Markdown for RELEASE_NOTES.md: version headings, bullets, **bold**, `code`. */
function ReleaseNotes({ markdown }: { markdown: string }) {
  const blocks: ReactNode[] = [];
  let bullets: string[] = [];
  const flush = () => {
    if (bullets.length) blocks.push(<ul key={`u${blocks.length}`}>{bullets.map((b, i) => <li key={i}>{inline(b)}</li>)}</ul>);
    bullets = [];
  };
  for (const raw of markdown.split("\n")) {
    const line = raw.trimEnd();
    if (line.startsWith("## ")) {
      flush();
      blocks.push(<h3 key={`h${blocks.length}`}>{line.slice(3)}</h3>);
    } else if (/^\s*- /.test(line)) {
      bullets.push(line.replace(/^\s*- /, ""));
    } else if (/^\s{2,}\S/.test(line) && bullets.length) {
      bullets[bullets.length - 1] += " " + line.trim();
    } else if (line.trim()) {
      flush();
      blocks.push(<p key={`p${blocks.length}`}>{inline(line)}</p>);
    } else flush();
  }
  flush();
  return <div className="release-notes">{blocks}</div>;
}

function inline(text: string): ReactNode[] {
  return text.split(/(\*\*[^*]+\*\*|`[^`]+`)/).map((part, i) =>
    part.startsWith("**") ? <strong key={i}>{part.slice(2, -2)}</strong> : part.startsWith("`") ? <code key={i}>{part.slice(1, -1)}</code> : part,
  );
}
