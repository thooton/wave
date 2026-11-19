# Status: what works today

As of 2026-11-19. Wave is at 0.7.6 (code 13). Nothing has been pushed or
published: every GitHub step (repo renames, CI runs, Pages, releases, the
hosted default source) waits on the [owner cutover](tasks/01-owner-checklist.md).
So "verified" below means on this machine, and on the one reserved phone
(XP3800 `8516067e`, Android 8.1, **stock and not rooted**). The briefs'
progress and the evidence behind each line are in the
[work notes](tasks/README.md#work-notes).

The short version: the app browses, searches and plans on a real phone. It
has not yet installed anything from a real source. Nothing that needs root
has run on a phone. The site works in a browser but isn't hosted. The tools
work locally. The packages build. The default source is empty.

## The app (W `app/`)

| Area | Implemented | Verified | Not verified, and why |
| :--- | :--- | :--- | :--- |
| Git client ([03](tasks/03-git-client.md)) | Git v2 smart HTTP, depth-1 fetch, packs and deltas | Host tests; live fetches from GitHub, GitLab, Codeberg and sourcehut; 1,000 packages in 1 s at a 32 MiB heap | Nothing outstanding |
| Sources, index, search ([05](tasks/05-sources-index.md)) | Preview and trust, transactional refresh, sections, merged candidates, search | Host tests; on the phone: adding a source over HTTPS, browsing it, search ([first phone](first-phone.md)) | Only against a local fixture source; the public source has never been fetched, because it isn't hosted |
| Tabs and screens ([07](tasks/07-app-screens.md), open) | Wave, Sources, Installed, Search; details, queue, no-root screen; `app/SPEC.md` | Phone QA (`tools/qa`) on 0.7.2: tabs, browse and preview, search, Home and root info all pass | 0.7.3 onward have no phone QA report. Not every mockup frame has a phone screenshot yet, which is 07's done-when. Installing over the Updater-derived build isn't rechecked |
| Install engine ([06](tasks/06-root-install.md)) | Plans with deps and conflicts, resumable download with size and SHA-256, receipts; apps without root go through Installer (`StockApps`) | 505 self-tests pass today (`:app:core:selfTest`), including whole plans run through a fake `su` | No real package has been installed by Wave on a phone: the local fixture had no download assets, and there's no published one |
| Root (06) | Opt-in: `su` opens only on a user action | Host tests; on the phone, the no-root path: mods refused before download, guide shown | Everything rooted. The phone isn't rooted; [ROOT-TESTING.md](../app/ROOT-TESTING.md) section 06 is the procedure |
| Watchdog ([08](tasks/08-boot-watchdog.md)) | Boot watchdog v2, safe-mode handling, launch dialogs | Pretend boots in host tests (good, hung, restarted, framework restarts, no reboot loop) | No real boot; safe-mode key timing on the XP3 unknown. ROOT-TESTING.md section 08 |

## The site (W `site/`)

| Area | Implemented | Verified | Not verified, and why |
| :--- | :--- | :--- | :--- |
| Landing ([10](tasks/10-site-pages.md), open) | Landing, USB platform notes, Pages build; no JavaScript, 27 kB | Strict HTML parse, link and size checks; keyboard-only and 240x320 in Chromium; `node --test` 14 pass | Not hosted: Pages must be enabled by the owner |
| Setup generator (10) | Setup files for all six apps, made in the browser | Downloads for all six parse in the apps' `Config.parse`; `sync_setup.py --check` agrees with P's descriptors | A generated file imported on the phone. Each app was phone-tested with hand-made files of the same format (brief [14](tasks/14-app-setup-codes.md)) |
| Guide ([09](tasks/09-site-jailbreak.md), open) | Device guide, Rescue for already-rooted phones, adb over WebUSB | adb protocol over libusb with an authorized key ([jailbreak.md](jailbreak.md#verification)) | Browser rooting, boot writes and restore were never done. The browser's device picker and first RSA prompt weren't seen live. Windows driver binding is open (decisions open item 5) |
| Backup | Read and check the boot partition over EDL in the browser; **left out of the public build** (`build.py --with-device-backup` adds it) | Headless Chromium on the real phone read all of `boot`, and its SHA-256 matched two desktop EDL reads; the Restore page checks a backup | Linux and Chromium only. Writing it back has not been tried |

## The tools (W `tools/`)

| Area | Implemented | Verified | Not verified, and why |
| :--- | :--- | :--- | :--- |
| `wavesrc lint`, `publish` ([02](tasks/02-wavesrc.md)) | Standard-library CLI, two composite Actions, [publishing guide](publishing.md) | 25 tests pass today; published the real List APK and retried a concurrent update against a local Git repo | The Actions on GitHub |
| `wavesrc verify` | Audits asset URLs, size and SHA-256 pins | Its tests (in the 25) | `lint --download` against real remote assets: there are none yet |
| Phone QA (`tools/qa`) | D-pad scenarios, screenshots, HTML report, the shared phone lock | 7 tests pass; used for the 07 runs above | Nothing outstanding |

## The repos

| Area | Implemented | Verified | Not verified, and why |
| :--- | :--- | :--- | :--- |
| Default source ([15](tasks/15-default-source.md)) | `../wave_repository` at `a3c0049`, deliberately empty | Lints; a disposable copy took the real Wave APK and linted; the phone fetched this exact commit locally | Hosting, branch protection, first real publications. Nothing points at the mutable `latest` release |
| Private source ([16](tasks/16-private-source.md), open) | `../wave_private_packages` (`2262590`): Source and Tablet with filtered history | 290 host checks; built, admitted and published to a disposable source; history scan found no credentials | The private host, authenticated downloads in Wave (decisions open item 4), an update on a migration phone. The owner still has to review the history. Both apps stay on P's legacy channel |

## The packages (P `../sonim`)

| Area | Implemented | Verified | Not verified, and why |
| :--- | :--- | :--- | :--- |
| Apps and CI ([13](tasks/13-packages-ci.md)) | Release workflow and gate; 18 APKs (hbsa, Installer and Updater retired) | actionlint; gate unit tests; a clean build signed and checked every APK and published into a scratch source | A real CI run on GitHub |
| Setup without a baked login ([14](tasks/14-app-setup-codes.md)) | Shared setup screen; Places, Mail, Charge, Authenticator, Flashcards and Canvas have direct backends | Self-tests per app, plus reviews of the setup library and the backends with fixes and tests; each app phone-tested as a `.wavetest` build | Only with fake or test logins: no real Gmail, Tesla or Canvas account was used. Charge's token test runs by hand (`charge/android/tests/tokenstore/run.sh`), not in CI |
| Mods ([11](tasks/11-mods-import.md), [12](tasks/12-mods-rename.md)) | Launcher, Music and Messages imported and renamed to Wave | Dex digests match upstream; the renamed builds and host suites pass from a fresh clone | Installable zips need genuine OEM signatures, and there are none. So no mod has been installed, run on the newer firmware, or handed over from the old mods |

## Known gaps worth knowing

- **Setup is by file, not QR.** The phone has a QR decoder (Authenticator's)
  and the chunk format is specified and parsed, but nobody has tried reading
  a code off a monitor with the XP3's fixed-focus camera
  ([decisions](decisions.md) open item 1).
- **Backups include logins.** Every app allows Android backup, so
  `adb backup` carries the setup and tokens. That's deliberate for moving
  phones, and waiting on the owner.
- **Versions.** Every app on common has an unpublished patch bump (P
  `375e34e` and later). Publishing needs the cutover first.
- **First phone, end to end ([17](tasks/17-first-phone.md)).** Only the
  stock pass is done ([first-phone.md](first-phone.md)). Still to run: a
  hosted install, update and remove cycle; rooted onboarding with the mods
  and the watchdog; Rescue and Restore; and the legacy migration. Don't
  retire the legacy channel until those pass.
