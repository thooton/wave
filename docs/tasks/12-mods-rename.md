# 12: Rename Argon to Wave in the mods

**Repo** P (`mods/`). **Needs** 11.

## Read first
`../design.md` section 9. Then `rg -n -i argon mods/` (about 790 matches in
`messages`, 320 in `launcher`, 60 in `music` when this was written).

## Goal
No "Argon" left in the mods' code, names or docs, and a phone already
running the Argon builds loses nothing when it updates.

## The mapping

| Was | Becomes |
| :--- | :--- |
| Messages Argon, Launcher Argon, Music Argon | Messages Improved, Launcher Improved, Music Improved |
| Magisk ids `messages_argon`, `launcher_argon`, `music_argon`, `argon_sqlite` | `messages_improved`, `launcher_improved`, `music_improved`, `sqlite_improved` (if published; `../decisions.md` open 2) |
| Classes `Argon*` (`ArgonTray`, `ArgonLayout`, `ArgonR`...) | `Wave*` |
| Smali bridges `argon*`, fields `sArgon*` | `wave*`, `sWave*` |
| Log tags `Argon`, `ArgonMms`, `ArgonRcs`, `ArgonPerf`, `ArgonSelf` | `Wave`, `WaveMms`, `WaveRcs`, `WavePerf`, `WaveSelf` |
| Build flag `ARGON_RCS` | `WAVE_RCS` |
| Files on the phone, e.g. `argon_tray.txt` | `wave_tray.txt` |
| Preferences, e.g. `argon_library` | `wave_library` |
| Zip names `messages_argon-<v>.zip` | `messages-improved-<v>.zip` |

## Deliver
- The rename, one mod per commit, done by script where it can be (the smali
  hooks are regenerated from `tools/patches/*.py` against the `stock-<mod>`
  tag, not edited by hand).
- **Migration on the phone**: wherever code reads a file or preference under
  a new name, it first moves the old one if the new one is missing. Covered
  by host tests where the mod has them.
- **Module handover**: each zip's install script marks the old module id for
  removal (the existing `LEGACY_MODULE_IDS` mechanism), and its Wave
  manifest lists the old id in `replaces`.
- The zips no longer abort on a fingerprint mismatch (`../decisions.md`,
  decided 7 and working default 6). `module.prop` gets a real `version` and a
  `versionCode` that rises with it, not the build time.
- Docs and AGENTS files reworded.

## Done when
`rg -i argon mods/` finds only the legacy ids and the migration code, each
with a comment saying why; all mods build and pass their host tests; and on
a reserved test phone running the Argon modules, installing the new zips
and rebooting keeps the tray layout, the Music whitelist and the RCS
configuration.

## Watch out
- The manifests and resources of the stock APKs stay byte-identical: this is
  a rename of our dex code only. Anything named in a stock manifest or
  resource can't change.
- A class rename changes every smali hook that calls it. Run dexcheck and
  linkcheck, and the on-phone `dex2oat` verify from the launcher's docs.
- RCS state lives in the app's private files; find every path before
  renaming any.
