# 05: Sources, index and search

**Repo** W (`app/core`). **Needs** 03, 04. **Blocks** 07.

## Read first
`../source-format.md`, `../../spec/`, `../design.md` sections 2 and 4, the
Updater's `Catalog`, `Status`, `Version` and `Notes`.

## Goal
Everything the screens need to know about sources and packages, in plain
Java, tested on a desktop.

## Deliver
- `SourceManifest`, `PackageManifest`: strict parsers over `common/core`'s
  `Json` that ignore unknown fields, enforce the format's limits, and report
  what is wrong with a manifest in words. A bad package hides itself, not
  its source.
- `SourceStore`: the list of sources (`files/sources.json`), each source's
  fetched tree on disk, add / remove / refresh (through 03's `fetchTree`),
  atomic replacement, the default source built in and not removable.
- `Index`: every package from every source merged by `id` (highest version,
  then source order), with lookups by id, by source, by section, and
  section counts.
- `Compat`: a package against the phone's facts (device, model, SDK,
  fingerprint, Wave's format version), giving "fits", "greyed because...",
  or "note: not tested on this firmware".
- `Status`: a package against what is on the phone (not installed, installed,
  update, newer than the source, pending install, pending removal). The
  phone's state comes in through an interface that 06 implements.
- `Search`: results for a typed string over name, summary and description,
  names first; fast enough to run on each keypress over a few thousand
  packages.
- `SelfTest` cases for all of it, with `spec/example-source/` as the main
  fixture and broken variants beside it.

## Done when
Self-tests pass, and the parser and `wavesrc lint` (02) agree on every
fixture: what one accepts the other accepts.

## Watch out
- Two sources can disagree about a package's `type` for the same `id`. Pick
  the rule (the winning version's type) and test it.
- Refreshing several sources: one that fails must not hold up or discard
  the others.
