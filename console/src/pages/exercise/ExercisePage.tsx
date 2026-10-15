import { NavLink, Route, Routes, useParams } from "react-router";
import { Api, type LifecycleAction } from "../../api/endpoints";
import { useResource } from "../../api/useResource";
import { AsyncButton, ErrorBox, Loading } from "../../components/ui";
import { CriteriaTab } from "./CriteriaTab";
import { DevicesTab } from "./DevicesTab";
import { EventsTab } from "./EventsTab";
import { LiveTab } from "./LiveTab";
import { SettingsTab } from "./SettingsTab";

const ACTIONS: Record<string, { action: LifecycleAction; label: string; kind?: "primary" | "secondary" | "danger"; confirm?: string }[]> = {
  draft: [{ action: "start", label: "Start" }],
  running: [
    { action: "pause", label: "Pause", kind: "secondary" },
    { action: "end", label: "End", kind: "danger", confirm: "End the exercise?" },
  ],
  paused: [
    { action: "resume", label: "Resume" },
    { action: "end", label: "End", kind: "danger", confirm: "End the exercise?" },
  ],
  ended: [{ action: "start", label: "Run again" }],
  archived: [],
};

export function ExercisePage() {
  const { id = "" } = useParams();
  const exercise = useResource(() => Api.exercise(id), [id]);

  if (exercise.error) return <ErrorBox error={exercise.error} />;
  if (!exercise.data) return <Loading />;
  const x = exercise.data;

  // Absolute links: relative ones resolve against the splat route and keep "Live" highlighted.
  const base = `/exercises/${id}`;
  const act = async (action: LifecycleAction) => exercise.setData(await Api.lifecycle(id, action));

  return (
    <div className="stack">
      <div className="row wrap">
        <h1>{x.name}</h1>
        <span className={`state state-${x.state}`}>{x.state}</span>
        <span className="spacer" />
        {ACTIONS[x.state]?.map((a) => (
          <AsyncButton key={a.action} kind={a.kind} confirm={a.confirm} onClick={() => act(a.action)}>
            {a.label}
          </AsyncButton>
        ))}
        {x.state !== "draft" && (
          <AsyncButton kind="secondary" confirm="Reset to draft? This ends the run and releases every override." onClick={() => act("reset")}>
            Reset
          </AsyncButton>
        )}
      </div>
      <nav className="tabs">
        <NavLink end to={base}>
          Live
        </NavLink>
        <NavLink to={`${base}/criteria`}>Criteria</NavLink>
        <NavLink to={`${base}/devices`}>Devices</NavLink>
        <NavLink to={`${base}/settings`}>Settings</NavLink>
        <NavLink to={`${base}/events`}>Event log</NavLink>
      </nav>
      <Routes>
        <Route index element={<LiveTab exercise={x} onExerciseChanged={exercise.reload} onExerciseSaved={exercise.setData} />} />
        <Route path="criteria" element={<CriteriaTab exercise={x} onSaved={exercise.setData} />} />
        <Route path="devices" element={<DevicesTab exercise={x} />} />
        <Route path="settings" element={<SettingsTab exercise={x} onSaved={exercise.setData} />} />
        <Route path="events" element={<EventsTab exercise={x} />} />
      </Routes>
    </div>
  );
}
