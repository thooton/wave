# The work

One brief per piece. Each says which repo it is in, what it depends on, what
to read, what to deliver and how to tell it is done. Read `../design.md`
first whatever you pick up.

Repos: **W** = `thooton/wave` (this one), **P** = `thooton/wave_packages`
(`../sonim` today), **R** = `thooton/wave_repository`.

Paths in the briefs: `../design.md` and the like are relative to this
folder. `../sonim`, `../rphone` and `../*_argon` are the sibling folders of
this repo, under their names before the renames in 01 and 11.

## Working through the briefs

Use the design and the brief's completion checks to proceed without
personal sign-off. Record verification, design decisions, and any blocked
steps in the notes below. If a step needs credentials, permissions, or a
reserved test device that isn't available, continue independent work and
leave only the affected check pending. See `../../AGENTS.md` for device
rules.

| # | Brief | Repo | Needs | Done |
| :- | :--- | :-- | :--- | :-: |
| 00 | [Mockups of the app's screens](00-mockups.md) | W | | [x] |
| 01 | [Create and rename the repos](01-repos.md) | all | | [x] |
| 02 | [wavesrc: lint and publish](02-wavesrc.md) | W | | [x] |
| 03 | [The git client](03-git-client.md) | W | 04 | [x] |
| 04 | [App foundation from the Updater](04-app-foundation.md) | W | | [x] |
| 05 | [Sources, index and search](05-sources-index.md) | W | 03, 04 | [x] |
| 06 | [Root and the install engine](06-root-install.md) | W | 04 | [x] |
| 07 | [The app's screens](07-app-screens.md) | W | 00, 05, 06 | [ ] |
| 08 | [The boot watchdog](08-boot-watchdog.md) | W | 06 | [x] |
| 09 | [The jailbreak page](09-site-jailbreak.md) | W | | [ ] |
| 10 | [Landing page and setup-code generator](10-site-pages.md) | W | 14 for the generator | [ ] |
| 11 | [Import the mods into wave_packages](11-mods-import.md) | P | 01 | [x] |
| 12 | [Rename Argon to Wave in the mods](12-mods-rename.md) | P | 11 | [x] |
| 13 | [Releases and publishing from wave_packages CI](13-packages-ci.md) | P | 01, 02 | [x] |
| 14 | [Setup codes: apps without a baked login](14-app-setup-codes.md) | P | | [ ] |
| 15 | [The default source](15-default-source.md) | R | 01, 02 | [x] |
| 16 | [Source and Tablet in their own repo and source](16-private-source.md) | new | 13's CI pattern, 14's shared setup | [ ] |
| 17 | [First phone, end to end](17-first-phone.md) | all | 07, 08, 09, 12, 13, 15; 14 and 16 for migration | [ ] |

## What can run at the same time

```
start now:   00  01  02  04  09  14(spike first)
then:        03  06        (after 04)      11 -> 12   13 15  (after 01, 02)
then:        05  08
then:        07            (after 00, 05, 06 pass their checks)
last:        10  16  17
```

Four agents can work without touching the same files: one on the app's core
(03, 05, 06, 08), one on the site (09, 10), one on `wave_packages` (11, 12,
13), one on the app reworks (14). 00 and 07 belong to whoever does the UI.

## Work notes

(Record completion evidence, decisions, and blocked checks here.)

- **Device UI QA:** `tools/qa/` (`7a8901c`) provides serial-locked, d-pad-only scenarios, per-step screenshots, and an HTML report. Seven host tests pass. `python3 tools/qa/run.py --dry-run` passed on 8516067e with Wave 0.1.0 (code 1): offline Updates → MENU (Check Again) → BACK, 5.36% cropped-pixel change on both transitions, and no attributed crash or ANR. The temporary install was removed and the lock released. Local report: `.oak/tmp/wave-qa/foundation-smoke/index.html`. The 07 tab/source/package/queue scenarios await a compatible APK and fixture source.

- **16 (local prep verified, cutover pending):** `../wave_private_packages` (`2262590`) preserves 38 filtered app commits and uses a verified `common/` snapshot. History scan: 391 distinct imported blobs, no credential-pattern candidates; owner review is still required. Source 1.4.7 and Tablet 0.11.4 passed 290 Java host checks in total, built with the shared signer, passed the credential-free APK admission checks, and published/linted with `wavesrc` in a disposable source. Private CI syntax and its three asset URL tests pass. The private host, authenticated Wave binary downloads and migration-device updates are not verified. Leave both apps in P's legacy channel; see the [owner cutover](01-owner-checklist.md#source-and-tablet-private-cutover).

- **15 (local complete, GitHub pending):** Default source committed locally in `../wave_repository` (`a3c0049`); lint and download-mode lint pass on the intentionally empty source. Published the real Wave 0.1.0 APK into a disposable copy at `applications/wave` and linted the result. The only public legacy release is mutable `latest`, so no package points at it and nothing with an unavailable immutable binary is advertised. Hosted branch protection, the first real W/P publications and `lint --download` against populated remote assets need the [owner cutover](01-owner-checklist.md); none were attempted.

- **02 done:** Standard-library `wavesrc`, both composite Actions, and the [publishing guide](../publishing.md). Example lint passes with the expected retired-module warning. All 18 tests pass, including publishing the real List APK (`com.list.xp3` 1.2.6) and retrying a concurrent update against a disposable local Git repository. Hosted Actions were not run.

- **10 (site implemented; phone import pending):** Landing page, USB platform notes and Pages build (`494d22d`, `590b889`), plus the setup-file generator (`bba23b8`). `node --test site/tests/*.test.mjs` and `python3 site/scripts/sync_setup.py ../sonim --check` pass. Chromium served the staged site under `/wave/`, downloaded files for all six apps without sending credential requests or leaving values in browser storage, and the Android core `Config.parse` accepted those six downloads. Setup files are the v1 transport; source QR creation awaits camera validation. Imports on a reserved XP3800 with the corresponding app builds are not verified. GitHub Pages must be enabled by the owner with GitHub Actions as the source; no remote deployment was attempted.

- **04 build is in** (commit 98aa658): `JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :app:core:selfTest :app:app:assembleRelease`
  (JDK 21 required; the machine default is 27, which Gradle 8.9 can't run). Modules `:app:core`
  (`app/core/src/main/java/com/wave/xp3/core/...`, tests `app/core/src/test/java/`, runner
  `com.wave.xp3.SelfTest` using `com.xp3.common.Check`), `:app:app`, `:common:core`, `:common:ui`.
  Needs `local.properties` with `sdk.dir=/opt/android-sdk` (gitignored). APK:
  `app/app/build/outputs/apk/release/app-release.apk`.

- **01 (local complete, remote blocked):** Verified final local origins in W/P/R, kept active worktrees in place. No authenticated GitHub changes or redirect check; see [owner cutover checklist](01-owner-checklist.md).
- **04 done** except CI: Wave 0.1.0 (code 1) builds, `:app:core:selfTest` 59 passed; installed by adb
  on 8516067e beside `com.updater.xp3`, showed the Updater's list (Installed: Authenticator, Books, ...)
  once Wi-Fi was on, then uninstalled and Wi-Fi turned back off. `common/` copied by
  `tools/sync-common.sh` from `../sonim` at ab210e6 (`common/UPSTREAM`). CI green is **pending**:
  `.github/workflows/build.yml` is written but nothing can be pushed from here.

- **06 (host complete, device pending):** W `bc7f21d`, `9c34668`, `f846c64`, `60f1abd` (Wave 0.6.0).
  `core/root` (scripts, framing, `ProcessShell`, pm/module/facts parsers) and `core/install`
  (`Plan` with deps/conflicts/replaces/downgrades/cycles/requires, resumable `Download` with
  size+SHA-256, `Verify`, `Receipts`, `Engine`); `:app` `RootShell`, `Phone`, `EngineService`,
  `StockApps` (no-root APKs through Installer). Root is opt-in: `su` opens only on a user action;
  the design's "pre-grant" was dropped (decision 16). 470 self-tests, including whole plans run
  end to end through a fake `su` on a pretend `/data/adb`, pass under bash and busybox ash
  (`WAVE_TEST_SH`). **Pending:** every on-phone check, since the test phone isn't rooted: run
  [app/ROOT-TESTING.md](../../app/ROOT-TESTING.md) section 06 once `adb shell su -c id` works.
- **08 (host complete, device pending):** watchdog scripts in
  `app/core/src/main/resources/com/wave/xp3/core/root/`, installed by the engine before the first
  module; tested through pretend boots (good, never-completing, restarted mid-boot, framework
  restarting, clean user restart, no forced-reboot loop). Wave's launch dialogs (`AfterBoot`):
  Remove / Try Again / Leave Off, and safe mode's "every mod is off". Test modules
  `app/root-tests/` (`wave_test_broken` breaks at most one boot, disarmed first).
  **Pending:** ROOT-TESTING.md section 08 on a rooted phone, including the XP3's safe-mode key timing.

- **11 (local complete):** See [import record](11-import-record.md). All ten dex SHA-256 digests match committed upstream baselines; five unsigned requests preserve stock resources and manifest. Verified launcher/Music link checks. Installable zips require genuine OEM signatures; no signing material is available.

- **03 done:** Git v2 self-tests replay a binary `git http-backend` exchange and use real plain, OFS/REF delta, and filtered packs. Live depth-1 fetch and unchanged refresh passed on GitHub (`octocat/Hello-World`), GitLab (`gitlab-org/gitlab-test`), Codeberg (`Codeberg/pages-server`), sourcehut (`~sircmpwn/aerc`), and the local example source (6 files). A 1,000-package local CGI source fetched and parsed in 1.03 s with a 32 MiB heap cap (5 MiB in use after the last young GC). Regenerate fixtures or start the loopback source with `app/core/test-tools/git_fixture.py`.

- **00 done:** `site/mockups/app.html` draws all 46 screens at 240x320 with each frame's Left/Right, Up/Down, OK, Back and Menu; `site/mockups/shoot.sh` screenshots them to `site/mockups/shots/<id>.png` (headless Chromium) and every shot was checked at full size. Sizes were measured on 8516067e from the Updater (density 160, 24px status bar, 34px bar, hairline at y=58) and the text uses the phone's own Roboto files. Results, the d-pad path to every action, and notes for 07 are in `site/mockups/CHECKLIST.md`; the design choices are in `../design.md` section 4 ("The look and the keys") and `../decisions.md` working default 14.

- **05 done:** Source preview and trust approval, transactional refresh, manifest validation, merged candidates, section views, search, compatibility and phone status live in `core.sources`. Standalone Git/source Java 8 tests passed (121); wired into `:app:core:selfTest`. `source_parity.py` agreed with `wavesrc lint` for all v1 fixtures, with the expected reader-only exception for unknown types. Search over 3,000 synthetic packages averaged 3.7 ms per query on the host. UI consumes the APIs in `app/SPEC.md`.

- **12 (local complete, device pending):** P commits `34d9eba`, `8a6d346`, `eb0a3bc`, `7279e37`. All three renamed builds and host suites pass from a fresh clone; tray rename and preference fallback have host regression tests. Launcher and Music hooks regenerate from stock tags; Messages' committed hooks renamed by script, and all three Messages APKs passed the targeted link check. No old-mod/device handover test without authorized signed APKs and a recoverable test phone.
- **13 (local implementation, remote validation pending):** P `34a553b`, `7279e37`. Workflow passes actionlint; release gate unit tests verify versions, same-version drift and nonempty generated credentials. Clean release smoke built and signature-checked 19 APKs (everything except Charge before its prerequisite was removed), generated the legacy manifest, and published Books plus a synthetic module fixture into a scratch source over HTTPS, passed `wavesrc lint --download`, and confirmed repeat publication left all manifest/icon bytes unchanged. Real GitHub test requires repo cutover, `v1`, source token and an authorized signed mod. Charge's token prerequisite was removed in P `906d109`; the full 20-APK check is running against that revision. `latest` remains; AppDrawer stays for the programmable key.

- **09 (partial; original done-when remains pending):** Device guide, adb
  transport, grounded protocol replays, Rescue for already-rooted devices,
  and local backup verification in `40caab3`; public staging in `7bee2da`.
  Two independent EDL reads of the test phone's stock `boot` matched SHA-256
  `489269c42090f3505394cd062cd9d95eed981bbdb837d491faa7803f65979455`.
  Both copies and `SHA256SUMS` are in the owner's backup directory outside Git.
  The same JS adb protocol passed auth, `shell_v2`, and push/pull over libusb
  with an existing authorized key. No partition writes or automatic root
  grants; the phone booted stock Android again. Browser rooting, boot writes,
  and byte-for-byte stock restoration have not been performed. See
  [verification and platform limits](../jailbreak.md#verification).
