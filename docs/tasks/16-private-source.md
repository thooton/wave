# 16: Source and Tablet in their own repo and source

**Repo** `thooton/wave_private_packages` (new, private). **Needs** 13's CI
pattern and 14's shared setup flow.

## Read first
`../sonim/source/AGENTS.md`, `../sonim/tablet/AGENTS.md`,
`../decisions.md` (decided 8, open 4).

## Goal
Source and Tablet depend on private infrastructure. Move them out of
`wave_packages` and the default source while keeping installs and updates
available through a separate private source.

## Deliver
- A private repo holding the two apps with their history, building against
  a synced copy of `common/`. Check the imported history for bundled secrets
  before pushing. Adapt the release-and-publish CI from 13.
- Keep `wave.json` and the generated section folders in that repo so the
  code and the source share one access policy. Keep releases private too.
- Use the setup flow from 14 for Delta credentials; never bake a login into
  an APK. Keep credentials out of manifests and logs. Configure source and
  asset access separately from package metadata.
- Add the source to a reserved migration device and verify an authenticated
  refresh and update before removing the apps from `wave_packages`.

## Done when
Both apps update through Wave from that source, neither is built or listed
in `wave_packages`, and their APKs contain no bundled credentials.

## Watch out
- A private GitHub release asset can't be downloaded with a plain URL.
  Verify the authenticated download path before cutting over. If private
  asset hosting or credentials are unavailable, leave cutover pending and
  continue the repo, build, and packaging work. Don't publish the repo or
  assets to work around missing access.
