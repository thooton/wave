# 15: The default source

**Repo** R (`thooton/wave_repository`). **Needs** 01, 02. Filled by 13.

## Read first
`../source-format.md`, `../../spec/example-source/`.

## Goal
`https://github.com/thooton/wave_repository` is a valid source that the Wave
app has built in, kept correct by CI.

## Deliver
- `wave.json`: name "Wave", a description, sections `applications`
  (Applications) and `system` (System), and a first `featured` list.
- `icon.png`: Wave's icon, 96x96.
- `README.md`: what this repo is, that its manifests are written by
  `wave_packages` CI and not by hand, and how to add this source (it is
  built in) or make one's own (link to the format).
- `.github/workflows/lint.yml`: `uses: thooton/wave/lint@v1` on every push,
  and nightly with `--download` so a dead link or a changed binary is
  noticed.
- Branch protection suited to a repo that CI pushes to.

## Done when
`wavesrc lint` passes on the empty-but-valid source, and after 13's first
run every package in `../design.md` section 8 that is ready has a folder
here.

## Watch out
- Wave itself is a package here (`applications/wave`, id `com.wave.xp3`),
  published by this repo's sibling `thooton/wave` using the same Action.
  That job belongs to 04's CI once this source exists; add it then.
