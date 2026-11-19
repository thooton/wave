# Wave site

Read the repository's `AGENTS.md`, `docs/tasks/10-site-pages.md` and `docs/design.md`.

Static HTML with native ES modules and local CSS; no framework, npm install,
CDN, analytics, runtime API or server. Directory indexes and **relative URLs**
work under the GitHub Pages `/wave/` prefix. Keep controls usable without a
mouse. Distinguish illustrated screens, planned packages and unverified steps
from shipped/tested behavior.

- `index.html`, `assets/`: public landing, shared visual assets and styles.
  `assets/screens/*.webp` are real XP3800 screenshots (240x320, Wave 0.7.12 debug build with
  sample packages) from `mockups/phone/`, made by `python3 site/scripts/screens.py` (256-colour
  palette, lossless WebP; the script fails if a screen changes too much, `--check` verifies the
  committed files). Say they are screenshots with sample data; never describe them as designs or
  show a screen the app does not have.
- `platforms/index.html`: USB compatibility and troubleshooting.
- `setup/`: local setup-file generator. Use `spec/setup-payload.md` and the
  canonical per-app descriptors from wave_packages. Regenerate the reviewed,
  checked-in snapshot with `python3 site/scripts/sync_setup.py ../sonim` when
  those descriptors change (it also updates `setup/UPSTREAM`, the recorded
  commit); use `--check` to detect drift. Production builds
  use the local snapshot and do not contact that repository. Never put credentials
  in URLs, logs, browser storage, network requests, sample files or tests.
- `mockups/`: app screen designer's area, not owned by the public site.
- `jailbreak/`, `rescue/`, `restore/`, `src/protocols/`, `src/jailbreak/`:
  owned by the separate device tools brief. The allowlist stages only the
  compatibility probe, Rescue, and the local stock-backup verifier and their
  dependencies. Do not include phone backups or loaders.
  **Opt-in read-only backup:** `python3 site/scripts/build.py --with-device-backup DIR`
  adds `backup/index.html`, `src/jailbreak/backup.mjs` and the Sahara, Firehose
  and GPT modules, and links the page from the guide. It is off by default, so
  the Pages workflow does not publish it; the owner enables it deliberately.
  It only reads (`configure` with `SkipWrite`, `getstorageinfo`, `read`, `power
  reset`), accepts only the hash-pinned Sonim programmer chosen by the user as a
  local file, and ships no binary. Never add a write, erase or patch path to
  those modules. `site/tests/backup.test.mjs` checks the flag. Details and
  hardware evidence are in `docs/jailbreak.md`.
- `scripts/`, `tests/`: site packaging and checks. CI packages only explicitly
  reviewed public files; never upload a worktree, phone data or local secrets.

Build with `python3 site/scripts/build.py NEW_OUTPUT_DIRECTORY`. Preview
with `python3 -m http.server --directory NEW_OUTPUT_DIRECTORY 8000`.
Tests run with `node --test site/tests/*.test.mjs site/src/protocols/tests/*.test.mjs`
(Node 22; pass the files, since a bare directory is not run).
