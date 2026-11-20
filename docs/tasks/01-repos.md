# 01: Create separate Wave repos

**Repos** all. **Needs** an authenticated GitHub session with the required
repository permissions for the remote steps. **Blocks** 11, 13, 15.

## Goal
The Wave repos exist under their final names, locally and on GitHub. The
legacy public `thooton/sonim` repo stays in place.

## Do
1. Preserve the unpublished package commits and worktree in `../wave_packages`
   and keep `../sonim` tracking `thooton/sonim`. Create a separate, empty
   `thooton/wave_packages`; do not rename or push Wave work to `sonim`.
2. Create `thooton/wave` and push this folder to it, or verify its remote if
   already created.
3. Create `thooton/wave_repository`, empty and public.
4. Give `wave_packages` CI a token scoped to `wave_repository`, stored as
   `WAVE_REPOSITORY_TOKEN`. Follow the [owner checklist](01-owner-checklist.md)
   before the first push to the package repo's `master`, which publishes releases.

## Watch out
- If remote access is missing, finish the independent local preparation and
  record hosted creation as pending.
- **Phones on the old Updater** fetch
  `https://github.com/thooton/sonim/releases/download/latest/manifest.json`.
  That release and Delta's existing Sonim nightly QA stay with `sonim`, not
  with Wave's new repos.

## Done when
Each Wave checkout has its own GitHub remote, `../sonim` tracks the legacy
public repo, and the old manifest URL still answers directly.
