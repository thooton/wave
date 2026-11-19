# 01: Create and rename the repos

**Repos** all. **Needs** Sam (GitHub admin actions). **Blocks** 11, 13, 15.

## Goal
The three repositories exist under their final names, locally and on GitHub.

## Do
1. Rename `thooton/sonim` to `thooton/wave_packages` on GitHub (history and
   releases come along; GitHub redirects the old URL). Rename the local
   folder `../sonim` to `../wave_packages` and fix its remote.
2. Create `thooton/wave` and push this folder to it. Rename the local folder
   `sonim_wave` to `wave` if Sam wants (`../decisions.md`, open 6). Enable
   GitHub Pages for it (source: the Actions workflow that 09 adds).
3. Create `thooton/wave_repository`, empty, public.
4. Give `wave_packages` CI a way to push to `wave_repository`: a deploy key
   or fine-grained token, stored as the secret `WAVE_REPOSITORY_TOKEN`.

## Watch out
- **Phones on the old Updater** fetch
  `https://github.com/thooton/sonim/releases/download/latest/manifest.json`.
  GitHub's redirect keeps that working after the rename; confirm with `curl
  -I` before moving on, and don't create a new repo called `sonim`, which
  would break the redirect.
- Delta's nightly QA and anything else that names `thooton/sonim` (see
  `../sonim/AGENTS.md` and thooton/delta) needs the new name.
- Search every repo for the old paths afterwards (`rg -n "thooton/sonim|\.\./sonim"`).

## Done when
`git remote -v` is right in all three folders, the old manifest URL still
answers, and `../decisions.md` open item 6 is closed.
