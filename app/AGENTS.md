# AGENTS.md (the Wave app)

The Wave Android app, `com.wave.xp3`: a package manager for the Sonim XP3
(240x320 mdpi, d-pad only, Android 8.1 / API 27). Built from the Updater in
`wave_packages/updater`; its four tabs now use the Git source index and
install plans. See [SPEC.md](SPEC.md) and [design](../docs/design.md) sections
3–5 for source, install and watchdog behavior.

Same constraints as every app in `wave_packages`: no AndroidX or
third-party libraries in the app or its tests, Java 8 language level,
nothing above API 27, `:core` plain Java compiled with `--release 8`.

## Modules

- `core` (`:app:core`, `com.wave.xp3.core`): plain Java, tested on a
  desktop JVM. Today: `Version`, `Catalog` (manifest.json), `Status`,
  `Notes` (what's new, the App Store's dates), `Releases` (fetch and
  download over HttpURLConnection). The git client goes in
  `com.wave.xp3.core.git` (brief 03), sources in `core.sources` (05).
  Root in `core.root`: `Scripts` (every shell script Wave sends), `Frame`,
  `ProcessShell` (the one `su` shell), `PmResult`, `Modules`, `Facts`,
  `Watchdog` (the boot watchdog; its scripts are resources beside it).
  The install engine in `core.install`: `Plan`, `Download`, `Verify`,
  `Receipts`, `Packages`, `Engine`. `Sandbox` in the tests is a pretend
  phone (fake `su`, `pm`, `magisk`, `getprop`...) that runs the real
  scripts; `WAVE_TEST_SH="busybox ash"` runs them under Magisk's shell.
  Tests are `src/test/java`, run by
  `com.wave.xp3.SelfTest` with `com.xp3.common.Check` (from
  `:common:core`'s test fixtures); no test framework.
- `app` (`:app:app`, `com.wave.xp3`): launcher `app.WaveActivity` and its
  d-pad screens, plus `data.SourceController` for the catalog and plans.
  `MainActivity` is the legacy Updater screen, no longer on the launcher.
  `ApkProvider` serves a downloaded APK to the installer. `RootShell`,
  `Phone` (what's installed; the engine's platform), `EngineService` (runs
  a plan in the foreground), `StockApps` (apps through Installer without
  root), `AfterBoot` (self-update result and watchdog notices on launch).
  `src/debug`: `DebugActivity`, adb-only, for `ROOT-TESTING.md`.
- `../common/`: a copy of `wave_packages/common`. Never edit it here; change
  it upstream and run `../tools/sync-common.sh` (writes `common/UPSTREAM`).

Root is opt-in: `su` is opened only when the user does something that
needs it (any module, or once root has worked: `RootShell.known`). Without
it, apps go to Installer (`com.installer.xp3`) through its signature-only
permission, which Wave holds because it is signed with the same shared key
(`../signing/`). On-phone root checks: `ROOT-TESTING.md`.

## Build and validation

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :app:core:selfTest :app:app:selfTest :app:app:assembleRelease
```

`:app:app:selfTest` runs on the desktop JVM like the core's: the screens' decisions
live in `Pages` (what a row or page says), `Actions` (what a package offers and
what pressing it does) and `Format`, none of which touch a View. Host tests also
exercise `CatalogModel` over source files. Put new logic of that kind there, with
a focused check in `app/app/src/test`, not inline in a screen.

A core test that starts a shell does so through `Sandbox` (`app/core/src/test`),
which runs each one in its own process group and kills whatever is left when it
closes. Anything it had to kill fails the run's last check, "Sandbox: leaks":
a test must stop what it starts. (A loop left behind once spun at full CPU
forever after its sandbox folder, and so its fake `sleep`, was deleted: one per
run, 80 of them before anyone noticed.)

Run from the repository root with JDK 21 (Gradle 8.9 does not run on newer
JDKs) and the Android SDK's platform 35 and Build Tools 35.0.1. Set
`ANDROID_HOME` or use an ignored root `local.properties` with
`sdk.dir=/path/to/android-sdk`. CI (`../.github/workflows/build.yml`) runs the
same checks and uploads `wave.apk`.

Use [the handset smoke checklist](../tools/qa/README.md) for d-pad/focus checks on a
reserved phone. Every app change bumps `versionName` and `versionCode` in
`app/app/build.gradle` (relative to the repository root).
