# AGENTS.md (the Wave app)

The Wave Android app, `com.wave.xp3`: a package manager for the Sonim XP3
(240x320 mdpi, d-pad only, Android 8.1 / API 27). Built from the Updater in
`wave_packages/updater` and, for now (brief 04), still behaves exactly as
the Updater does: one screen listing the apps in the rolling `latest`
release of thooton/sonim. Briefs 05 to 08 change where the data comes from
and add the other tabs, root and the boot watchdog; see `../docs/design.md`
sections 3 to 5. What the screen does is in `SPEC.md`.

Same constraints as every app in `wave_packages`: no AndroidX or
third-party libraries in the app or its tests, Java 8 language level,
nothing above API 27, `:core` plain Java compiled with `--release 8`.

## Modules

- `core` (`:app:core`, `com.wave.xp3.core`): plain Java, tested on a
  desktop JVM. Today: `Version`, `Catalog` (manifest.json), `Status`,
  `Notes` (what's new, the App Store's dates), `Releases` (fetch and
  download over HttpURLConnection). The git client goes in
  `com.wave.xp3.core.git` (brief 03). Tests are `src/test/java`, run by
  `com.wave.xp3.SelfTest` with `com.xp3.common.Check` (from
  `:common:core`'s test fixtures); no test framework.
- `app` (`:app:app`, `com.wave.xp3`): `MainActivity` (the list, keys, menu,
  What's New, install queue), `AppAdapter`, `ProgressRing`, `Icons`,
  `ApkProvider` (serves a downloaded APK to the installer).
- `../common/`: a copy of `wave_packages/common`. Never edit it here; change
  it upstream and run `../tools/sync-common.sh` (writes `common/UPSTREAM`).

Installs still go to Installer (`com.installer.xp3`) through its
signature-only permission, which Wave holds because it is signed with the
same shared key (`../signing/`). Brief 06 replaces that path with root.

## Build and validation

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :app:core:selfTest :app:app:assembleRelease
```

JDK 21 (Gradle 8.9 does not run on newer JDKs). `local.properties` with
`sdk.dir=` your Android SDK (gitignored). CI (`../.github/workflows/build.yml`)
runs the same and uploads `wave.apk`.

There are no UI automation tests; key, focus and install changes need manual
checks on the handset (d-pad keyevents over adb, `screencap`). Every change
bumps `versionName` and `versionCode` in `app/build.gradle`.
