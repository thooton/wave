# Wave site

Read the repository's `AGENTS.md`, `docs/tasks/10-site-pages.md` and `docs/design.md`.

Static HTML with native ES modules and local CSS; no framework, npm install,
CDN, analytics, runtime API or server. Directory indexes and **relative URLs**
work under the GitHub Pages `/wave/` prefix. Keep controls usable without a
mouse. Distinguish illustrated screens, planned packages and unverified steps
from shipped/tested behavior.

- `index.html`, `assets/`: public landing, shared visual assets and styles.
  `assets/screens/*.webp` are real XP3800 screenshots (240x320, Wave 0.7.25 debug build with
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
- `root/`, `rescue/`, `restore/`, `backup/`, `rooting/`, `stock/`,
  `src/protocols/`, `src/root/`: owned by the device tools brief (09). All are
  in the default build. `rooting/` roots the phone (adb identity, EDL backup,
  patch on the phone with Magisk 24.0 over adb, EDL write and full readback,
  Magisk and Wave install); `stock/` writes the saved stock image back;
  `backup/` only reads. Rules for these:
  - **Only `src/protocols/firehose-write.mjs` sends a write** (`program`), and
    only to `boot`, found by name in a GPT that must be the XP3800's. It writes
    a full 32 MiB image or a shorter one that `checkPatchedBoot` accepts (signed
    AVB 1.0, same certificate and header as the stock image it is written over),
    requires the partition to equal that stock image first, and reads the whole
    partition back. `firehose.mjs` (the read-only reader and shared framing) and
    the backup page never send `program`, `erase` or `patch`; tests check both.
  - Never write before the backup is read twice, checked, saved, and the saved
    file chosen again and hash-matched. Never write an image that did not come
    from patching that phone's own backup in the same flow.
  - No programmer binary, boot image or phone data is ever bundled. The
    Sonim programmer is chosen by the user as a local file and pinned by
    SHA-256. Magisk (pinned, official 24.0) and Wave may be served from the
    site's own origin with `build.py --magisk-apk/--wave-apk` (into
    `rooting/files/` with `files.json`, verified by `check_pages.py`);
    otherwise the user chooses them as files.
  - Nothing grants Wave root (decision 16). The page may ask Magisk for the
    adb shell's root, only on the user's click, and Magisk's prompt decides.
  - The flow's stage is in `localStorage` and the two images in IndexedDB so a
    reload resumes; both are cleared at the end or on Start over.
  Details and hardware evidence are in `docs/root.md`.
- `scripts/`, `tests/`: site packaging and checks. CI packages only explicitly
  reviewed public files; never upload a worktree, phone data or local secrets.

Build with `python3 site/scripts/build.py NEW_OUTPUT_DIRECTORY`. Preview
with `python3 -m http.server --directory NEW_OUTPUT_DIRECTORY 8000`.
Tests run with `node --test site/tests/*.test.mjs site/src/protocols/tests/*.test.mjs`
(Node 22; pass the files, since a bare directory is not run).
