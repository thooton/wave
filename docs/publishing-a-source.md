# Publishing a source

How to go from an empty folder to a Wave source that a phone can add, with
one app in it. About half an hour, no server of your own. The rules behind
each step are in the [source format](source-format.md); the CI route for
packages built by GitHub Actions is in [publishing](publishing.md).

You need Git, Python 3 and an account on a host that serves Git over HTTPS
(GitHub, GitLab, Codeberg and sourcehut work). For an APK you also need
`aapt2` (Android SDK build-tools), and a place to host the file at a
permanent URL, such as a GitHub release. Wave itself has no signing or
account requirements for sources.

## 1. Get the checker

```sh
git clone https://github.com/thooton/wave
alias wavesrc="$PWD/wave/tools/wavesrc/wavesrc"
```

`wavesrc` is plain Python 3 with no dependencies. Options are written
`--option=value`.

## 2. Make a repository for the source and nothing else

```sh
mkdir sams-apps && cd sams-apps
git init -b main
mkdir -p applications/hello
```

Use a repository of its own. Wave downloads the whole latest commit (up to 8
MiB, see [limits](source-format.md#how-wave-fetches-a-source)), so a source
inside a repository full of code or screenshots is refused: "That source is
over the 8 MB Wave fetches. Keep only the source there."

Layout, two levels: a section folder, then one folder per package.

```
wave.json
icon.png                      (optional, for the source)
applications/                 a section
  hello/                      a package
    package.json
    icon.png                  (optional, for the package)
```

Folder names are lowercase letters, digits and `-`. Anything that doesn't
fit is ignored without a message on the phone, so `Applications/` or
`applications/hello world/` would just not exist; `wavesrc lint` tells you.

## 3. Describe the source

`wave.json`:

```json
{
  "wave": 1,
  "name": "Sam's Apps",
  "description": "Small apps for the XP3800.",
  "maintainer": "Sam",
  "sections": { "applications": "Applications" },
  "featured": ["com.example.hello"]
}
```

Only `wave` and `name` are required. `name` and `maintainer` are what a
person sees when deciding whether to trust the source: the dialog reads
"Add it only if you trust" followed by the maintainer.

## 4. Host the binary and measure it

Upload the finished APK (or module zip) where its URL never changes and
never serves different bytes. On GitHub, create a release with a version tag
such as `v1.0.0`, and attach `hello-1.0.0.apk`. Never use a URL containing
`latest`, `main`, `master`, `nightly` or `?ref=`, and never replace a file
once it has been published: its size and SHA-256 are copied into your
manifest and Wave rejects anything else.

Read the three facts the manifest needs, from the file you uploaded:

```sh
sha256sum hello-1.0.0.apk
stat -c %s hello-1.0.0.apk                 # size in bytes (macOS: stat -f %z)
aapt2 dump badging hello-1.0.0.apk | head -1
```

The last line prints `name='com.example.hello'` and `versionName='1.0.0'`.
Those are the package's `id` and `version`. For a Magisk module, `id` and
`version` come from `module.prop` inside the zip
(`unzip -p mod.zip module.prop`).

## 5. Write the package

`applications/hello/package.json`:

```json
{
  "id": "com.example.hello",
  "type": "apk",
  "name": "Hello",
  "summary": "Says hello",
  "description": "A small app that says hello.",
  "author": "Sam",
  "version": "1.0.0",
  "download": {
    "url": "https://github.com/sam/hello/releases/download/v1.0.0/hello-1.0.0.apk",
    "size": 52301,
    "sha256": "<the 64 hex characters from sha256sum>"
  },
  "requires": { "sdk": 27 }
}
```

- `id` must be the APK's package name, exactly.
- `version` must be the APK's `versionName`. Wave compares versions as
  numbers, part by part: `1.10.0` is newer than `1.9.0`. Names such as
  `1.0-beta` do not fit; use plain numbers.
- `type` is `apk` or `module`. Modules need a rooted phone, and Wave says so
  on a stock one.
- `requires.sdk: 27` keeps it off phones older than Android 8.1.
  `requires.device: ["XP3800"]` restricts it to this model.
- Every field is listed, with its limits, in the
  [source format](source-format.md#packagejson).

## 6. Icons

Optional but they make the list readable. A PNG, square, at most 64 KB and at
most 1024 pixels; 96x96 is what Wave draws. With ImageMagick:

```sh
magick -size 96x96 xc:'#3366cc' icon.png            # a solid tile, to start
cp icon.png applications/hello/icon.png
file applications/hello/icon.png                     # PNG image data, 96 x 96
```

Without an icon Wave draws a coloured tile with the package's initial.

## 7. Check it

```sh
wavesrc lint .
wavesrc lint --download --aapt2=$(command -v aapt2) .
wavesrc verify .
```

- `lint` checks every file against the format, offline. Fix each line it
  prints that does not say `warning`.
- `lint --download` also downloads each binary and checks that its package
  name and version are the ones in the manifest, the way Wave will.
- `verify` checks the downloads only: HTTPS, the size and SHA-256 match, and
  the URL does not point at something that moves.

A clean run prints nothing; warnings are printed but don't fail the run.
The default source runs the same checks every night.

## 8. Publish the repository and test the address

```sh
git add -A && git commit -m "Sam's Apps"
git remote add origin https://github.com/sam/sams-apps.git
git push -u origin main
```

Check the address Wave will use, which is the repository's HTTPS URL without
a query:

```sh
curl -s -H 'Git-Protocol: version=2' \
  'https://github.com/sam/sams-apps.git/info/refs?service=git-upload-pack' \
  | head -c 120 | grep -a 'version 2'
```

If that prints a `version 2` line, the host speaks what Wave needs. Private
repositories need a token; see [source format](source-format.md#how-wave-fetches-a-source).

## 9. Add it on the phone

In Wave, open the **Sources** tab, press the menu key and choose **Add a
Source** (Home has the same row). The address field starts with
`https://github.com/`; type the rest with the keypad, which is slow, so
choose short names. Wave fetches the repository and shows what it found: the
name, the maintainer and how many packages. The dialog warns that the
source's packages can change anything on the phone; **Add Source** only for
someone you trust. Adding installs nothing: each install shows a plan to
confirm first.

Your package then appears under its section, in Search, and on the source's
page if listed in `featured`.

## 10. Release an update

1. Build the new APK with a higher `versionCode` and the same signing key.
   Android refuses an update signed by another key or with a lower code.
2. Upload it under a new tag and URL. Do not overwrite the old one.
3. In `package.json` change `version`, `download.url`, `size` and `sha256`,
   and put what changed first in `changes`:
   `"changes": [{ "version": "1.0.1", "date": "2026-12-01", "text": "Fixes the crash." }]`
4. Run the three checks, commit, push. A phone picks it up the next time Wave
   starts, or at once with **Refresh Sources** in a source's menu, and then
   shows an Update.

To publish from CI instead of editing by hand, use the `wavesrc publish`
Action, which writes this file from your build; see [publishing](publishing.md).

## The mistakes people make

| What you see | Cause | Fix |
| :--- | :--- | :--- |
| `lint` warns "mutable download URL selector 'latest'", and `verify` fails | The URL contains `latest`, `main`, `nightly`, a `?ref=`, or similar. Whatever it points to can change under the manifest | Use a release tag URL. Never re-upload a file; give each version its own URL |
| `icon.png: PNG must be at most 1024 pixels square`, `must be square`, or `larger than 64 KB` | The icon is the wrong shape or too big. The 1024 pixel limit exists because a 100-byte PNG can declare 65,000 pixels a side, which a phone cannot decode | Export it at 96x96 and shrink it (`optipng` or `pngquant`) |
| The source's page says "1 package can't be read" (press OK for the list) | The reasons are the field problem such as "summary is required", "Same id as <folder>", "Over 64 KB", "Not UTF-8 text" or "Its folder name isn't allowed". "Icons ignored" lists icons Wave won't draw: "Icon must be square", "Icon must be at most 1024 pixels square", "Not a readable PNG image", "Over 64 KB" | Fix the named folder; `wavesrc lint` on the same commit shows the file |
| A package is missing and the page says nothing | Wave doesn't report what it ignores: a folder with capitals or spaces, a `package.json` at the wrong depth, non-ASCII names | `wavesrc lint` on the same commit, then push |
| `duplicate id` | Two packages in one source have the same `id`. Wave keeps one | Give each package its own id. Across sources, the highest version of an id wins, then the source added first: pick ids you own |
| "The download is version 1.0.0, not 1.0.1." (or "is com.a.b, not com.c.d") | `version` or `id` in the manifest isn't what is inside the APK | `lint --download` finds this before your users do |
| The size or SHA-256 doesn't match | The file was rebuilt or replaced after the manifest was written | Recompute with `sha256sum` and `stat`, or publish under a new URL |
| "No Wave source at that address." | Wrong address, `wave.json` missing from the top of the default branch, or a host that only serves plain files rather than Git | Open the address in a browser (you should see the repository), then run the `curl` test above |
| "That source needs a sign-in." | The repository is private and the address has no token (or the token is wrong) | Make it public, or add it as `https://user:token@host/...` on the phone |
| "That address sends Wave elsewhere. Use the address it ends at." | The repository moved or renamed, or the address redirects (for instance to `www.` or to HTTPS from a vanity domain) | Use the final address; Wave never follows redirects |
| "That source is over the 8 MB Wave fetches. Keep only the source there." | Wave reads the whole latest commit, not just the package files, and the repository holds more than 8 MB (or more than 64,000 files). Files of 200 KB each count | Put the source in a repository of its own. `lint` warns past 6 MiB |
| "That source lists too much for Wave (12,000 files, 16 MB at most)." | More than 12,000 package and icon files, or 16 MB of them | Split it into several sources |
| "That source is empty." | The repository has no commits on its default branch | Push |
| "The source's details: name is required." (or another field), "The source's details are over 64 KB." or "The source's details can't be read." | `wave.json` has a bad field, is over 64 KB, or isn't valid JSON | `wavesrc lint` |
| "That source sent something Wave can't read." | The host's reply was not valid Git data | Try another host or report it |
| "That source's server doesn't work with Wave." | The server doesn't speak Git protocol v2 | Use GitHub, GitLab, Codeberg or sourcehut, or enable v2 |

## Before you share the address

- Add it to a phone yourself and install the package once.
- Keep signing keys and tokens out of the repository. A source is public
  text; nothing in it is secret, and manifests must not contain credentials.
- Say what your packages do to the phone. For a module that means what it
  changes and that it cleans up in `uninstall.sh`; see
  [source format](source-format.md#binaries).
