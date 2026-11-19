# 13: Releases and publishing from wave_packages CI

**Repo** P. **Needs** 01, 02. 11 and 12 for the mods' part.

## Read first
`../design.md` sections 7 and 8, `../source-format.md`,
`../sonim/.github/workflows/build-apks.yml`, `../sonim/.github/manifest.py`,
`../sonim/AGENTS.md` ("Releases", "Every change to an app bumps its
version").

## Goal
Pushing a version bump to master ends with that version installable from
the default source, with nothing done by hand.

## Deliver
- **`wave/` beside each package**: `package.json` with the fixed fields
  (`id`, `type`, `name`, `summary`, `description`, `author`, `homepage`,
  `requires`, `depends`, `conflicts`, `replaces`) and the section it goes
  in, plus `icon.png` (96x96, exported from the app's launcher icon). Write
  a real summary and description for each; these are what people read in
  Wave.
- **Releases**: for each package whose current version has no release tag
  `<name>-<version>`, CI creates the release and uploads
  `<name>-<version>.apk` or `.zip`. Never overwrite an asset: a version that
  was released is final, and a change without a bump fails the build with a
  message saying so.
- **Publish**: after the upload, `uses: thooton/wave/publish@v1` writes the
  manifest and icon into `wave_repository`.
- **The mods in CI**: a job with apktool, build-tools, Python (and the NDK
  if the SQLite module is published) that builds and packages `mods/*`.
- **Retirements**: remove HBSA from the build; move Source and Tablet out
  (16); remove AppDrawer (`launcher/`) once Launcher Improved covers the
  programmable key that opened it (see the last lines of
  `../launcher_argon/AGENTS.md`; if it can't, keep AppDrawer and say so in
  `../decisions.md`); Updater and Installer get a last release whose
  description says Wave replaces them, and Wave's manifest lists both in
  `replaces`.
- **The old channel**: keep publishing the rolling `latest` release with
  `manifest.json` until Sam says every phone is on Wave, then delete that
  job.
- `AGENTS.md` updated: how shipping works now, and that `SPEC.md` rules and
  version bumps still apply.

## Done when
A no-op version bump of one app and one mod each appear in
`wave_repository` within one CI run, `wavesrc lint --download` passes on the
result, and a second push without a bump publishes nothing.

## Watch out
- Installer is the device owner on Sam's phones. Removing it needs the role
  given up first (its status page has the button); `pm uninstall` fails
  otherwise. Wave's `replaces` handling (06) must cope, or Installer is
  removed by hand.
- Apps that still bake a Delta login (`gradle/bundled.gradle`) build with
  empty constants in CI already; published APKs must never contain a login.
  Add a CI check that the generated `Bundled` constants are empty.
- Two packages finishing at once both push to `wave_repository`; the Action
  retries, but keep one publish job per package rather than one commit for
  all so a failure is isolated.
