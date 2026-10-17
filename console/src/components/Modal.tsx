import { useEffect, useRef, type ReactNode } from "react";

/**
 * A dialog over the page: Escape or the × closes it (unless [busy]); focus moves into it on open
 * and back to where it was on close.
 */
export function Modal({
  title,
  onClose,
  children,
  footer,
  busy = false,
  wide = false,
}: {
  title: string;
  onClose: () => void;
  children: ReactNode;
  footer?: ReactNode;
  busy?: boolean;
  wide?: boolean;
}) {
  const dialog = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const before = document.activeElement as HTMLElement | null;
    dialog.current?.querySelector<HTMLElement>("input, select, textarea, button:not(.modal-close)")?.focus();
    return () => before?.focus();
  }, []);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape" && !busy) onClose();
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [busy, onClose]);

  return (
    <div className="modal-backdrop">
      <div ref={dialog} className={`modal${wide ? " wide" : ""}`} role="dialog" aria-modal="true" aria-label={title}>
        <header className="modal-header">
          <h2>{title}</h2>
          <button className="modal-close" onClick={onClose} disabled={busy} aria-label="Close">
            ×
          </button>
        </header>
        <div className="modal-body">{children}</div>
        {footer && <footer className="modal-footer">{footer}</footer>}
      </div>
    </div>
  );
}
