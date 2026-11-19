# wavesrc

```sh
./tools/wavesrc/wavesrc lint spec/example-source
python3 -m unittest discover -s tools/wavesrc
```

Run those commands at the Wave repository root. `python3 -m unittest` also
works from `tools/wavesrc/`. The tool and tests use the Python standard library;
Git is needed for publishing and `aapt2` is needed to inspect APKs. The APK
integration test uses the sibling `../sonim` List build if it exists, or builds
a tiny fixture with the installed SDK. Set `WAVESRC_TEST_APK` to exercise
another APK. No tests contact GitHub or need network credentials.

See [Publishing your own packages](../../docs/publishing.md) for usage and CI
examples, and the [source format](../../docs/source-format.md) for the rules.
