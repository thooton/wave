# The Wave source format, version 1

A source is a git repository, reachable over HTTPS, that Wave can fetch. This
is what Wave reads from it. JSON Schemas for both files are in `../spec/`, and
`../spec/example-source/` is a small valid source. `wavesrc lint` checks a
source against all of this. [Publishing a source](publishing-a-source.md) is
the walkthrough from an empty repository.

## Layout

```
wave.json                    required
icon.png                     optional
<section>/                   one folder per section
  <folder>/                  one folder per package
    package.json             required
    icon.png                 optional
```

- Exactly two levels. A top-level folder is a section if it contains at least
  one `<folder>/package.json`; anything else in the repository is ignored. Wave
  does not report what it ignores: a folder with an uppercase letter or a
  space in its name, a `package.json` one level too deep or too shallow, or a
  non-ASCII name is simply absent from the phone, and other large files are
  never mentioned. `wavesrc lint` reports the folder names.
- Section and package folder names start with a lowercase letter or digit
  and may also contain `-`. The package folder name is only a name; identity
  is the `id` in the file. Manifest and icon paths are regular files, not symlinks.
- Files are UTF-8. A manifest is at most 64 KB and an icon at most 64 KB
  (65,536 bytes each). Wave asks the server to leave out files over 256 KB
  and drops anything over 64 KB it still receives. A dropped `package.json`
  hides that package ("Over 64 KB" on the source's page, see below) and a
  dropped `wave.json` gives "The source's details are over 64 KB."
- Icons are square PNGs, 96x96 recommended, at most 1024x1024 (a small file can
  declare far more pixels than a phone can decode; a larger icon is ignored).
  Wave rounds the corners. A package or source without one gets a coloured
  tile with its initial.
- Wave reads the repository's default branch (`HEAD`).

## How Wave fetches a source

The address is `https://host/path`, with no `?` or `#`. Wave does not follow
redirects: a moved or renamed repository says "That address sends Wave
elsewhere. Use the address it ends at." Trailing slashes are ignored, but
`.../repo` and `.../repo.git` are two different sources to Wave.

The server must speak Git's smart HTTP protocol, version 2, with SHA-1
objects, and allow a shallow fetch. Static file hosting (the "dumb"
protocol) does not work and shows "No Wave source at that address." GitHub,
GitLab, Codeberg and sourcehut were tried (task 03's note). Check any other
host with:

```sh
curl -s -H 'Git-Protocol: version=2' 'https://HOST/OWNER/REPO.git/info/refs?service=git-upload-pack' | head -c 120 | grep -a 'version 2'
```

It must print a line containing `version 2`. Nothing, or an HTML page, means
Wave can't use that address.

Wave fetches the whole commit at depth 1, not just the manifests. What the
server sends counts against these limits, so a source that lives in a repository
with other content can fail however small its manifests are ("That source is
over the 8 MB Wave fetches. Keep only the source there."):

| Limit | Value |
| :--- | :--- |
| Server reply (the pack) | 8 MiB |
| Objects in the pack | 64,000 |
| Manifests and icons together | 16 MiB, at most 12,000 files |
| Sources on one phone | 128 |
| Download size of a package | 512 MiB |

If the server supports Git's `filter`, files over 256 KB are left out of the
pack; otherwise everything is in it. Keep a source in its own small
repository, as the default source does. `wavesrc lint` warns when the files a
fetch would carry add up to more than 6 MiB.

A private repository's address can carry the reader's token
(`https://user:token@github.com/OWNER/REPO`, entered on the phone only), and
manifests must never contain credentials; see
[decisions](decisions.md#open) item 4.

## wave.json

```json
{
  "wave": 1,
  "name": "Wave",
  "description": "Apps and system mods for the Sonim XP3800.",
  "maintainer": "thooton",
  "homepage": "https://github.com/thooton/wave_repository",
  "sections": {
    "applications": "Applications",
    "system": "System"
  },
  "featured": ["com.mail.xp3", "messages_improved"]
}
```

| Field | Required | Meaning |
| :--- | :--- | :--- |
| `wave` | yes | Format version. `1`. A source with a higher number is shown as needing a newer Wave |
| `name` | yes | Shown in Sources and in the trust dialog. At most 40 characters |
| `description` | no | One or two sentences, at most 200 characters. Checked, not shown by Wave 0.7 |
| `maintainer` | no | A name, at most 60 characters. Shown in Sources and in the trust dialog |
| `homepage` | no | An `https://` URL. Checked, not shown by Wave 0.7 |
| `sections` | no | Folder name to display name (1 to 30 characters); a key that isn't a valid folder name makes `wave.json` unreadable. The order of the keys is the order shown. Folders not listed come after, alphabetically, named by their folder with a capital |
| `featured` | no | At most 10 package ids to show on the source's page; the default source's are shown on the Wave tab. An id the source doesn't have is skipped, and `wavesrc lint` warns |

## package.json

```json
{
  "id": "messages_improved",
  "type": "module",
  "name": "Messages Improved",
  "summary": "Group MMS in the right thread, faster receiving, crash fixes",
  "description": "The stock Messages app and its provider, fixed.\n\n...",
  "author": "thooton",
  "homepage": "https://github.com/thooton/wave_packages/tree/master/mods/messages",
  "version": "1.2.0",
  "download": {
    "url": "https://github.com/thooton/wave_packages/releases/download/messages-improved-1.2.0/messages-improved-1.2.0.zip",
    "size": 4107233,
    "sha256": "9f2c...e1"
  },
  "requires": { "device": ["XP3800"], "sdk": 27 },
  "depends": [],
  "conflicts": [],
  "replaces": ["messages_argon"],
  "changes": [
    { "version": "1.2.0", "date": "2026-11-18", "text": "Prefetch recent messages when the inbox is opened." }
  ]
}
```

The `...` in `description` and `sha256` stand for text left out here; the
real files in [`spec/example-source`](../spec/example-source) are complete.
The smallest valid manifest, with only the required fields (`size` and
`sha256` must be those of your real file):

```json
{
  "id": "com.example.hello",
  "type": "apk",
  "name": "Hello",
  "summary": "Says hello",
  "version": "1.0.0",
  "download": {
    "url": "https://github.com/example/hello/releases/download/v1.0.0/hello-1.0.0.apk",
    "size": 52301,
    "sha256": "0000000000000000000000000000000000000000000000000000000000000000"
  }
}
```

| Field | Required | Meaning |
| :--- | :--- | :--- |
| `id` | yes | The package's identity. For `apk`: the Android package name. For `module`: the Magisk module id. Letters, digits, `.`, `_`, `-`; at most 100 characters |
| `type` | yes | `apk` or `module`. Wave lists a package of any other type as unavailable |
| `name` | yes | At most 30 characters |
| `summary` | yes | One line for list rows, at most 80 characters |
| `description` | no | Longer text, at most 4,000 characters. Plain paragraphs and the Markdown subset `common/ui`'s `Markdown` draws. Searched as well as shown |
| `author` | no | A name, at most 60 characters |
| `homepage` | no | An `https://` URL. Checked, not shown by Wave 0.7 |
| `license` | no | An SPDX identifier, at most 40 characters. Checked, not shown by Wave 0.7 |
| `version` | yes | Dot-separated numbers, compared part by part as numbers (`1.10` is newer than `1.9`, and `1.0` equals `1.0.0`). Must be the binary's own version in that comparison: an APK's `versionName`, a module's `version` in `module.prop` |
| `download.url` | yes | `https://`, with no user name or password. Must be immutable: a new version gets a new URL. A `latest`, `main` or `?ref=` style URL passes `wavesrc lint` with a warning and fails `wavesrc verify` |
| `download.size` | yes | Bytes, a positive whole number; Wave downloads at most 512 MiB. The file must be exactly this long |
| `download.sha256` | yes | Lowercase hex. Wave refuses a file that doesn't match |
| `requires.device` | no | At least one entry if present. Any of these must equal the phone's `ro.product.device` or `ro.product.model`. Otherwise the package is greyed |
| `requires.sdk` | no | Minimum `ro.build.version.sdk` |
| `requires.wave` | no | Minimum format version the Wave app must understand |
| `requires.firmware` | no | Build fingerprints the package was tested on. Advisory: a phone on another build sees a note, not a refusal |
| `depends` | no | Packages installed first. Each is an id, or `{ "id": "...", "min": "1.2.0" }` |
| `conflicts` | no | Ids that can't be installed alongside; Wave refuses and says which |
| `replaces` | no | Ids Wave removes as part of installing this one |
| `changes` | no | What's new, newest first, at most 10: `version` and `text` (at most 500 characters) are required; `date` is an optional calendar date (`YYYY-MM-DD`) |

Rules for readers and writers:

- Integer fields accept whole numbers within the positive signed 64-bit
  range, up to 9,223,372,036,854,775,807.
- Unknown fields are ignored, so fields can be added without a new version.
- `wavesrc lint` validates the version 1 package types. Wave's reader can
  still list an unknown type or a package from a newer source as unavailable.
- A manifest that fails to parse or lacks a required field hides that one
  package, not the source. From Wave 0.7.17 the source's page says "N
  packages can't be read"; OK lists each folder with its reason, up to 20,
  then "Icons ignored" (an icon problem alone doesn't hide a package). The
  reasons are the reader's own words: the field problem (`summary is
  required`), `Same id as <folder>`, `Over 64 KB`, `Not UTF-8 text`, `Its
  folder name isn't allowed`. The trust dialog shows "N packages (M can't be
  read)". Run `wavesrc lint` before every push anyway: it names the file and
  the line of the problem.
- The same `id` in several sources is one package; the highest version wins,
  then the source added first. Nothing ties an id to the source that first
  offered it, so a source added later can offer a higher version of a package
  already installed from another. An APK still has to be signed with the key
  of the installed copy; a module has no such check. Pick ids that only you
  control (a reverse domain you own for APKs).
- Two packages with the same `id` in one source: the first in folder order is
  used and the other is dropped. `wavesrc lint` reports it.

## Binaries

**`apk`**: an APK for API 27. After download Wave checks its package name is
`id` and its `versionName` is `version` (as versions: `1.0` is `1.0.0`),
and says "The download is version X, not Y." or "The download is com.a.b, not
com.c.d." if not. `wavesrc lint --download` makes the same checks before you
publish. Android itself requires an update to
be signed with the key of the copy on the phone and not to have a lower
`versionCode`; so keep one key and always raise `versionCode`.

**`module`**: a Magisk module zip in the standard layout (`module.prop` at
the top; installable by `magisk --install-module`, Magisk 24.0). After
download Wave checks `module.prop`'s `id` is the package's `id` and its
`version` is `version`. `versionCode` in `module.prop` must rise with each
release. A module must not refuse to install by firmware fingerprint, and
must clean up after itself in `uninstall.sh` if it changed anything outside
its overlay.

## Publishing

Hand-written manifests are fine; [Publishing a source](publishing-a-source.md)
walks through one. For packages built by CI, the `wavesrc
publish` Action in `thooton/wave` writes the manifest and icon into a source
from a static `wave/package.json` beside the code plus the built binary (see
"Publishing" in `design.md`).
