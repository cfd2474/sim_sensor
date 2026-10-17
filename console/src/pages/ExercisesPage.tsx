import { useState } from "react";
import { Link } from "react-router";
import { Api, type Exercise } from "../api/endpoints";
import { useResource } from "../api/useResource";
import { Card, ErrorBox, Loading, formatTime } from "../components/ui";
import { CreateExerciseModal } from "./exercises/CreateExerciseModal";
import { DeleteExerciseModal } from "./exercises/DeleteExerciseModal";

export function ExercisesPage() {
  const exercises = useResource(Api.exercises, []);
  const profiles = useResource(Api.profiles, []);
  const [creating, setCreating] = useState(false);
  const [deleting, setDeleting] = useState<Exercise | null>(null);

  return (
    <div className="stack">
      <div className="page-header">
        <h1>Exercises</h1>
        <button className="btn primary large" onClick={() => setCreating(true)}>
          + Create new exercise
        </button>
      </div>

      <Card title="Your exercises">
        <ErrorBox error={exercises.error} />
        {exercises.loading && !exercises.data ? (
          <Loading />
        ) : exercises.data?.length ? (
          <table className="table">
            <thead>
              <tr>
                <th>Name</th>
                <th>State</th>
                <th>Location</th>
                <th>Default monitor</th>
                <th>Updated</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {exercises.data.map((x) => (
                <tr key={x.id}>
                  <td>
                    <Link to={`/exercises/${x.id}`}>{x.name}</Link>
                  </td>
                  <td>
                    <span className={`state state-${x.state}`}>{x.state}</span>
                  </td>
                  <td className="small">{x.location?.label ?? <span className="muted">—</span>}</td>
                  <td>{profiles.data?.find((p) => p.id === x.profileId)?.definition.name ?? "—"}</td>
                  <td>{formatTime(x.updatedAt)}</td>
                  <td className="nowrap">
                    <button className="btn secondary small danger-outline" onClick={() => setDeleting(x)} aria-label={`Delete ${x.name}`}>
                      Delete
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        ) : (
          <p className="muted">No exercises yet. Use “Create new exercise” to set one up.</p>
        )}
      </Card>

      {creating && <CreateExerciseModal onClose={() => setCreating(false)} />}
      {deleting && (
        <DeleteExerciseModal
          exercise={deleting}
          onClose={() => setDeleting(null)}
          onDeleted={() => {
            setDeleting(null);
            void exercises.reload();
          }}
        />
      )}
    </div>
  );
}
