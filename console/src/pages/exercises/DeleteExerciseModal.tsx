import { useState } from "react";
import { Api, type Exercise } from "../../api/endpoints";
import { Modal } from "../../components/Modal";
import { ErrorBox } from "../../components/ui";

/**
 * Confirms deleting an exercise and says what goes with it. A running exercise is ended first
 * (the server refuses to delete one that runs), which the instructor confirms explicitly.
 */
export function DeleteExerciseModal({ exercise, onClose, onDeleted }: { exercise: Exercise; onClose: () => void; onDeleted: () => void }) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>();
  const running = exercise.state === "running";

  const remove = async () => {
    setBusy(true);
    setError(undefined);
    try {
      if (running) await Api.lifecycle(exercise.id, "end");
      await Api.deleteExercise(exercise.id);
      onDeleted();
    } catch (e) {
      setError(e);
      setBusy(false);
    }
  };

  return (
    <Modal
      title={`Delete “${exercise.name}”?`}
      onClose={onClose}
      busy={busy}
      footer={
        <>
          <button className="btn secondary" onClick={onClose} disabled={busy}>
            Cancel
          </button>
          <button className="btn danger" onClick={() => void remove()} disabled={busy}>
            {busy ? "Deleting…" : running ? "End and delete" : "Delete exercise"}
          </button>
        </>
      }
    >
      <div className="stack">
        {running && (
          <p className="warn-text">This exercise is running. It will be ended first, and its phones go straight to waiting.</p>
        )}
        <p>
          This permanently deletes the exercise with its leaks, event log and recorded readings. Phones in it are not deleted; they go
          back to waiting for an exercise. Beacons stay in the inventory.
        </p>
        <p className="muted small">This can't be undone.</p>
        <ErrorBox error={error} />
      </div>
    </Modal>
  );
}
