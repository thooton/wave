# spec

- `wave.schema.json`, `package.schema.json`: JSON Schemas (draft 2020-12) for
  the two files of the source format. `docs/source-format.md` is the text;
  where they differ, fix both.
- `example-source/`: a small valid source, used as the fixture by `wavesrc`
  and by the app's self-tests. Its download URLs and hashes are
  placeholders, so it passes `wavesrc lint` but not `lint --download`.
