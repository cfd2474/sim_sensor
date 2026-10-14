# Project Workflow Rules

## 1. Project State File
- Maintain a running state file (`PROJECT_STATE.md`) in the repo root.
- Update it after every completed step with:
  - What's been completed
  - What's in progress
  - Constraints
  - Decisions made (with rationale)
- Read this file before starting any new step. Do not proceed without reading it first.

## 2. Task Decomposition
- Before executing complex work, break it into discrete chunks of 5-7 steps.
- Write the chunk plan into the state file before starting execution.
- Do not execute steps that aren't part of a defined chunk.

## 3. Validation Checkpoints
- After completing each chunk, stop.
- Provide a summary: what was done, what changed, any open questions/risks.
- Wait for explicit approval before starting the next chunk.

## 4. Troubleshooting vs. Assumptions
- When debugging, either troubleshoot (gather evidence, test hypotheses) or state assumptions explicitly and proceed - never mix the two silently.
- If troubleshooting is incomplete, do not make assumptions to fill the gap without flagging it.

## 5. SOLID Programming Principles
- Always make sure code falls under the SOLID programming design principles.

## 6. Android Platform Reference
- `docs/ANDROID_PLATFORM_REFERENCE.md` records the Android contracts this project
  depends on, each traceable to an official source, plus what has been verified on
  our own hardware.
- **Read it before writing or changing any code that touches permissions, foreground
  services, location, Bluetooth LE scanning, kiosk / lock task mode, app installation,
  or background execution limits.** Check the change against it, and do not rely on
  recollection of how Android behaves.
- Platform contracts often fail vaguely or silently (a permission quietly denied, a
  scan throttled, a service killed). There is usually nothing to infer from the
  symptom, so consult the spec before reasoning from behaviour.
- Update the file whenever hardware teaches something new, marking it ✅ verified.
  Where an official source contradicts an observation, keep both and note the
  disagreement rather than deleting the observation.

## 7. Environment Traps
- Read "Operational notes" in `PROJECT_STATE.md` before debugging anything that
  looks impossible — a correct change appearing to have no effect is almost always
  a stale build, container, or cached artifact, not a bug in the code.
- Record every such trap in "Operational notes" once found (for example, which
  Docker Compose command actually rebuilds the server image, or which config changes
  need a proxy restart).

## 8. Remote Server
- `docs/REMOTE_SERVER.md` will record how to reach the host: access method,
  the commands that work from this machine, and the deploy, APK-publish and
  migration procedures.
- **Read it before running anything against the host.** Only record commands
  there that have been run successfully, including any non-obvious flags.
- Reading the host (logs, container status, rendered pages) is fine. **Changing it
  (deploys, rebuilds, restarts, data changes) requires the operator's explicit OK**
  until a standing permission is recorded in that file. Any standing OK never
  extends to destroying data or to other hosts.

## 9. Releases: bump the version on every product-changing push

### Bump the version
`VERSION` at the repo root is the product version. Move it forward in the same
commit as the change:

- **patch** (`1.0.0` → `1.0.1`) by default — fixes, refactors, docs, tests.
- **minor** (`1.0.0` → `1.1.0`) for a new capability an operator would notice.
- **major** only when the operator says so.

Then tag that commit:

```
git tag -a v1.0.1 -m "v1.0.1 — <what changed>"
git rev-parse 'v1.0.1^{}'      # the COMMIT the tag points at
```

⚠️ **`git rev-parse v1.0.1` returns the annotated tag object, not the commit.**
Use the `^{}` suffix whenever a commit SHA is needed.

⚠️ **`VERSION` must equal the newest tag.** A test enforces it. If the test fails,
the fix is to bump `VERSION`, never to weaken the test.

### Things that follow every release
- **Release notes** (`docs/RELEASE_NOTES.md`). One `## vX.Y.Z — YYYY-MM-DD`
  section per release, newest first, in the same commit as the bump — written for
  the operator, no internal task numbers. A release with nothing an operator would
  see still gets a one-line section saying so. A test fails when the newest
  section is not `VERSION`.
- **Android `versionCode`**, whenever `android/` changed. ⚠️ Always raise it — a
  rebuilt APK with a repeated `versionCode` will not install as an update.
- **`PROJECT_STATE.md`**, per section 1.

### Private-only changes
If a change does not affect anything shipped (APK, server image, console), leave
`VERSION` alone rather than publishing a release whose only content is its own
version number.
