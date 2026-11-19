# 16: Source and Tablet in their own repo and source

**Repo** a new one (name to be chosen with Sam; private). **Needs** 13.

## Read first
`../sonim/source/AGENTS.md`, `../sonim/tablet/AGENTS.md`,
`../decisions.md` (decided 8, open 4).

## Goal
Source and Tablet, which only make sense with Sam's own infrastructure, are
out of `wave_packages` and the default source, and still install and update
through Wave on his phones from a source of their own.

## Deliver
- A repo holding the two apps with their history, building against
  `common/` the way `thooton/wave` does (a synced copy), with the same
  release-and-publish CI as 13.
- A source for them: either a second small repo, or `wave.json` and the
  section folders at the top of the code repo itself (the format ignores
  everything else, and the fetch only takes small files). Pick one and say
  why.
- The source added on Sam's phones, with a token in the URL if the repo is
  private.

## Done when
Both apps update through Wave from that source, and neither is built or
mentioned in `wave_packages`.

## Watch out
- These two keep their baked Delta login (`bundled.gradle`), so their APKs
  contain credentials: the repo's releases must be private, and a private
  release asset can't be downloaded with a plain URL. Either host the
  binaries where a token in the URL works, or move these two to setup codes
  (14) as well and make the repo public. Raise this with Sam before
  building anything.
