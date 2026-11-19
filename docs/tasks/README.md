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
| 01 | [Create and rename the repos](01-repos.md) | all | | local |
| 02 | [wavesrc: lint and publish](02-wavesrc.md) | W | | [x] |
| 03 | [The git client](03-git-client.md) | W | 04 | [x] |
| 04 | [App foundation from the Updater](04-app-foundation.md) | W | | [x] |
| 05 | [Sources, index and search](05-sources-index.md) | W | 03, 04 | [x] |
| 06 | [Root and the install engine](06-root-install.md) | W | 04 | [x] |
| 07 | [The app's screens](07-app-screens.md) | W | 00, 05, 06 | [x] |
| 08 | [The boot watchdog](08-boot-watchdog.md) | W | 06 | [x] |
| 09 | [The jailbreak page](09-site-jailbreak.md) | W | | [ ] |
| 10 | [Landing page and setup-code generator](10-site-pages.md) | W | 14 for the generator | [ ] |
| 11 | [Import the mods into wave_packages](11-mods-import.md) | P | 01 | [x] |
| 12 | [Rename Argon to Wave in the mods](12-mods-rename.md) | P | 11 | [x] |
| 13 | [Releases and publishing from wave_packages CI](13-packages-ci.md) | P | 01, 02 | [x] |
| 14 | [Setup codes: apps without a baked login](14-app-setup-codes.md) | P | | [x] |
| 15 | [The default source](15-default-source.md) | R | 01, 02 | [x] |
| 16 | [Source and Tablet in their own repo and source](16-private-source.md) | new | 13's CI pattern, 14's shared setup | [ ] |
| 17 | [First phone, end to end](17-first-phone.md) | all | 07, 08, 09, 12, 13, 15; 14 and 16 for migration | [ ] |

"local": everything that can be done here is done and checked; the GitHub
steps wait on the [owner checklist](01-owner-checklist.md).

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

- **Site quality pass:** `e49775d` stages 21 public assets (~99 kB raw, ~45 kB individually gzip-compressed); landing is ~27 kB and has no JavaScript. The Home preview is pixel-identical lossless WebP (11.5 kB, down from 22.2 kB). All six pages passed strict HTML5 parsing and the staged link/import/size checks; the site and USB replay suites pass. Chromium at 240×320 verified all skip links, keyboard-only setup including invalid-field focus and download, and arrow-only access to primary controls with native spatial navigation enabled. The guide tells visitors from Wave that it cannot create a backup, patch, flash, or grant root. Target vs shipped scope is explicit (`dab674c`). GitHub Wave links remain pending the owner cutover; device USB, hosted Pages and screen-reader hardware were not tested in this pass.

- **07 done (rooted paths pending):** `com.wave.xp3.app` (`641c311` … `c322082`, Wave 0.7.12, code 19): tabs Wave, Sources, Installed, Search; source, section, Details, Add a Source, Confirm, the queue, Root, watchdog and reboot dialogs, over agent 2's `WaveData`. `MainActivity` (the Updater list) is off the launcher. Debug builds take `--es fixture browse|reboot|errors|offline|uptodate|noroot` (and `--es query`) from `src/debug/.../FakeWaveData`, which changes nothing on the phone; see `app/SPEC.md`. Every mockup frame has a matching 0.7.12 screenshot from 8516067e in `site/mockups/phone/` (48 shots, `site/mockups/phone-frames.py`; d-pad keys and `input text` only), compared side by side with `shots/`; differences are listed in `site/mockups/CHECKLIST.md`. Agent 5's suite (above) reaches every action with the d-pad alone. Installing 0.7.6 release over 0.1.0 (04's Updater-derived build, same signer) kept working: the launcher resolves to `.app.WaveActivity`, no crash, Home/Sources/Installed drawn. A real app install from a real HTTPS source on stock went through Confirm and the queue (agent 1, Wave Demo, including a hash failure). Not checked, needs root: installing, removing or turning on a mod, Reboot Now actually rebooting, a real watchdog turn-off, the root opt-in (Check Again granting su). Lists are not recycled (one ScrollView per page); fine at today's sizes, worth revisiting past a few hundred packages.

- **Device UI QA (07 evidence):** `tools/qa/` (`bbcb698`; 10 host tests) drives the phone with d-pad keys only, takes a 240×320 capture per step, checks text through uiautomator, focus movement, screen change and logcat crash/ANR, and writes an HTML report. Scenarios name their debug fixture; `python3 tools/qa/suite.py` runs them all. On Wave 0.7.6 (code 13, debug fixtures) on 8516067e: tabs, source > section > package > Confirm plan > Back out, Search (`mus`) > details > Back, Installed (Update All, row menu), errors (watchdog alert, red source row), reboot dialog (never confirmed), offline Sources all passed with no Wave crash or ANR and the temporary install removed each time. Suite: `.oak/tmp/wave-qa/suite-076/index.html`. Earlier stock 0.1.0 and 0.3.0 passed offline launch > MENU > BACK. On 0.7.12 (code 19) the new default-answer fixes passed too: `Reboot Now?` starts on Later (OK closes it, no reboot toast) and `Remove XP3 Games?` starts on Cancel, and a second queue after a failed Mail run shows no stale "wasn't installed" alert (`.oak/tmp/wave-qa/suite-0712/index.html`). Not covered: Add a Source typing (agent 3's own walk), a real install, rooted behaviour.

- **17 (stock pass; rooted and migration checks pending):** Wave 0.6.0 opens on the real stock XP3800 and points to the guide for mods. An isolated test APK loaded the exact local default source over HTTPS and real Git v2, added a populated fixture source, browsed both sections, recognized the newer installed List, searched, refused a module for lack of root, and removed the fixture source. No package action was confirmed; the public default source, hosted release/update flow, rooted mod/watchdog/recovery run and legacy migration remain unverified. See [first phone evidence](../first-phone.md).

- **16 (local prep verified, cutover pending):** `../wave_private_packages` (`bd56fd0`) preserves 38 filtered app commits and uses a verified `common/` snapshot. History scan: 391 distinct imported blobs, no credential-pattern candidates; owner review is still required. Source 1.5.0 and Tablet 0.12.0 passed 305 Java host checks in total (78 common, 97, 130), built with the shared signer, passed the credential-free APK admission checks, and published/linted with `wavesrc` in a disposable source. Private CI syntax and its three asset URL tests pass. The private host, authenticated Wave binary downloads and migration-device updates are not verified. Leave both apps in P's legacy channel; see the [owner cutover](01-owner-checklist.md#source-and-tablet-private-cutover).

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
- **13 (local implementation, remote validation pending):** P `34a553b`, `7279e37`. Workflow passes actionlint; release gate unit tests verify versions, same-version drift and nonempty generated credentials. Clean release smoke built and signature-checked 19 APKs (everything except Charge before its prerequisite was removed), generated the legacy manifest, and published Books plus a synthetic module fixture into a scratch source over HTTPS, passed `wavesrc lint --download`, and confirmed repeat publication left all manifest/icon bytes unchanged. Real GitHub test requires repo cutover, `v1`, source token and an authorized signed mod. Charge's token prerequisite was removed in P `906d109`; the original full 20-APK check passed at that revision (6m28s Gradle, 8m11s complete). `latest` remains; AppDrawer stays for the programmable key. The follow-up retirement of Installer and Updater (`93cd68c`) was verified by an 18-APK build with no retired manifest entries.

- **09 (partial; original done-when remains pending):** Device guide, adb
  transport (native JS, no Tango: no bundler or transitive packages), Rescue for
  rooted phones and the local Restore verifier are public (`40caab3`, `7bee2da`).
  **Read-only browser backup over EDL** (Sahara, Firehose, pinned programmer,
  two reads compared, image plus manifest) is implemented and verified end to
  end in Chromium on the XP3800, but is **off by default**: build it with
  `python3 site/scripts/build.py --with-device-backup DIR` (site agent declined
  to stage an EDL transport). Evidence: two desktop reads and the browser's
  download all SHA-256 `489269c42090f3505394cd062cd9d95eed981bbdb837d491faa7803f65979455`,
  copies in `~/Documents/wave-phone-backups/8516067e/` (not in Git); 14 tests
  pass; the phone returned to stock Android each time. Not done: on-phone
  patching proof, boot write and byte-for-byte Restore over EDL (the owner
  approved rooting later, but only the read path was built), automatic root
  grants, native chooser and rooted Rescue checks, Windows and macOS. The phone
  is still unrooted, so root-dependent checks in other briefs stay pending.
  See [jailbreak.md](../jailbreak.md).