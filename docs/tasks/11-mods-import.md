# 11: Import the mods into wave_packages

**Repo** P. **Needs** 01. **Blocks** 12.

## Read first
`../messages_argon/AGENTS.md`, `../launcher_argon/AGENTS.md`,
`../music_argon/AGENTS.md`, their `tools/` (note `launcher_argon` and
`music_argon` link `.tools` to `messages_argon`'s and each carries its own
copy of `tools/lib`), and `../sonim/settings.gradle`.

## Goal
The three mods live in `wave_packages` under `mods/`, with their history,
build there, and can be built by CI. No renaming of code yet (that is 12),
so that this step is a pure move and easy to check.

## Deliver
```
mods/
  tools/            one copy of the shared toolkit: env, apktool/jadx setup,
                    splice_apk.py, smalipatch.py, strip_feature.py, the
                    module packager, dexcheck/linkcheck
  messages/         from messages_argon  (apps/, docs/, module/, native/, tools/ for what is its own)
  launcher/         from launcher_argon
  music/            from music_argon
  firmware/         the stock APKs the mods patch, and build.prop, committed
```
- History kept: rewrite each mod's history into its subfolder and merge it
  (`git filter-repo --to-subdirectory-filter`, then merge with unrelated
  histories). Each repo's `stock` tag becomes `stock-messages`,
  `stock-launcher`, `stock-music`, and the docs that say `git diff stock`
  are updated.
- The stock APKs each mod needs (BMms, TelephonyProvider, ContactsProvider,
  SonimLauncher, BMusic, and whatever the SQLite build links against) in
  `mods/firmware/system/...` with `build.prop`. Not the whole 430 MB dump.
- `mods/<mod>/tools/build.sh`, `test.sh`, `package.sh` working from the new
  place; `deploy.sh` unchanged in behaviour.
- A root `mods/AGENTS.md` (what is shared, the hard constraints once) and
  each mod's own trimmed to what is particular to it.
- The Gradle build untouched: `settings.gradle` only looks at top-level
  folders, so `mods/` is invisible to it. Check that it stays so.

## Done when
Each mod builds from a fresh clone with only `mods/tools/setup.sh` run
first, and the APKs it produces are byte-identical to what the old repo
builds at the same commit (compare SHA-256; if apktool output isn't
deterministic, compare the dex files).

## Watch out
- The mods' builds need apktool, the Android build-tools (`d8`, `zipalign`)
  and Python; the SQLite build needs the NDK. Write down versions; 13 puts
  them in CI.
- `decompiled/` and the full `firmware/` dump stay ignored.
- `../sonim/launcher` (AppDrawer) is a different thing from
  `mods/launcher`. Don't merge them here; 13 retires AppDrawer.
- Other agents may have uncommitted work in the old mod repos. Check `git
  status` in each and ask before importing.
