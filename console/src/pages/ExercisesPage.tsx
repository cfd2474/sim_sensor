import { useState } from "react";
import { Link, useNavigate } from "react-router";
import { Api } from "../api/endpoints";
import { useResource } from "../api/useResource";
import { Card, ErrorBox, Loading, formatTime } from "../components/ui";

export function ExercisesPage() {
  const exercises = useResource(Api.exercises, []);
  const profiles = useResource(Api.profiles, []);
  const navigate = useNavigate();
  const [name, setName] = useState("");
  const [profileId, setProfileId] = useState("");
  const [error, setError] = useState<unknown>();

  const chosenProfile = profileId || profiles.data?.[0]?.id || "";

  return (
    <div className="stack">
      <h1>Exercises</h1>
      <Card title="New exercise">
        <form
          className="row wrap"
          onSubmit={async (e) => {
            e.preventDefault();
            setError(undefined);
            try {
              const created = await Api.createExercise(name, chosenProfile);
              navigate(`/exercises/${created.id}`);
            } catch (err) {
              setError(err);
            }
          }}
        >
          <input placeholder="Name, e.g. Rail yard drill" value={name} onChange={(e) => setName(e.target.value)} required />
          <select value={chosenProfile} onChange={(e) => setProfileId(e.target.value)}>
            {profiles.data?.map((p) => (
              <option key={p.id} value={p.id}>
                {p.definition.name}
              </option>
            ))}
          </select>
          <button className="btn primary" type="submit" disabled={!chosenProfile}>
            Create
          </button>
        </form>
        <ErrorBox error={error} />
      </Card>

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
                <th>Default monitor</th>
                <th>Updated</th>
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
                  <td>{profiles.data?.find((p) => p.id === x.profileId)?.definition.name ?? "—"}</td>
                  <td>{formatTime(x.updatedAt)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        ) : (
          <p className="muted">No exercises yet.</p>
        )}
      </Card>
    </div>
  );
}
