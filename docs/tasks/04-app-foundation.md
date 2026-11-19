# 04: App foundation from the Updater

**Repo** W (`app/`, `common/`). **Needs** nothing. **Blocks** 03, 05, 06.

## Read first
`../sonim/AGENTS.md`, `../sonim/updater/AGENTS.md` and `SPEC.md`, the
Updater's sources (about 2,400 lines), `../sonim/gradle/*.gradle`,
`../sonim/settings.gradle`, `../sonim/signing/`.

## Goal
This repo builds an APK called Wave that is, for now, the Updater under a new
name, with the build, tests and CI in place for everything that follows.

## Deliver
- `app/core` and `app/app`: the Updater's two modules copied in, package
  `com.wave.xp3` (core: `com.wave.xp3.core`), label "Wave", version `0.1.0`,
  a placeholder icon.
- `common/core` and `common/ui`: copied from `../sonim/common`, with
  `tools/sync-common.sh` that redoes the copy and writes the source commit to
  `common/UPSTREAM`. Nothing in `common/` is edited here.
- The Gradle build at the repo root (`settings.gradle`, `gradle/app.gradle`,
  `gradle/core.gradle`, wrapper), the shared signing config, JDK 21, same
  commands as `wave_packages`:
  `./gradlew :app:core:selfTest :app:app:assembleRelease`.
- `.github/workflows/build.yml`: self-tests and a release build on every
  push; the APK as an artifact. (Releasing and publishing Wave into the
  default source comes with 13 and 15.)
- `app/AGENTS.md` and `app/SPEC.md`, started from the Updater's and cut down
  to what is true of Wave.

## Done when
CI is green, the APK installs on a phone beside the real Updater (different
package) and shows the Updater's list.

## Watch out
- Keep the Updater's behaviour intact in this step. The Installed tab is
  this screen; 05 to 07 change where its data comes from, not how it looks.
- The Updater hands APKs to Installer through a signature permission. Leave
  that path in until 06 replaces it with root.
