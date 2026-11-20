# Wave site

Read the repository's `AGENTS.md` and `docs/root.md`.

Static HTML with native ES modules and local CSS; no framework, npm install,
CDN, analytics, runtime API or server. Directory indexes and **relative URLs**
work under the GitHub Pages `/wave/` prefix. Keep controls usable without a
mouse.

- `index.html`: the installer, at the site root: Introduction, Get ready (adb
  identity), Back up (EDL read twice, saved as one `.wavebackup` file and read
  back), Root (patch on the phone with Magisk 24.0 over adb, Magisk and Wave
  install, EDL write and full readback, root check), Finish (open Wave).
  `src/root/rooting.mjs`. Each step's body is a checklist of what runs; a
  problem is one line (`common.mjs` `problem` and the throw sites hold the
  approved wording), with raw detail in the Log.
- `recovery/index.html`: restore only. Choosing a backup starts it: check the
  file, EDL over adb or by hand (guided), match the eMMC disk GUID, write the
  stock image back and read it back. `src/root/recovery.mjs`.
- `src/root/backup-file.mjs`: the `.wavebackup` format, a stored ZIP of
  `manifest.json` (format 2, with the GPT disk GUID) and `boot.img`. Old
  `.img` + `.json` pairs (format 1) still restore.
- `assets/`: `style.css` (both pages), the Wave launcher icon
  (`wave-icon.svg`, from `app/app/src/main/res/drawable/ic_launcher.xml`) and
  the bundled Cascadia Code font.
- `src/protocols/`, `src/root/`: the device code. Rules:
  - **Only `src/protocols/firehose-write.mjs` sends a write** (`program`), and
    only to `boot`, found by name in a GPT that must be the XP3800's. It writes
    a full 32 MiB image or a shorter one that `checkPatchedBoot` accepts (signed
    AVB 1.0, same certificate and header as the stock image it is written over),
    requires the partition to equal that stock image first, and reads the whole
    partition back. `firehose.mjs` (the read-only reader and shared framing)
    never sends `program`, `erase` or `patch`; tests check both.
  - Never write before the backup is read twice, checked as stock, saved with
    the File System Access API, and the saved file read back and hash-matched.
    Never write an image that did not come from patching that phone's own
    backup in the same flow.
  - No boot image or phone data is ever bundled. `build.py` always stages the
    pinned Sonim programmer from `assets/` into `files/`. CI supplies Magisk
    24.0 and Wave (`--magisk-apk` and `--wave-apk`). All three are served from
    the site with `files.json`, verified by `check_pages.py`; see `README.md`.
    The programmer and Magisk are pinned by SHA-256 in `build.py` and again in
    the pages. Users never choose installer prerequisites.
  - Nothing grants Wave root (decision 16), and the site never asks Magisk for
    root: Recovery needs only adb's `reboot edl`.
  - The flow's stage is in `localStorage` (`wave-rooting-v2`) and the two
    images in IndexedDB (`wave-rooting`) so a reload resumes; both are cleared
    at the end or on Start over. The adb key is kept in IndexedDB `wave-adb`,
    so "Always allow" lasts.
  Details and hardware evidence are in `docs/root.md`.
- `mockups/`: the app screen designer's area, not part of the site.
- `scripts/`, `tests/`: packaging and checks. CI stages only the files listed in
  `build.py`; never upload a worktree, phone data or local secrets.

Build with `python3 site/scripts/build.py NEW_OUTPUT_DIRECTORY/wave`. Preview
with `python3 -m http.server --directory NEW_OUTPUT_DIRECTORY 8000`.
Tests run with `node --test site/tests/*.test.mjs site/src/protocols/tests/*.test.mjs`
(Node 22; pass the files, since a bare directory is not run).
