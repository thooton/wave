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
| 00 | [Mockups of the app's screens](00-mockups.md) | W | | [ ] |
| 01 | [Create and rename the repos](01-repos.md) | all | | [x] |
| 02 | [wavesrc: lint and publish](02-wavesrc.md) | W | | [ ] |
| 03 | [The git client](03-git-client.md) | W | 04 | [ ] |
| 04 | [App foundation from the Updater](04-app-foundation.md) | W | | [x] |
| 05 | [Sources, index and search](05-sources-index.md) | W | 03, 04 | [ ] |
| 06 | [Root and the install engine](06-root-install.md) | W | 04 | [ ] |
| 07 | [The app's screens](07-app-screens.md) | W | 00, 05, 06 | [ ] |
| 08 | [The boot watchdog](08-boot-watchdog.md) | W | 06 | [ ] |
| 09 | [The jailbreak page](09-site-jailbreak.md) | W | | [ ] |
| 10 | [Landing page and setup-code generator](10-site-pages.md) | W | 14 for the generator | [ ] |
| 11 | [Import the mods into wave_packages](11-mods-import.md) | P | 01 | [ ] |
| 12 | [Rename Argon to Wave in the mods](12-mods-rename.md) | P | 11 | [ ] |
| 13 | [Releases and publishing from wave_packages CI](13-packages-ci.md) | P | 01, 02 | [ ] |
| 14 | [Setup codes: apps without a baked login](14-app-setup-codes.md) | P | | [ ] |
| 15 | [The default source](15-default-source.md) | R | 01, 02 | [ ] |
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
