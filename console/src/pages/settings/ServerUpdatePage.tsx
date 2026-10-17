import { useCallback, useEffect, useRef, useState, type ReactNode } from "react";
import { Api, type UpdateInfo } from "../../api/endpoints";
import { Modal } from "../../components/Modal";
import { Card, ErrorBox, Loading, formatTime } from "../../components/ui";
import { BackupsCard } from "./BackupsCard";

/** How often the page asks while an update runs; the server restarts in the middle of it. */
const POLL_MS = 3000;

type Action = "update" | "backup" | "restore";

/** The updater's steps for each action (deploy/updater/update.sh), in order. */
const STEPS: Record<Action, [string, string][]> = {
  update: [
    ["check", "Checking the new version on GitHub"],
    ["download", "Downloading it"],
    ["backup", "Backing up the database"],
    ["build", "Building and restarting the server (the longest step: several minutes with no visible change)"],
    ["health", "Checking that the new version starts"],
  ],
  backup: [["backup", "Backing up the database"]],
  restore: [
    ["safety", "Backing up the current data first"],
    ["stop", "Stopping the server"],
    ["restore", "Restoring the backup"],
    ["start", "Starting the server and checking it answers"],
  ],
};

/** How each action is named on the page. */
const NAMES: Record<Action, { running: string; last: string; took: string; refresh: string }> = {
  update: {
    running: "Updating",
    last: "Last update",
    took: "It usually takes 5–8 minutes in all.",
    refresh: "This page refreshes by itself when the update is complete.",
  },
  backup: {
    running: "Making a backup",
    last: "Last backup",
    took: "It usually takes under a minute; the server keeps running.",
    refresh: "This page shows the new backup by itself when it is done.",
  },
  restore: {
    running: "Restoring a backup",
    last: "Last restore",
    took: "It usually takes 1–3 minutes; the console and phones are offline for most of it.",
    refresh: "This page refreshes by itself when the restore is complete.",
  },
};

function actionOf(status: UpdateInfo["status"]): Action {
  return status?.action === "backup" || status?.action === "restore" ? status.action : "update";
}

function duration(seconds: number): string {
  const m = Math.floor(seconds / 60);
  const s = Math.floor(seconds % 60);
  return m ? `${m} min ${s.toString().padStart(2, "0")} s` : `${s} s`;
}

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
  const [lastAnswer, setLastAnswer] = useState(Date.now());
  const [now, setNow] = useState(Date.now());
  const [reloading, setReloading] = useState(false);
  const watching = useRef(false);
  /** The version being installed while this page watched; reaching it reloads the console. */
  const target = useRef<string | null>(null);
  /**
   * A restore this page started or saw running; when it is done the console reloads (its data
   * changed). `before` is the status start time when it was asked for, so the result of an earlier
   * restore doesn't count (a restore can finish between two polls).
   */
  const restoreWatched = useRef<{ before: string | null } | null>(null);
  /** What this page last asked for, shown until the updater reports on it. */
  const [requested, setRequested] = useState<{ action: Action; file?: string } | null>(null);

  const load = useCallback(async () => {
    try {
      const next = await Api.updateInfo();
      setInfo(next);
      setRestarting(false);
      setError(undefined);
      setLastAnswer(Date.now());
      if (next.status?.state === "running" && actionOf(next.status) === "update" && next.status.tag) target.current = next.status.tag;
      if (next.status?.state === "running" && actionOf(next.status) === "restore" && !restoreWatched.current)
        restoreWatched.current = { before: null };
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

  // A clock for the elapsed time and "last checked" counters.
  useEffect(() => {
    if (!running && !restarting) return;
    const timer = window.setInterval(() => setNow(Date.now()), 1000);
    return () => window.clearInterval(timer);
  }, [running, restarting]);

  // The new version answered: load its console (this page's code is from the old one). After a
  // restore, reload too: everything the console shows changed.
  useEffect(() => {
    if (!info || reloading || info.status?.state !== "succeeded") return;
    const updated = !!target.current && `v${info.currentVersion}` === target.current && actionOf(info.status) === "update";
    const restored =
      !!restoreWatched.current && actionOf(info.status) === "restore" && (info.status.startedAt ?? null) !== restoreWatched.current.before;
    if (updated || restored) {
      setReloading(true);
      window.setTimeout(() => window.location.reload(), 3000);
    }
  }, [info, reloading]);

  if (!info) return error ? <ErrorBox error={error} /> : <Loading />;
  const status = info.status;
  const finished = status && (status.state === "succeeded" || status.state === "failed") ? status : null;
  const finishedAction = actionOf(finished);
  const consoleIsOld = finishedAction === "update" && finished?.state === "succeeded" && status?.tag === `v${info.currentVersion}`;
  const startWatching = (action: Action, file?: string) => {
    setRequested({ action, file });
    if (action === "restore") restoreWatched.current = { before: info?.status?.startedAt ?? null };
    watching.current = true;
    void load();
  };

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
        <CertificateLine />
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

      {reloading && (
        <Card title={actionOf(status) === "restore" ? "Restore complete" : "Update complete"}>
          <p aria-live="polite">
            <span className="spinner" />{" "}
            {actionOf(status) === "restore" ? "The backup is restored." : `The server runs v${info.currentVersion}.`} Reloading the console…
          </p>
        </Card>
      )}

      {(running || restarting) && !reloading && (
        <UpdateProgress
          info={info}
          requested={requested}
          restarting={restarting}
          now={now}
          lastAnswerSecondsAgo={Math.max(0, Math.round((now - lastAnswer) / 1000))}
        />
      )}

      {finished && !running && !restarting && (
        <Card title={`${NAMES[finishedAction].last}: ${finished.state === "succeeded" ? "done" : "failed"}`}>
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

      <BackupsCard running={running || restarting} onStarted={startWatching} />

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
            setRequested({ action: "update" });
            watching.current = true;
          }}
        />
      )}
    </div>
  );
}

