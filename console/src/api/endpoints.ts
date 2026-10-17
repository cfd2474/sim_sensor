import { api, type Schemas } from "./client";

export type Me = Schemas["Me"];
export type Instructor = Schemas["Instructor"];
export type Profile = Schemas["Profile"];
export type SensorProfile = Schemas["SensorProfile"];
export type Channel = Schemas["Channel"];
export type Exercise = Schemas["Exercise"];
export type Device = Schemas["Device"];
export type DeviceTarget = Schemas["DeviceTarget"];
export type EnrollmentToken = Schemas["EnrollmentToken"];
export type Event = Schemas["Event"];
export type ConsoleConfig = Schemas["ConsoleConfig"];
export type AppInfo = Schemas["AppInfo"];
export type GasSource = Schemas["GasSource"];
export type SourceShape = GasSource["shape"];
export type SourceTiming = NonNullable<GasSource["timing"]>;
export type LatLon = Schemas["LatLon"];
export type PreviewGrid = Schemas["PreviewGrid"];
export type SystemSettings = Schemas["SystemSettings"];
export type Beacon = Schemas["Beacon"];
export type ExerciseLocation = NonNullable<Schemas["Location"]>;
export type GeocodeResult = Schemas["GeocodeResult"];
export type UpdateInfo = Schemas["UpdateInfo"];
export type SmtpSettingsView = Schemas["SmtpSettingsView"];
export type SmtpSettingsInput = Schemas["SmtpSettingsInput"];
export type TokenInfo = Schemas["TokenInfo"];
export type Units = SystemSettings["units"];
export type MapBounds = { south: number; west: number; north: number; east: number };
export type DeviceGroup = { id: string; name: string };
export type CreatedToken = { token: string; link: string; info: EnrollmentToken };
export type OverrideMode = "FIXED" | "RAMP" | "OFFSET" | "FAULT";
export type LifecycleAction = "start" | "pause" | "resume" | "end" | "reset";
type Affected = { deviceIds: string[] };

