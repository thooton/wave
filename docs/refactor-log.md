# Refactor log

What was changed for quality beyond the briefs' own done-when lines, by area,
with the commit and the reason. Drawn from the commit logs and
[work notes](tasks/README.md); figures are as reported by the agent who
measured them, and anything unmeasured says so. Commits are in W unless
marked **P** (`wave_packages`, locally `../sonim`), **R** (`wave_repository`)
or **private** (`wave_private_packages`). Read the diff with `git show <id>`
in the named repository.

## App startup (agent 1)

Measured on the XP3800 (8516067e) with `adb shell am start -W` TotalTime,
force-stop before each launch, one warm-up, then 8 launches, median.

| Build | Median | Range |
| --- | --- | --- |
| Wave 0.7.1 | 552 ms | 490 to 587 ms |
| Wave 0.7.2 (`1f86e4b`) | 440 ms | 433 to 457 ms |

- First frame drawn (timed build): about 390 ms to about 255 ms. Home showing
  the sources: about 390 to 500 ms to about 410 ms.
- Causes fixed in `1f86e4b`: three full redraws of Home before the first frame,
  a duplicate phone read on the first resume, icon tiles drawn on the main
  thread.
- For reference, the retired Updater takes 350 to 420 ms on the same phone.

## Build speed (agent 6)

- **P `72eb2b7`**: APK signature checks run in bounded parallel batches. For
  the same 18 APKs, `apksigner verify` took 31.417 s serially and 8.377 s with
  four workers.
- A clean 18-APK release build at P `de87d48` took 6m02s with signature checks
  and the legacy manifest included. Full clean builds are not a controlled
  before and after (the APK counts differed), so no other speed-up is claimed.

## Wave app: install engine, root, watchdog (agent 1)

Defects found by review and fixed in `e1defb2` (0.7.0), `8654e34` (0.7.4) and
`6f9877c` (0.7.5), nine in all:

- download resume was never reached;
- the install receipt was written after the slow compile and lost if the run
  died;
- Wave's own update was never recorded;
- a failed receipt write failed a step that had succeeded;
- a module list was misread from a crafted or unterminated `module.prop`;
- `pm` crash output was misread on Android 8.1;
- Try Again was undone at the next boot;
- the watchdog stopped on an unreadable list (script v2, `f56f1f6`);
- `StockApps` reported cancelled early.

A d-pad and mockup checklist audit found 11 mismatches, fixed across 0.7.4 and
0.7.5, then menus and Back behavior in `3b8d849` (0.7.6) and `b32c202` (0.7.3,
the alert starts on OK, never on Remove It). Root became opt-in (`60f1abd`,
`76c5a54`, decision 16): apps install through the stock Installer without
`su`, which is what the stock-phone pass ([first phone](first-phone.md)) ran.

## Wave app: data layer (agent 2)

- `2478c0d`: sources are stored, validated and indexed, with a review of
  refresh and add races, the generation switch, a corrupt `sources.json`,
  oversized manifests, decompression bombs, and receipts that disagree with
  the index. `3f6d0b7` fetches over Git protocol v2.
- `e6cc177`: source integers are checked exactly within the app parser's range
  (the same limits are in the schemas), so the app, lint and the schemas agree.
- Parity: `source_parity.py` agreed with `wavesrc lint` on all v1 fixtures
  (work note 05).

## Setup library and the six apps' backends (agent 7)

Three defects in `common/` and eight in the apps, found by review and fixed
with tests:

- `common/`: concurrent saves through a shared `.tmp` file (P `375e34e`);
  Authenticator kept account secrets after Sign out (P `60ccb2e`); setup files
  with secrets left in external storage (P `375e34e`).
- Apps: Charge let an old sign-in overwrite a newly imported login (P
  `1f76144`); Canvas sync was blocked by an unhelpful command center and
  Canvas sign-out let in-flight work write back (P `c563353`); Mail
  reported a 429 or 5xx as a refused login (P `f572f20`); Authenticator treated
  an unreadable vault as empty (P `6e852c4`); Flashcards overwrote an unreadable
  collection (P `35eeb03`); a key could leak into an error message through a
  URL (P `856603c`); late callbacks crashed a dead activity (P `dfaf314`).
- P `8911324`: setup buttons stay readable on the focus fill.
- P `de87d48`: Canvas typing is saved when the app goes to the background.
- Every app built on `common/` got a patch bump for these (P `375e34e`).
- Wave and the private repo took the shared fixes by `tools/sync-common.sh`
  (see Tooling).

## Mods and CI (agent 6)

- P `9dcc12f`: Charge's duplicated HTTP code replaced with `common` Http.
- P `bf1bfc5`: duplicated `Ui` helpers removed from four apps.
- P `abbe9d1`, `34a553b`, `7279e37`: one mod build path that requires
  authorized signatures, immutable package releases next to the legacy channel,
  and CI tests for the settings handover and release immutability.
