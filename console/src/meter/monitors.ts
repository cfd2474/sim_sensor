import type { Channel, Profile, SensorProfile } from "../api/endpoints";

/** "Blackline G8 · Blackline Safety G8", or just the name when there's no model. */
export function monitorLabel(definition: SensorProfile): string {
  const model = definition.model?.trim();
  return model && model !== definition.name ? `${definition.name} · ${model}` : definition.name;
}

/**
 * Every gas of [monitors], each defined by the first monitor that has it (the exercise's
 * default monitor comes first). Mirrors ExerciseMonitors.combined on the server.
 */
export function combinedChannels(monitors: Profile[]): Channel[] {
  const byId = new Map<string, Channel>();
  monitors.forEach((m) => m.definition.channels.forEach((c) => byId.has(c.id) || byId.set(c.id, c)));
  return [...byId.values()];
}

/** The monitor a phone uses: its own, else the exercise default. */
export function monitorFor(monitorId: string | null | undefined, defaultId: string, monitors: Profile[]): Profile | undefined {
  return monitors.find((m) => m.id === (monitorId ?? defaultId)) ?? monitors.find((m) => m.id === defaultId);
}
