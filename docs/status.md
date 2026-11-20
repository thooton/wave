# Status: what works today

As of 2026-11-20. Wave is at 0.7.25 (code 32; `app/app/build.gradle` has the current one). Nothing has been pushed or
published: every GitHub step (repo renames, CI runs, Pages, releases, the
hosted default source) waits on the [owner cutover](tasks/01-owner-checklist.md).
So "verified" below means on this machine, and on the one reserved phone
(XP3800 `8516067e`, Android 8.1, rooted with Magisk 24.0 since 2026-11-19). The briefs'
progress and the evidence behind each line are in the
[work notes](tasks/README.md#work-notes).

The short version: the app browses, searches and plans on a real phone, and
has installed, updated and removed an app from a real (local) source without
root ([first-install.md](first-install.md)). Through root on the rooted
phone, it has installed apps and mods and updated itself, and the boot
watchdog has turned off a mod that hung the boot
([ROOT-TESTING.md](../app/ROOT-TESTING.md)). The phone was rooted from its own
verified boot backup ([jailbreak.md](jailbreak.md#rooted-test-phone-8516067e)),
and the built Rescue page, driven from a browser over WebUSB, disabled a test
module on it. The site works in a browser but isn't hosted. The tools work
locally. The packages build. The default source is empty. No mod has been
installed from a published source, and none can be until genuine signing
exists (see Known gaps).

## The app (W `app/`)

| Area | Implemented | Verified | Not verified, and why |
| :--- | :--- | :--- | :--- |
| Git client ([03](tasks/03-git-client.md)) | Git v2 smart HTTP, depth-1 fetch, packs and deltas | Host tests; live fetches from GitHub, GitLab, Codeberg and sourcehut; 1,000 packages in 1 s at a 32 MiB heap | Nothing outstanding |
| Sources, index, search ([05](tasks/05-sources-index.md)) | Preview and trust, transactional refresh, sections, merged candidates, search | Host tests; on the phone: adding a source over HTTPS, browsing it, search ([first phone](first-phone.md)); adding, refreshing and removing a `wavesrc`-published source ([first install](first-install.md)) | Only against local sources; the public source has never been fetched, because it isn't hosted. A private source has never been fetched from the phone |
| Tabs and screens ([07](tasks/07-app-screens.md)) | Wave, Sources, Installed, Search; details, queue, no-root screen; `app/SPEC.md` | Phone QA (`tools/qa`, debug fixtures): on 0.7.6 tabs, browsing to Confirm, search, Installed, the watchdog alert, a failing source, the reboot dialog and offline Sources; on 0.7.12 the default answers (Later, Cancel) and no stale queue alert; on 0.7.16 all 11 fixture scenarios, including the 500-package list. Every mockup frame has a phone screenshot (0.7.12), and the rooted frames were re-shot from real data on the rooted phone (0.7.20): Magisk's grant answered with the d-pad, a mod's details, the queue, Reboot Now and a real watchdog turn-off. Scrolling 500 packages: 499 Downs at 11/18-19/24-27 ms (50th/90th/99th) with 14-17% janky frames on 0.7.21, from 15/31/61 ms and 37% on 0.7.16 ([work notes](tasks/README.md#work-notes)). Installing 0.7.6 over the Updater-derived 0.1.0 kept working | The fixture suite ran in full on 0.7.23 (13 of 13 pass, first time, rooted phone), and the new `07-newer` (Wave itself offered as an update) and `07-kept` (packages kept after their source went: no empty Author row, no Reinstall) scenarios each passed on 0.7.24 builds; no full-suite run is recorded since. The rooted suite (below) passed on 0.7.25. Turn On was seen by agent 1 and in the rooted suite's R03, not in the re-shot frames |
| Install engine ([06](tasks/06-root-install.md)) | Plans with deps and conflicts, resumable download with size and SHA-256, receipts; apps without root go through Installer (`StockApps`); a private source's sign-in sent to its own downloads only (Bearer for its GitHub release assets, Basic on its own host, never past a redirect to another host) | 722 self-tests pass today (`:app:core:selfTest`), and 16,815 checks on what the screens say and offer (`:app:app:selfTest`, since 0.7.24, run over real source files and every debug fixture); the first includes whole plans run through a fake `su` and the sign-in rules against local servers. On the stock phone, from a `wavesrc`-published local HTTPS source: GET, UPDATE, a refused bad hash and Remove of a test app, installed silently through Installer ([first-install.md](first-install.md)) | Against a hosted source (none is published). A real private asset downloaded on a phone (decisions open item 4). Anything through root |
| Root (06) | Opt-in: `su` opens only on a user action | Host tests; on the phone, the no-root path: mods refused before download, guide shown. On the rooted phone (Magisk 24.0), all of [ROOT-TESTING.md](../app/ROOT-TESTING.md) 06: the su prompt (deny, then grant), `pm` installs and removals through root, modules across reboots, Wave's self-update, root removal from Installed | 06.5 (out of space) can't run on this phone |
| Watchdog ([08](tasks/08-boot-watchdog.md)) | Boot watchdog v5, safe-mode handling, launch dialogs | Pretend boots in host tests and against Magisk 24.0's own busybox (`app/root-tests/magisk`). On the rooted phone, ROOT-TESTING.md 08: a hung boot rebooted once and the mod turned off, the alert, Turn On, Remove It, a cut boot, Magisk's safe mode through its property, a clean restart keeping the mod. The cut-boot step found a real bug: the XP3's f2fs lost the unsynced `pending` to `trying` rename, so the mod was tried again instead of turned off. Watchdog 4 (0.7.19) syncs its state. Its cost was measured on the phone: 9.3 s of CPU per judged boot, nothing left running after; watchdog 5 (0.7.25) starts one program a second instead of three and costs 2.9 s, and passed 08.1, 08.4 and 08.6 again ([ROOT-TESTING.md](../app/ROOT-TESTING.md)) | The safe-mode key and a real battery pull, which need a person at the phone. `adb reboot` in the first minute after boot isn't survived (known risk) |

## The site (W `site/`)

| Area | Implemented | Verified | Not verified, and why |
| :--- | :--- | :--- | :--- |
| Landing ([10](tasks/10-site-pages.md), local complete) | Landing, USB platform notes, Pages build; no JavaScript, 56 kB with five real phone screenshots (43.8 kB gzip) | Strict HTML parse, link and size checks; keyboard-only and 240x320 in Chromium; `node --test site/tests/*.test.mjs site/src/protocols/tests/*.test.mjs` 14 pass | Not hosted: Pages must be enabled by the owner |
| Setup generator (10) | Setup files for all six apps, made in the browser | Downloads for all six parse in the apps' `Config.parse`; `sync_setup.py --check` agrees with P's descriptors. On the rooted phone, all six files generated by the real `/setup/` page in headless Chromium were imported with the d-pad only into side-by-side `.wavetest` copies; each opened configured and didn't ask again after a force-stop and relaunch, the setup file was owner-only and the copy on shared storage was deleted. The owner's six installed packages were untouched (same path, version and update time before and after). Evidence in the [work notes](tasks/README.md#work-notes) (brief 10) | Android's "Choose a file..." picker (not drivable by d-pad), a real backend (the hosts in the files don't resolve, so Mail and Canvas show their offline messages after import), source QR creation (no camera validation), and the owner's own copies, which were deliberately not touched |
| Guide ([09](tasks/09-site-jailbreak.md), open) | Device guide, Rescue for already-rooted phones, adb over WebUSB | adb protocol over libusb with an authorized key ([jailbreak.md](jailbreak.md#verification)). Rescue, built, in headless Chromium over real WebUSB on the rooted phone: it listed a marker module (pending and active), disabled it (the next boot ran no `service.sh`), rebooted the phone and listed it again after re-enable; Magisk's policy table never changed. That run found and fixed a false error after a successful reboot (`7d750d3`: the disconnect is taken as success) | The guide has never rooted a phone: the owner's root was done by hand over EDL. The browser's device chooser, the first RSA prompt and the Shell prompt weren't seen (the harness granted the device by policy and used an authorized key), nor Volume Down safe mode. Writing a boot back from the browser and Windows driver binding are untried (decisions open item 5) |
| Backup | Read and check the boot partition over EDL in the browser; **left out of the public build** (`build.py --with-device-backup` adds it) | Headless Chromium on the real phone read all of `boot`, and its SHA-256 matched two desktop EDL reads; the Restore page checks a backup | Linux and Chromium only. Writing it back has not been tried |

## The tools (W `tools/`)

| Area | Implemented | Verified | Not verified, and why |
| :--- | :--- | :--- | :--- |
| `wavesrc lint`, `publish` ([02](tasks/02-wavesrc.md)) | Standard-library CLI (`new` scaffolds a lint-clean source), two composite Actions, [publishing guide](publishing.md) | 34 tests pass today; published the real List APK and retried a concurrent update against a local Git repo | The Actions on GitHub |
| `wavesrc verify` | Audits asset URLs, size and SHA-256 pins | Its tests (in the 34) | `lint --download` against real remote assets: there are none yet |
| Phone QA (`tools/qa`) | D-pad scenarios, screenshots, HTML report, the shared phone lock | 37 host tests pass; used for the 07 runs above. **Rooted suite** (`suite.py --rooted`, R01 to R04): on the rooted phone, against a debug build of 0.7.25 pointed at a local HTTPS source holding `app/root-tests`' two test mods: Check Again raises Magisk's prompt and the d-pad grants it; a mod installs, Reboot Now restarts the phone and the module runs; the watchdog's alert, Turn On and Remove It; a broken mod holds the boot, the watchdog reboots once after 300 s and turns it off. All four passed in 27 min and the phone ended as it began (`.oak/tmp/wave-qa/rooted-suite-full/`, not committed). It is the only automated cover for the reboot paths | It changes root state and reboots the phone, so it is not part of the default suite and needs a person to start it. It proves the paths with the two test mods, not a real mod, and not the safe-mode key or a battery pull |

## The repos

| Area | Implemented | Verified | Not verified, and why |
| :--- | :--- | :--- | :--- |
| Default source ([15](tasks/15-default-source.md)) | `../wave_repository` at `a3c0049`, deliberately empty | Lints; a disposable copy took the real Wave APK and linted; the phone fetched this exact commit locally | Hosting, branch protection, first real publications. Nothing points at the mutable `latest` release |
| Private source ([16](tasks/16-private-source.md), open) | `../wave_private_packages` (`bd56fd0`): Source and Tablet with filtered history | 305 host checks; built, admitted and published to a disposable source; history scan found no credentials | The private host; Wave's authenticated downloads (in since 0.7.14, host tests only) against the real private release on a phone (decisions open item 4); an update on a migration phone. The owner still has to review the history. Both apps stay on P's legacy channel |

## The packages (P `../sonim`)

| Area | Implemented | Verified | Not verified, and why |
| :--- | :--- | :--- | :--- |
| Apps and CI ([13](tasks/13-packages-ci.md)) | Release workflow and gate; 18 APKs (hbsa, Installer and Updater retired) | actionlint; gate unit tests; a clean build signed and checked every APK and published into a scratch source; all 18 apps' code launched and drew correctly on the phone as side-by-side `.qa` builds, not over the owner's installed copies ([app-sweep.md](app-sweep.md)); that sweep found Flashcards' unreadable setup title, fixed in 1.14.3 and not re-run on the phone | A real CI run on GitHub |
| Setup without a baked login ([14](tasks/14-app-setup-codes.md)) | Shared setup screen; Places, Mail, Charge, Authenticator, Flashcards and Canvas have direct backends | Self-tests per app, plus reviews of the setup library and the backends with fixes and tests; each app phone-tested as a `.wavetest` build | Only with fake or test logins: no real Gmail, Tesla or Canvas account was used. Charge's token test runs by hand (`charge/android/tests/tokenstore/run.sh`), not in CI |
| Mods ([11](tasks/11-mods-import.md), [12](tasks/12-mods-rename.md)) | Launcher, Music and Messages imported and renamed to Wave | Dex digests match upstream; the renamed builds and host suites pass from a fresh clone. The module path itself (zip install, update, removal and watchdog across reboots) is proven on the rooted phone with Wave's own test modules (`app/root-tests`) and a Rescue marker module | Installable zips need genuine OEM signatures, and there are none; the owner has decided the mods stay unpublished until genuine signing exists. So no real mod has been installed, run on the newer firmware, or handed over from the old mods |

## Known gaps worth knowing

- **Setup is by file, not QR.** The phone has a QR decoder (Authenticator's)
  and the chunk format is specified and parsed, but nobody has tried reading
  a code off a monitor with the XP3's fixed-focus camera
  ([decisions](decisions.md) open item 1).
- **Backups include logins.** Every app allows Android backup, so
  `adb backup` carries the setup and tokens, so whoever holds a backup can
  read them. The owner decided to keep this, for moving phones
  ([decisions](decisions.md), decided 11).
- **Versions.** Every app on common has an unpublished patch bump (P
  `375e34e` and later). Publishing needs the cutover first. W's `common/`
  copy is synced to P `d905f90` (`common/UPSTREAM`).
- **First phone, end to end ([17](tasks/17-first-phone.md)).** Only the
  stock pass ([first-phone.md](first-phone.md)) and an install, update and
  remove cycle from a local source ([first-install.md](first-install.md))
  are done, plus on the rooted phone: root installs and removals, the watchdog and
  Wave's self-update (ROOT-TESTING 06 and 08) and Rescue from a browser. Still
  to run: the same cycle against the hosted source; onboarding with the real
  mods (blocked on signing); Restore (a boot write from the browser) and the
  legacy migration. Don't retire the legacy channel until those pass.