/** The HTTPS certificate: Caddy renews it by itself; the server checks it daily. */
function CertificateLine() {
  const [cert, setCert] = useState<Awaited<ReturnType<typeof Api.certificate>> | undefined>(undefined);
  useEffect(() => {
    Api.certificate().then(setCert, () => setCert(null));
  }, []);
  if (!cert) return null;
  return (
    <p className={cert.warning ? "warn-text" : "muted small"}>
      HTTPS certificate for {cert.host}:{" "}
      {cert.notAfter ? `valid until ${formatTime(cert.notAfter)} (${cert.daysLeft} days); renews automatically.` : "couldn't be read."}
      {cert.warning && <> {cert.warning}</>}
    </p>
  );
}

/** Progress while an update runs: steps, elapsed time, and signs of life. */
function UpdateProgress({
  info,
  requested,
  restarting,
  now,
  lastAnswerSecondsAgo,
}: {
  info: UpdateInfo;
  requested: { action: Action; file?: string } | null;
  restarting: boolean;
  now: number;
  lastAnswerSecondsAgo: number;
}) {
  const status = info.status?.state === "running" ? info.status : null;
  // Before the updater reports (a request waiting), the last status may be from an earlier action.
  const action = status ? actionOf(status) : (requested?.action ?? null);
  const file = status?.file ?? requested?.file;
  const steps = action ? STEPS[action] : [];
  const stepIndex = status ? steps.findIndex(([id]) => id === status.step) : -1;
  const started = status?.startedAt ? Date.parse(status.startedAt) : null;
  const rollingBack = status?.step === "rollback";
  const names = action ? NAMES[action] : null;
  const title = !action
    ? "Starting…"
    : action === "update"
      ? `Updating to ${status?.tag ?? `v${info.latestVersion}`}`
      : action === "restore" && file
        ? `Restoring ${file}`
        : names!.running;
  return (
    <Card title={title}>
      {names && (
        <p className="update-note">
          <strong>{names.refresh}</strong> {names.took} You can also leave this page; the work carries on.
        </p>
      )}
      {rollingBack ? (
        <p className="warn-text">{status?.message}</p>
      ) : (
        <ol className="update-steps">
          {steps.map(([id, label], i) => {
            // While the server restarts, the build is done and the start-up check is under way.
            const index = restarting && action === "update" ? steps.length - 1 : stepIndex;
            const state = index < 0 ? "todo" : i < index ? "done" : i === index ? "now" : "todo";
            return (
              <li key={id} className={`update-step ${state}`}>
                <span className="update-step-mark">{state === "done" ? "✓" : state === "now" ? <span className="spinner" /> : "○"}</span>
                {label}
              </li>
            );
          })}
        </ol>
      )}
      <p className="muted small" aria-live="polite">
        {started != null && <>Running for {duration((now - started) / 1000)}. </>}
        {restarting ? (
          <>
            The server is {action === "restore" ? "stopped for the restore" : "restarting"}, so it doesn't answer for a minute or two;
            that is expected. This page asks again every few
            seconds (last answer {duration(lastAnswerSecondsAgo)} ago).
          </>
        ) : info.updaterSeenSecondsAgo != null && info.updaterSeenSecondsAgo <= 30 ? (
          <>The updater checked in {duration(info.updaterSeenSecondsAgo + lastAnswerSecondsAgo)} ago, so it is working, not stuck.</>
        ) : info.updaterSeenSecondsAgo != null ? (
          <>
            The updater is busy with this step and reports again when it finishes; a step can run several minutes without a word
            here. That is normal. This page asks every few seconds (last answer {duration(lastAnswerSecondsAgo)} ago).
          </>
        ) : (
          <>Waiting for the updater to pick up the request (within a few seconds).</>
        )}
      </p>
      <p className="muted small">Phones keep their readings and reconnect by themselves after the restart.</p>
    </Card>
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
