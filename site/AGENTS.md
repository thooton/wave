# Wave site

Read the repository's `AGENTS.md`, `docs/tasks/10-site-pages.md` and `docs/design.md`.

Static HTML with native ES modules and local CSS; no framework, npm install,
CDN, analytics, runtime API or server. Directory indexes and **relative URLs**
work under the GitHub Pages `/wave/` prefix. Keep controls usable without a
mouse. Distinguish illustrated screens, planned packages and unverified steps
from shipped/tested behavior.

- `index.html`, `assets/`: public landing, shared visual assets and styles.
  `assets/wave-home.png` is a reviewed copy of `mockups/shots/wave-home.png`,
  an app design preview, not a hardware screenshot.
- `platforms/index.html`: USB compatibility and troubleshooting.
- `setup/`: local setup-file generator. Use `spec/setup-payload.md` and the
  canonical per-app descriptors from wave_packages. Regenerate the reviewed,
  checked-in snapshot with `python3 site/scripts/sync_setup.py ../sonim` when
  those descriptors change; use `--check` to detect drift. Production builds
  use the local snapshot and do not contact that repository. Never put credentials
  in URLs, logs, browser storage, network requests, sample files or tests.
- `mockups/`: app screen designer's area, not owned by the public site.
- `jailbreak/`, `rescue/`, `restore/`, `src/protocols/`, `src/jailbreak/`:
  reserved for the separate device tools brief. Not in the public bundle.
- `scripts/`, `tests/`: site packaging and checks. CI packages only explicitly
  reviewed public files; never upload a worktree, phone data or local secrets.

Build with `python3 site/scripts/build.py NEW_OUTPUT_DIRECTORY`. Preview
with `python3 -m http.server --directory NEW_OUTPUT_DIRECTORY 8000`.
Tests run with `node --test site/tests/*.test.mjs` (Node 22).
