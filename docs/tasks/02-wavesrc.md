# 02: wavesrc, lint and publish

**Repo** W (`tools/wavesrc/`). **Needs** nothing. **Blocks** 13, 15.

## Read first
`../source-format.md`, `../../spec/`, `../design.md` section 7, and
`../sonim/.github/manifest.py` (the script this grows out of: how it reads a
built APK with `aapt2` and turns `git log` into what's new).

## Goal
One Python 3 tool, standard library only, with two commands, and a GitHub
Action that wraps each.

**`wavesrc lint <source dir>`** checks a source against the format: layout,
both schemas, sizes, icon dimensions, ids unique, `depends`/`conflicts`/
`replaces` name things that exist (a warning when they don't, since they may
be in another source), `https://` everywhere. With `--download` it also
fetches each binary and checks size, SHA-256, and that the binary is what the
manifest says (APK package and `versionName`; a zip's `module.prop` id and
version). Exit status non-zero on any error, messages as `path: what is
wrong`.

**`wavesrc publish`** writes one package into a source checkout:

```
wavesrc publish --source <checkout of the source> --section applications \
    --meta <pkg>/wave/package.json --icon <pkg>/wave/icon.png \
    --binary build/mail-1.4.2.apk --url <release asset URL> \
    --changes-from <git dir> --changes-path <pkg dir>
```

It reads version and id from the binary (never from the caller), computes
size and SHA-256, builds `changes`, merges with the static metadata, writes
`<section>/<folder>/package.json` and `icon.png`, lints the result, and
leaves committing and pushing to the Action.

## Deliver
- `tools/wavesrc/` with the two commands and tests that run with plain
  `python3 -m unittest`, using `spec/example-source/` and deliberately broken
  copies of it.
- `lint/action.yml` and `publish/action.yml` at the repo root (composite
  Actions), so another repo writes `uses: thooton/wave/publish@v1`. The
  publish Action checks out the source, runs the tool, commits and pushes
  with retry on a non-fast-forward (two packages may publish at once).
- A short "Publishing your own packages" page in `docs/`.

## Done when
`wavesrc lint spec/example-source` passes, each broken fixture fails with the
message a person would need, and publishing the example APK into a scratch
source produces a manifest that lints.

## Watch out
- The schemas in `spec/` are the contract with the app's parser (05). If you
  find the format wrong, change the doc, the schemas, the example and the
  tool together.
- No `jsonschema` package: either validate by hand from the schema's subset
  or keep the checks in code and test that code and schema agree on the
  example.
