import { useEffect, useState } from "react";
import { fetchHealth, type Health } from "./api";

type ServerStatus =
  | { kind: "loading" }
  | { kind: "ok"; health: Health }
  | { kind: "error"; message: string };

export default function App() {
  const [status, setStatus] = useState<ServerStatus>({ kind: "loading" });

  useEffect(() => {
    const controller = new AbortController();
    fetchHealth(controller.signal)
      .then((health) => setStatus({ kind: "ok", health }))
      .catch((e: unknown) => {
        if (!controller.signal.aborted) setStatus({ kind: "error", message: String(e) });
      });
    return () => controller.abort();
  }, []);

  return (
    <main className="shell">
      <header>
        <h1>Sim Sensor</h1>
        <span className="badge">Instructor console</span>
      </header>
      <section className="card">
        <h2>Server</h2>
        {status.kind === "loading" && <p>Checking…</p>}
        {status.kind === "ok" && (
          <p>
            <span className="dot ok" /> {status.health.status} · v{status.health.version}
          </p>
        )}
        {status.kind === "error" && (
          <p>
            <span className="dot bad" /> Unreachable: {status.message}
          </p>
        )}
      </section>
      <p className="muted">Exercises, devices and the live map arrive in Phase 1.</p>
    </main>
  );
}
