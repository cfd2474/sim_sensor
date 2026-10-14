/** Server REST calls. Same origin in production; proxied by Vite in development. */

export interface Health {
  status: string;
  version: string;
}

export async function fetchHealth(signal?: AbortSignal): Promise<Health> {
  const response = await fetch("/health", { signal });
  if (!response.ok) throw new Error(`HTTP ${response.status}`);
  return (await response.json()) as Health;
}