/** Every REST call the console makes (docs/openapi.yaml). */
export const Api = {
  config: () => api<ConsoleConfig>("/api/config"),
  app: () => api<AppInfo>("/api/app"),

  login: (email: string, password: string) => api<Me>("/api/auth/login", { body: { email, password } }),
  logout: () => api<void>("/api/auth/logout", { method: "POST" }),
  me: () => api<Me>("/api/auth/me"),
  changePassword: (currentPassword: string, newPassword: string) =>
    api<void>("/api/auth/password", { body: { currentPassword, newPassword } }),

  instructors: () => api<Instructor[]>("/api/instructors"),
  createInstructor: (body: { email: string; name: string; password?: string; role: string; sendInvite?: boolean }) =>
    api<Instructor>("/api/instructors", { body }),
  sendResetEmail: (id: string) => api<void>(`/api/instructors/${id}/reset-email`, { method: "POST" }),

  authOptions: () => api<{ forgotPassword: boolean }>("/api/auth/options"),
  forgotPassword: (email: string) => api<unknown>("/api/auth/forgot", { body: { email } }),
  tokenInfo: (token: string) => api<TokenInfo>(`/api/auth/reset?token=${encodeURIComponent(token)}`),
  resetPassword: (token: string, password: string) => api<void>("/api/auth/reset", { body: { token, password } }),

  smtpSettings: () => api<SmtpSettingsView>("/api/settings/smtp"),
  saveSmtpSettings: (s: SmtpSettingsInput) => api<SmtpSettingsView>("/api/settings/smtp", { method: "PUT", body: s }),
  testSmtp: () => api<{ sentTo: string }>("/api/settings/smtp/test", { method: "POST" }),
  updateInstructor: (id: string, body: { name?: string; role?: string; disabled?: boolean; password?: string }) =>
    api<Instructor>(`/api/instructors/${id}`, { method: "PATCH", body }),

  beacons: () => api<Beacon[]>("/api/beacons"),
  createBeacon: (b: Beacon) => api<Beacon>("/api/beacons", { body: b }),
  updateBeacon: (b: Beacon) => api<Beacon>(`/api/beacons/${b.id}`, { method: "PUT", body: b }),
  deleteBeacon: (id: string) => api<void>(`/api/beacons/${id}`, { method: "DELETE" }),
  /** The exercise's beacons: its phones listen only for these. */
  exerciseBeacons: (exerciseId: string) => api<Beacon[]>(`/api/exercises/${exerciseId}/beacons`),
  addExerciseBeacon: (exerciseId: string, beaconId: string) =>
    api<Beacon[]>(`/api/exercises/${exerciseId}/beacons`, { body: { beaconId } }),
  removeExerciseBeacon: (exerciseId: string, beaconId: string) =>
    api<Beacon[]>(`/api/exercises/${exerciseId}/beacons/${beaconId}`, { method: "DELETE" }),

  /** The sensor types a monitor can have, with their default settings. */
  sensors: () => api<Channel[]>("/api/sensors"),
  profiles: () => api<Profile[]>("/api/profiles"),
  createProfile: (definition: SensorProfile) => api<Profile>("/api/profiles", { body: definition }),
  updateProfile: (id: string, definition: SensorProfile) => api<Profile>(`/api/profiles/${id}`, { method: "PUT", body: definition }),
  deleteProfile: (id: string) => api<void>(`/api/profiles/${id}`, { method: "DELETE" }),

  exercises: () => api<Exercise[]>("/api/exercises"),
  exercise: (id: string) => api<Exercise>(`/api/exercises/${id}`),
  createExercise: (name: string, profileId: string, location?: ExerciseLocation | null) =>
    api<Exercise>("/api/exercises", { body: { name, profileId, location: location ?? undefined } }),
  /** Places for an address (through the server: cached, the service's usage policy kept). */
  /** Settings → Server update (admins). */
  updateInfo: () => api<UpdateInfo>("/api/system/update"),
  startUpdate: (tag: string) => api<UpdateInfo>("/api/system/update", { body: { tag } }),
  geocode: (q: string) => api<GeocodeResult[]>(`/api/geocode?q=${encodeURIComponent(q)}`),
  updateExercise: (
    id: string,
    body: {
      name?: string;
      profileId?: string;
      o2Displacement?: boolean;
      windDirDeg?: number;
      windSpeedMps?: number;
      location?: ExerciseLocation;
    },
  ) => api<Exercise>(`/api/exercises/${id}`, { method: "PATCH", body }),
  deleteExercise: (id: string) => api<void>(`/api/exercises/${id}`, { method: "DELETE" }),
  setExerciseInstructors: (id: string, instructorIds: string[]) =>
    api<Exercise>(`/api/exercises/${id}/instructors`, { method: "PUT", body: { instructorIds } }),
  /** Monitors the exercise's phones use: the default first. */
  exerciseMonitors: (id: string) => api<Profile[]>(`/api/exercises/${id}/monitors`),
  /** Sets the monitor of phones in the exercise; null = the exercise default. */
  setDeviceMonitor: (exerciseId: string, deviceIds: string[], monitorId: string | null) =>
    api<Device[]>(`/api/exercises/${exerciseId}/devices/monitor`, { method: "PUT", body: { deviceIds, monitorId } }),
  lifecycle: (id: string, action: LifecycleAction) => api<Exercise>(`/api/exercises/${id}/${action}`, { method: "POST" }),
  events: (id: string, before?: number) => api<Event[]>(`/api/exercises/${id}/events?limit=100${before ? `&before=${before}` : ""}`),
  exerciseDevices: (id: string) => api<Device[]>(`/api/exercises/${id}/devices`),

  setOverride: (
    exerciseId: string,
    body: { target: DeviceTarget; channelId: string | null; mode: OverrideMode; value?: number; rampSeconds?: number },
  ) => api<Affected>(`/api/exercises/${exerciseId}/overrides`, { body }),
  releaseOverrides: (exerciseId: string, target: DeviceTarget, channelId: string | null) =>
    api<Affected>(`/api/exercises/${exerciseId}/overrides/release`, { body: { target, channelId } }),
  command: (exerciseId: string, body: { target: DeviceTarget; kind: string; text?: string; value?: number }) =>
    api<Affected>(`/api/exercises/${exerciseId}/commands`, { body }),

  sources: (exerciseId: string) => api<GasSource[]>(`/api/exercises/${exerciseId}/sources`),
  createSource: (exerciseId: string, source: GasSource) => api<GasSource>(`/api/exercises/${exerciseId}/sources`, { body: source }),
  updateSource: (exerciseId: string, source: GasSource) =>
    api<GasSource>(`/api/exercises/${exerciseId}/sources/${source.id}`, { method: "PUT", body: source }),
  deleteSource: (exerciseId: string, sourceId: string) => api<void>(`/api/exercises/${exerciseId}/sources/${sourceId}`, { method: "DELETE" }),
  activateSource: (exerciseId: string, sourceId: string) =>
    api<GasSource>(`/api/exercises/${exerciseId}/sources/${sourceId}/activate`, { method: "POST" }),
  preview: (exerciseId: string, gas: string, b: MapBounds, cells: number) =>
    api<PreviewGrid>(
      `/api/exercises/${exerciseId}/preview?gas=${encodeURIComponent(gas)}&south=${b.south}&west=${b.west}&north=${b.north}&east=${b.east}&cells=${cells}`,
    ),

  settings: () => api<SystemSettings>("/api/settings"),
  updateSettings: (settings: SystemSettings) => api<SystemSettings>("/api/settings", { method: "PUT", body: settings }),

  devices: () => api<Device[]>("/api/devices"),
  updateDevice: (id: string, body: { name?: string; groupId?: string }) => api<Device>(`/api/devices/${id}`, { method: "PATCH", body }),
  assignDevice: (id: string, exerciseId: string | null) => api<Device>(`/api/devices/${id}/exercise`, { method: "PUT", body: { exerciseId } }),
  revokeDevice: (id: string) => api<Device>(`/api/devices/${id}/revoke`, { method: "POST" }),

  groups: () => api<DeviceGroup[]>("/api/device-groups"),
  createGroup: (name: string) => api<DeviceGroup>("/api/device-groups", { body: { name } }),
  deleteGroup: (id: string) => api<void>(`/api/device-groups/${id}`, { method: "DELETE" }),

  enrollmentTokens: () => api<EnrollmentToken[]>("/api/enrollment-tokens"),
  createEnrollmentToken: (body: { kind: "single" | "fleet"; label?: string; ttlMinutes?: number }) =>
    api<CreatedToken>("/api/enrollment-tokens", { body }),
  revokeEnrollmentToken: (id: string) => api<void>(`/api/enrollment-tokens/${id}`, { method: "DELETE" }),
  /** MDM fleet token: when it stops enrolling new phones (null = never). Enrolled phones keep working. */
  setTokenRetirement: (id: string, expiresAt: string | null) =>
    api<EnrollmentToken>(`/api/enrollment-tokens/${id}`, { method: "PATCH", body: { expiresAt } }),
};
