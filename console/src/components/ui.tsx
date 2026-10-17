import type { ReactNode } from "react";
import { ApiError, errorMessage } from "../api/client";

/** Shows an error from a failed request, with the per-field messages when it was a 422. */
export function ErrorBox({ error }: { error: unknown }) {
  if (!error) return null;
  const details = error instanceof ApiError ? error.errors : [];
  return (
    <div className="error-box" role="alert">
      <strong>{errorMessage(error)}</strong>
      {details.length > 0 && (
        <ul>
          {details.map((e, i) => (
            <li key={i}>
              {e.channelId ? `${e.channelId} · ` : ""}
              {e.field}: {e.message}
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

export function Card({ title, actions, children }: { title?: ReactNode; actions?: ReactNode; children: ReactNode }) {
  return (
    <section className="card">
      {(title || actions) && (
        <header className="card-head">
          {title && <h2>{title}</h2>}
          {actions && <div className="row">{actions}</div>}
        </header>
      )}
      {children}
    </section>
  );
}

export function Loading() {
  return <p className="muted">Loading…</p>;
}

/** Runs an async action from a button, disabling it and reporting errors. */
export function AsyncButton({
  onClick,
  children,
  kind = "primary",
  disabled,
  confirm,
  onError,
  small,
}: {
  onClick: () => Promise<unknown>;
  children: ReactNode;
  kind?: "primary" | "secondary" | "danger";
  disabled?: boolean;
  confirm?: string;
  onError?: (e: unknown) => void;
  /** Table-row size, like `btn small`. */
  small?: boolean;
}) {
  return (
    <button
      className={`btn ${kind}${small ? " small" : ""}`}
      disabled={disabled}
      onClick={async (event) => {
        const button = event.currentTarget;
        if (confirm && !window.confirm(confirm)) return;
        button.disabled = true;
        try {
          await onClick();
        } catch (e) {
          if (onError) onError(e);
          else window.alert(errorMessage(e));
        } finally {
          button.disabled = false;
        }
      }}
    >
      {children}
    </button>
  );
}

export function formatTime(iso: string | null | undefined): string {
  if (!iso) return "—";
  return new Date(iso).toLocaleString();
}

export function relativeTime(iso: string | null | undefined): string {
  if (!iso) return "never";
  const seconds = Math.round((Date.now() - new Date(iso).getTime()) / 1000);
  if (seconds < 60) return `${Math.max(seconds, 0)} s ago`;
  if (seconds < 3600) return `${Math.round(seconds / 60)} min ago`;
  if (seconds < 86400) return `${Math.round(seconds / 3600)} h ago`;
  return new Date(iso).toLocaleDateString();
}
