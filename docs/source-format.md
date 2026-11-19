# The Wave source format, version 1

A source is a git repository, reachable over HTTPS, that Wave can fetch. This
is what Wave reads from it. JSON Schemas for both files are in `../spec/`, and
`../spec/example-source/` is a small valid source. `wavesrc lint` checks a
source against all of this.

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
  one `<folder>/package.json`; anything else in the repository is ignored.
- Section and package folder names start with a lowercase letter or digit
  and may also contain `-`. The package folder name is only a name; identity
  is the `id` in the file. Manifest and icon paths are regular files, not symlinks.
- Files are UTF-8. A manifest is at most 64 KB and an icon at most 64 KB
  (65,536 bytes each):
  Wave fetches with a 256 KB blob limit and anything larger never reaches
  the phone.
- Icons are square PNGs, 96x96 recommended. Wave rounds the corners. A
  package or source without one gets a coloured tile with its initial.
- Wave reads the repository's default branch (`HEAD`).

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
| `name` | yes | Shown in Sources. At most 40 characters |
| `description` | no | One or two sentences |
| `maintainer` | no | A name |
| `homepage` | no | An `https://` URL |
| `sections` | no | Display names for section folders; the order of the keys is the order shown. Folders not listed come after, alphabetically, named by their folder with a capital |
| `featured` | no | Package ids to show on the source's page; the default source's are shown on the Wave tab |

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

| Field | Required | Meaning |
| :--- | :--- | :--- |
| `id` | yes | The package's identity. For `apk`: the Android package name. For `module`: the Magisk module id. Letters, digits, `.`, `_`, `-` |
| `type` | yes | `apk` or `module`. Wave lists a package of any other type as unavailable |
| `name` | yes | At most 30 characters |
| `summary` | yes | One line for list rows, at most 80 characters |
| `description` | no | Longer text. Plain paragraphs and the Markdown subset `common/ui`'s `Markdown` draws |
| `author` | no | A name |
| `homepage` | no | An `https://` URL |
| `license` | no | An SPDX identifier |
| `version` | yes | Dot-separated numbers, compared part by part as numbers. Must equal the binary's own version: an APK's `versionName`, a module's `version` in `module.prop` |
| `download.url` | yes | `https://`. Should be immutable: a new version gets a new URL |
| `download.size` | yes | Bytes |
| `download.sha256` | yes | Lowercase hex. Wave refuses a file that doesn't match |
| `requires.device` | no | Any of these must equal the phone's `ro.product.device` or `ro.product.model`. Otherwise the package is greyed |
| `requires.sdk` | no | Minimum `ro.build.version.sdk` |
| `requires.wave` | no | Minimum format version the Wave app must understand |
| `requires.firmware` | no | Build fingerprints the package was tested on. Advisory: a phone on another build sees a note, not a refusal |
| `depends` | no | Packages installed first. Each is an id, or `{ "id": "...", "min": "1.2.0" }` |
| `conflicts` | no | Ids that can't be installed alongside; Wave refuses and says which |
| `replaces` | no | Ids Wave removes as part of installing this one |
| `changes` | no | What's new, newest first, at most 10: `version` and `text` are required; `date` is an optional calendar date (`YYYY-MM-DD`) |

Rules for readers and writers:

- Integer fields accept whole numbers within the positive signed 64-bit
  range, up to 9,223,372,036,854,775,807.
- Unknown fields are ignored, so fields can be added without a new version.
- `wavesrc lint` validates the version 1 package types. Wave's reader can
  still list an unknown type or a package from a newer source as unavailable.
- A manifest that fails to parse or lacks a required field hides that one
  package, not the source.
- The same `id` in several sources is one package; the highest version wins,
  then the source added first.

## Binaries

**`apk`**: an APK for API 27. After download Wave checks its package name is
`id` and its `versionName` is `version`. Android itself requires an update to
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

Hand-written manifests are fine. For packages built by CI, the `wavesrc
publish` Action in `thooton/wave` writes the manifest and icon into a source
from a static `wave/package.json` beside the code plus the built binary (see
"Publishing" in `design.md`).