- P `d220a52`, `ba69efc`, `1bb151b`: renamed hook targets are verified in
  release builds, signing requests keep non-signature metadata, and signature
  failures are reported directly. P `f474fb5`, `d9deb86`, `f58920e` separate
  OEM signing keys and historical mod tests from current device checks.
- P `93cd68c`: legacy Installer and Updater retired without removing their
  source, verified by an 18-APK build with no retired manifest entries.

## Sources and publishing tooling (agent 4)

- `6cc55fd`, `e6cc177`: `wavesrc` lint and publish in standard-library Python,
  with strict JSON (duplicate keys, NaN and lone surrogates rejected),
  schema checks without `jsonschema`, PNG and size checks, and a publish that
  retries a concurrent push on a new tip. 18 tests then, 25 now.
- `d5cbc2d`: `wavesrc verify` proves every download is HTTPS, matches its
  size and SHA-256, and is not a moving `latest`-style URL, including through
  redirects. A pass proves the bytes match now, not that the host cannot
  replace them later.
- R `a3c0049` and `c0b4b82`: the default source, built locally and linted;
  nothing is advertised whose immutable binary does not exist.
- private `2262590`, `bd56fd0` and `5980a4e`: Source and Tablet moved to their
  own repo with filtered history (38 commits), no baked login, credential-free
  CI, and a scan of 391 imported blobs that found no candidates. The gap
  remains: Wave's downloader has no private-asset authentication.
- `10c3e27`: the site's setup snapshot records the P commit it came from in
  `site/setup/UPSTREAM`; `sync_setup.py --check` regenerates from that commit
  and fails on any difference, with 7 tests and a Pages CI step. Found by the
  clean-clone check below, where the snapshot lagged P's Canvas descriptor.
- `6391511`: `tools/sync-common.sh --check` compares the copy with the
  recorded commit without modifying it.

## Site (agent 5)

- `e49775d`, `dc589c7`: the staged bundle is about 99 kB raw and 45 kB gzip,
  the landing page 27 kB with no JavaScript, the Home screenshot 22.2 kB to
  11.5 kB as pixel-identical lossless WebP, and the allowlist is narrower. A
  keyboard-only pass at 240x320 covered the skip links, setup form and
  arrow-only access.
- `590b889`: internal links are validated before deployment. `5719017`
  keeps EDL and ADB file operations out of the Pages bundle. `dab674c`,
  `dc589c7`, `5093ffa`: target versus shipped scope, and the guide says
  root is optional and Wave cannot create a backup or grant root.
- `f01ff68`, `9554921`: the read-only boot backup page is off by default.

## Device QA (agents 5 and 4)

- `7a8901c`, `e3e0405`, `fb30169`, `7ae0171`: serial-locked, d-pad-only
  scenarios with per-step screenshots; the phone lock is mandatory; key
  dispatch is guarded after the hierarchy check; health logs stream.
- `5b0cbde`, `f452bd2`: debug fixtures let scenarios walk screens and Search.
- `11d4933`: first stock-phone pass, recorded in [first phone](first-phone.md).
  Input quirk found there: `adb shell input text` mangles digits on this
  handset.

## Docs repairs

- `30f4688`: README now has the build and test commands and prerequisites;
  `AGENTS.md`, `app/AGENTS.md`, `site/README.md` and `tools/README.md` no
  longer describe the phone as rooted, the Updater screen, a `latest` release
  or empty tools; sibling repository names are correct.
- `b9fb66e`: contradictions between the design, decisions and task docs fixed
  (root is opt-in; folder names; the Delta apps after task 14).
- `93155ae`: the owner checklist is an ordered go-live procedure, each step
  with its command or click, its check, and what to do if it fails.
- `b63d8c8`: [status](status.md) lists what works, how it was verified, and
  what is still unverified.
- `e44d897`: the task index marks brief 01 local-complete.
- `4548eb6`, `2e8655a`, `5d4c5f4`: mod history, signing, rename and link
  verification boundaries recorded.

## Clean-clone check

A fresh clone of W, P, R and the private repo, with only `local.properties`
added, built and tested: Wave's `:app:core:selfTest` and `assembleRelease`
(about 3.5 min), P's Books and List and the Music mod, the site tests and
staging, and `wavesrc lint` and `verify` on the default, private and example
sources. Gaps found were documentation (fixed in `30f4688`), a stale site
snapshot (`10c3e27`) and a private `common/` three commits behind P
(private `bd56fd0`). Left to decide: W's own `common/` is still at P
`ab210e6` while P is further ahead, and syncing it means a Wave version bump.

## Not measured or not verified

Hosted CI and Pages runs, a rooted-phone run (mods, watchdog, Rescue and
Restore), legacy migration, the public default source, authenticated private
asset downloads, and any build speed other than the signature stage above.
