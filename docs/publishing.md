# Publishing your own packages

A Wave source is a Git repository, not a binary host. Put `wave.json` at its
root and commit the manifests and icons to its default branch. Your built APKs
and module ZIPs can be on GitHub Releases or any HTTPS host. Give each version
its own asset URL and never overwrite a published asset. See the
[source format](source-format.md) and the [example source](../spec/example-source/).

Starting from nothing? `wavesrc new DIR --name NAME --id PACKAGE_ID` writes a
source that lints clean, and [publishing a source](publishing-a-source.md)
walks through filling it in.

## Publish locally

Install Python 3.8 or later and Git. For APKs, install Android SDK Build Tools
and put `aapt2` on `PATH`, set `ANDROID_HOME`, or pass `--aapt2 /path/to/aapt2`.
Module ZIPs only need Python and Git; `module.prop` must be at the ZIP root.

Beside your package's code, create `wave/package.json` containing the metadata
that stays the same across releases, and add a square PNG (at most 1024 pixels, 96 recommended) at `wave/icon.png`
(96×96 recommended, up to 64 KiB). For example:

```json
{
  "type": "apk",
  "name": "My App",
  "summary": "A useful application for the XP3800",
  "requires": { "sdk": 27 }
}
```

From the package repository, after building and uploading the asset:

```sh
/path/to/wave/tools/wavesrc/wavesrc publish \
  --source ../my-source --section applications \
  --meta my-app/wave/package.json --icon my-app/wave/icon.png \
  --binary build/my-app-1.2.0.apk \
  --url https://github.com/you/packages/releases/download/my-app-1.2.0/my-app-1.2.0.apk \
  --changes-from . --changes-path my-app
```

The tool takes the ID and version from the APK or ZIP. An optional static
`id` is checked against the binary; `version`, `download` and `changes` are
generated, not read from the static file. The tool uses at most ten recent
non-merge commits touching the package, excluding Markdown and `mockups`.
A subject such as `My App 1.2.0: fix notifications` becomes
`Fix notifications.` for version `1.2.0`; otherwise it uses the binary's
version. Use full Git history if your checkout is shallow.

The destination folder is derived from the display name, or set with
`--folder`. A later publish reuses the existing folder for the same ID.
Publishing refuses to downgrade or change the download of an existing version.
The CLI validates the result and writes the manifest and icon. Review, commit
and push those files yourself. To check the source without publishing:

```sh
/path/to/wave/tools/wavesrc/wavesrc lint ../my-source
/path/to/wave/tools/wavesrc/wavesrc lint ../my-source --download
```

The second command downloads the assets, requires HTTPS even on redirects,
and verifies their size, hash and binary identity. References to packages not
in the source are warnings; those packages may be in another source.

For a periodic link and pin audit, use `wavesrc verify ../my-source`. It
streams every asset without keeping an APK or requiring the Android SDK,
checks the declared size and SHA-256, and rejects moving download URLs such
as GitHub's `/releases/latest/`, a `releases/download/latest/` tag, or a
`version=latest` selector. Redirects must stay on HTTPS and avoid these
aliases as well. A pass proves the bytes match **now**, not that a server
can never replace them. Use immutable releases or content-addressed storage;
keep `lint --download` for APK/module identity checks.

## Publish from GitHub Actions

The calling workflow checks out the package repo with `fetch-depth: 0`, builds
and uploads an immutable release asset, then calls the composite action. The
publish action uses a separate checkout of the source's default branch under
`.wavesrc-target` in the workspace. It commits and pushes, retrying if another
publisher advances the branch while it works. The secret needs **Contents:
read and write** on the *source* repository; the caller's `GITHUB_TOKEN`
usually doesn't have access to a different repository. Do not pass credentials
in an asset URL.

```yaml
- uses: thooton/wave/publish@v1
  with:
    source-repository: you/my-source
    source-token: ${{ secrets.SOURCE_TOKEN }}
    section: applications
    meta: my-app/wave/package.json
    icon: my-app/wave/icon.png
    binary: build/my-app-1.2.0.apk
    url: https://github.com/you/packages/releases/download/my-app-1.2.0/my-app-1.2.0.apk
    changes-path: my-app
```

In the source repository's own CI, check it out, then use
`thooton/wave/lint@v1`. Optional inputs are `source` (default `.`),
`download` (default `'false'`) and `aapt2`. Both actions need `python3` on the
runner. Git and `aapt2` are needed for publishing APKs.
