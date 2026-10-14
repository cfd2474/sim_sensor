import type { components } from "./schema";

/** Response and shared schemas, generated from docs/openapi.yaml (npm run gen:api). */
export type Schemas = components["schemas"];
export type ValidationError = Schemas["ValidationError"];

/** A non-2xx response from the API. */
export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
    readonly errors: ValidationError[] = [],
  ) {
    super(message);
  }

  /** The validation error for one field (and channel), if any. */
  fieldError(field: string, channelId: string | null = null): string | undefined {
    return this.errors.find((e) => e.field === field && (e.channelId ?? null) === channelId)?.message;
  }
}

let onUnauthorized: (() => void) | null = null;

/** Called when any request comes back 401 (session expired): the app returns to the login page. */
export function setUnauthorizedHandler(handler: (() => void) | null) {
  onUnauthorized = handler;
}

/** JSON request to the same-origin API. Throws [ApiError] for non-2xx answers. */
export async function api<T>(path: string, init: { method?: string; body?: unknown } = {}): Promise<T> {
  const hasBody = init.body !== undefined;
  const response = await fetch(path, {
    method: init.method ?? (hasBody ? "POST" : "GET"),
    headers: hasBody ? { "Content-Type": "application/json" } : undefined,
    body: hasBody ? JSON.stringify(init.body) : undefined,
    credentials: "same-origin",
  });
  if (response.status === 204) return undefined as T;
  const text = await response.text();
  const data = text ? safeParse(text) : undefined;
  if (!response.ok) {
    if (response.status === 401 && !path.startsWith("/api/auth/login")) onUnauthorized?.();
    const body = (data ?? {}) as Partial<Schemas["Error"]>;
    throw new ApiError(response.status, body.code ?? "error", body.message ?? response.statusText, body.errors ?? []);
  }
  return data as T;
}

function safeParse(text: string): unknown {
  try {
    return JSON.parse(text);
  } catch {
    return undefined;
  }
}

/** A user-facing message for any error. */
export function errorMessage(error: unknown): string {
  if (error instanceof ApiError) return error.message;
  if (error instanceof Error) return error.message;
  return String(error);
}
