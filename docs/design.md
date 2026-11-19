# Wave: design

Wave is a package manager and modding framework for the Sonim XP3800, in the
role Cydia had on a jailbroken iPhone. The user jailbreaks the phone from a
web page, which leaves a Wave app on the launcher. In Wave they add sources
(git repositories of package manifests), browse and search them, and install
apps and system mods. Wave keeps what it installed up to date.

This file is the whole picture. The details that other people build against
are split out: `source-format.md` (what a source is), `jailbreak.md` (the web
jailbreak), `decisions.md` (what was decided, what is assumed, what is open),
and `tasks/` (the work, one brief per piece).

## 1. The three repositories

| Repo | Local folder | Holds |
| :--- | :--- | :--- |
| `thooton/wave` | this one (`sonim_wave` today) | The Wave app, the website (jailbreak, QR setup generator), the source format spec, and the tools sources are checked and published with |
| `thooton/wave_packages` | `../sonim` today, renamed | The source code of every package we ship: the Gradle apps, `common/`, and the three system mods under `mods/`. CI builds them and attaches the binaries to GitHub releases |
| `thooton/wave_repository` | new | The default source. Only manifests and icons. Each manifest points at a release asset in `wave_packages`. Written by `wave_packages` CI |

Anyone else's source is the same shape as `wave_repository`: a git repo with
manifests that point at binaries hosted anywhere over HTTPS.

```
 wave_packages            wave_repository              the phone
 (code, CI)               (manifests, icons)           (Wave app)
     |  build                    ^                         |
     |  release asset  ----------+-- package.json points   |  git fetch (depth 1)
     |  (immutable URL)          |   at the asset          |<---------------------
     +---- CI commits manifest --+                         |  download asset on
                                                           |  demand, check sha256,
                                                           |  install through su
```

## 2. Sources and packages

Normative text is in `source-format.md`; this is the shape.

```
wave.json                 name, description, sections
icon.png
applications/             a section (Cydia's "Sections")
  mail/
    package.json          id, type, name, version, download {url,size,sha256}, ...
    icon.png
system/
  messages-improved/
    package.json
    icon.png
```

A source is two levels deep: section folders, then package folders. Nothing
else in the repo is read, so a source can carry a README or CI files.

Two package types in version 1:

- **`apk`**: an ordinary app. Its `id` is the Android package name. Installed
  with `pm install` through root; no reboot.
- **`module`**: a Magisk module zip (the standard layout: `module.prop`,
  `system/...`, `service.sh`, `customize.sh`). Its `id` is the Magisk module
  id. Installed with `magisk --install-module`; takes effect after a reboot.
  This is how the system mods ship, because they replace platform-signed
  system APKs and can only do so as a systemless overlay.

A package Wave doesn't understand (an unknown `type`, or a format version
above its own) is listed but can't be installed, with "Needs a newer Wave".
That leaves room for later types (on-device patching of the phone's own stock
APK, settings tweaks, themes) without breaking old phones.

### Identity, versions, several sources

- A package is identified by `id`. The same `id` in two sources is the same
  package; Wave offers the highest version, and on a tie the source added
  first.
- Versions are dot-separated numbers compared part by part (the Updater's
  `Version`).
- "Installed" is read from the phone, not from Wave's records: an `apk` is
  installed if PackageManager has the package; a `module` if
  `/data/adb/modules/<id>/module.prop` exists (read through `su`). So apps
  already on a phone are adopted on first run, as long as their package
  names stay what they are today.
- Wave also keeps a receipt per package it installed (source, version,
  time), so the Installed tab can still show a package whose source was
  removed.

### Dependencies

`depends` (ids, optionally with a minimum version), `conflicts` (ids), and
`replaces` (ids removed when this installs; how `messages_improved` takes
over from a phone's old `messages_argon` module, and how Wave removes the
Updater and Installer). Wave resolves them into an ordered plan and shows it
before doing anything, as Cydia's confirm screen does. No version solver: a
dependency is satisfied by whatever the best candidate is, or the install is
refused with the reason.

### Compatibility

`requires` can name devices (`XP3800`), a minimum SDK and a minimum Wave. A
package that doesn't fit the phone is greyed with the reason. Firmware build
is **not** a hard requirement: the mods ship as one binary and are expected
to work across the XP3800's builds, which differ very little. `requires.firmware`
exists but only produces a "not tested on this firmware" line on the details
page. The safety net for a mod that does break a phone is the boot watchdog
(section 5), not a refusal up front.

### Trust

Adding a source means trusting its maintainer with root, since modules run as
root. Wave says so when a source is added. The chain is: HTTPS to the git
host, then the SHA-256 in the manifest pins the binary. Only `https://` is
accepted, for sources and downloads. APK signatures aren't a trust anchor (our
shared key is a public debug keystore); Android still refuses an update signed
with a different key, and Wave reports that plainly instead of failing
silently. Signed sources are left for a later format version.

## 3. Fetching a source: git on a phone with no git

`app/core/.../git/` is a small git client in plain Java (no dependencies,
host-testable), speaking **protocol v2 over smart HTTP**, fetch only:

1. `GET <url>/info/refs?service=git-upload-pack` with `Git-Protocol: version=2`.
2. `ls-refs` for `HEAD`. If the commit is the one already stored, the source
   hasn't changed and the refresh is done after one small request.
3. `fetch` with `want <commit>`, `deepen 1`, and `filter blob:limit=262144`
   when the server offers `filter`. That is "clone the top level": one
   commit, its trees, and only small blobs.
4. Parse the packfile (zlib via `java.util.zip.Inflater`, offset and ref
   deltas, SHA-1 check of every object), walk the tree, and write
   `wave.json`, `icon.png`, and each `<section>/<package>/{package.json,icon.png}`
   to `files/sources/<source>/tree/`. Everything else in the pack is dropped.
5. The new tree replaces the old one atomically (write beside, rename), so a
   refresh that dies half way leaves the old copy.

Limits: a pack over 8 MB is refused; a blob over the filter limit never
arrives, so manifests and icons must be small (the format says how small).
Works with GitHub, GitLab, Codeberg/Forgejo and sourcehut. Protocol v0/v1 and
dumb HTTP are not supported. A private source uses `https://user:token@host/...`,
sent as Basic auth.

Every refresh refetches the whole tree (no `have` negotiation), which is fine
at this size and keeps the client small.

## 4. The Wave app

`com.wave.xp3`, built from the Updater (`wave_packages/updater`): same
App Store look, same `:core` + `:app` split, same rules (Java 8, API 27, no
third-party dependencies, 240x320, d-pad only). It keeps its own copy of
`common/` (see `decisions.md`).

### Tabs

A bottom tab bar as Cydia's, four tabs. Left/Right moves between tabs from
anywhere a row doesn't use them; Up/Down moves in the list; OK presses; Back
goes up a level; Menu opens the context menu, as the Updater does now.

| Tab | Shows |
| :--- | :--- |
| **Wave** | What Cydia's home is: a welcome header, Featured (from the default source's `featured`), What is Wave, Add a Source, device facts (model, firmware, Magisk and root state, storage), a Reboot row when one is pending, community links |
| **Sources** | "All Sources", then each source with its icon, name and URL. Refresh and Add on the bar. A source opens to its sections (All Packages, then each folder with a count), a section to its packages, a package to its details |
| **Installed** | The Updater's screen. Updates first with Update All, then everything installed, alphabetically. A banner when a reboot is pending |
| **Search** | A field and live results across every source, matching name, summary and description |

Cydia's Changes tab is left out; what's new is on each row and on the details
page, from the manifest's `changes`.

### Package details

Icon, name, version, size, author, source and section; the capsule (GET,
UPDATE, OPEN, or REMOVE from the menu, with Reinstall); a "System mod, needs a
reboot" line for modules; compatibility notes; description; what's new;
depends and conflicts.

### Doing things: the queue

GET, UPDATE and REMOVE add to a queue. Confirm shows the plan (including
dependencies it pulled in and packages it replaces). Then, one at a time:
download to the cache, check size and SHA-256, check the file is what the
manifest says (an APK's package name and version, a zip's `module.prop` id and
version), install through root, record the receipt. At the end: Done, or
Reboot Now / Later when a module changed. Wave updates itself last, from a
detached root shell, because Android kills the app being replaced.

### Root

Wave needs root and says so: with no working `su` it shows one screen that
points at the jailbreak site. It keeps a single `su` shell open and sends it
scripts; `:core` builds the scripts and parses their output, so that logic is
tested on a desktop JVM. Operations:

| Operation | How |
| :--- | :--- |
| Install or update an apk | `pm install -r` from the root shell, then `cmd package compile -m speed` |
| Remove an apk | `pm uninstall` |
| Install or update a module | `magisk --install-module <zip>`; pending until reboot |
| Remove a module | `touch /data/adb/modules/<id>/remove`; pending until reboot |
| Read module state | list `/data/adb/modules` and `modules_update` with each `module.prop` and its `disable`/`remove`/`update` flags |
| Reboot | `svc power reboot` |

The jailbreak pre-grants Wave in Magisk's policy database, so the user never
has to answer a superuser prompt with a d-pad.

### Storage on the phone

```
files/sources.json                 the list of sources (url, name, HEAD commit, last refresh)
files/sources/<source>/tree/       the fetched manifests and icons
files/receipts.json                what Wave installed
cache/downloads/                   binaries in flight, deleted after install
/data/adb/wave/                    root-owned state for the boot watchdog
```

## 5. Safety: the boot watchdog

A mod replaces the launcher or the telephony provider; a bad one can leave a
phone that doesn't reach its home screen. The mods fail safe on their own
(see the launcher's fallbacks), and on top of that Wave installs two small
scripts into Magisk's `post-fs-data.d` and `service.d`:

- When Wave installs or updates a module it lists it in
  `/data/adb/wave/pending`.
- After a boot completes and stays up for a minute, `service.d` clears the
  list.
- If a boot starts while the list is still there from a previous attempt (the
  last boot never completed), `post-fs-data.d` disables the listed modules
  before Magisk mounts them, and the phone comes up without them. Wave then
  says which mod was turned off and why.
- If boot hasn't completed after a few minutes, `service.d` reboots, which
  triggers the case above.

This rests on the order of Magisk 24.0's early boot, checked in its source
(`native/jni/core/bootstages.cpp`, `post_fs_data`): the scripts in
`post-fs-data.d` run first, and only then are modules collected, skipping any
with a `disable` file.

Three more ways out, in rising order of effort:

- **Magisk's safe mode.** Holding Volume Down for three seconds as the phone
  boots makes Magisk 24.0 disable every module (same source file,
  `check_key_combo`). No computer needed. Wave notices disabled modules and
  offers to turn them back on one at a time. To be confirmed on the XP3's
  keys.
- **Rescue** on the website: disable modules over adb.
- **Restore** on the website: flash the stock boot image saved during the
  jailbreak over EDL. Since `/system` is never written, this is always a
  full way back.

## 6. The jailbreak

A static web page (GitHub Pages from this repo) that drives the phone over
WebUSB in Chrome: adb for the Android side, Sahara and Firehose for EDL. It
automates `../rphone/ROOTING.md` end to end: back up the boot image over EDL,
patch it **on the phone** with Magisk 24.0's own ARM binaries over adb, flash
it over EDL, verify, then install the Magisk app and Wave and grant Wave
root. The user's stock boot image is saved to their computer. Steps, checks
and the platform caveats are in `jailbreak.md`.

The same site hosts the landing page and the QR setup generator (section 8).

## 7. Publishing: from a push to a phone

In `wave_packages`:

1. Each package has a `wave/` folder beside its code: `package.json` with the
   fields that don't change per release (id, type, name, summary,
   description, author, section, depends...) and `icon.png`.
2. CI builds everything on each push to master. For each package whose
   version has no release yet, it creates the release `<name>-<version>` and
   uploads the binary. Release assets are never replaced, so a manifest can't
   point at a file that changed underneath it.
3. CI then runs `wavesrc publish` (from `thooton/wave`, as a GitHub Action):
   it merges the static fields with version, URL, size, SHA-256 and the
   newest changes (from `git log`, as `.github/manifest.py` does today), and
   commits `<section>/<name>/package.json` and `icon.png` into
   `wave_repository`.
4. Phones see it on their next refresh.

So shipping is still: bump the version, push to master. `wave_repository` is
never edited by hand for our packages. Someone running their own source can
use the same Action, or write manifests by hand and run `wavesrc lint`.

## 8. What goes in the default source

| Package | Type | Notes |
| :--- | :--- | :--- |
| Wave | apk | Updates itself |
| Books, Dictionary, List, Música, New Yorker, Print, Serve, Video, Weather | apk | As they are |
| Canvas, Charge, Mail, Places, Authenticator, Flashcards | apk | Reworked so they don't need a baked Delta login (below) |
| Messages Improved | module | From `messages_argon`: BMms, TelephonyProvider, ContactsProvider |
| Launcher Improved | module | From `launcher_argon`; absorbs AppDrawer |
| Music Improved | module | From `music_argon` |

Out: HBSA. Source and Tablet move to a separate repo and source of their own.
Their source and releases stay private (task 16). AppDrawer, Updater and
Installer are retired. `camera_argon` is not part of this yet. The SQLite
upgrade module is a candidate (`decisions.md`).

### Apps that needed Delta

Today eight apps are built with a Delta login baked in. In Wave they ship
with none and the user brings their own credentials: Tesla tokens for Charge,
a Gmail token for Mail, a Maps key for Places, and so on. Typing those on a
T9 keypad is not an option, so:

- A shared setup flow in `common/` imports a small, versioned JSON payload
  naming the app and its settings. Task 14 tests camera scanning; use
  setup-file import if scanning can't be validated.
- A generator page on the Wave site builds the setup file or QR code in the
  browser from what the user pastes in. Nothing is sent to a server.
- The same payload can carry a Delta address and token instead. Each app
  talks to its service through one interface with two implementations
  (direct, and through Delta), so the code is the same either way and the
  Delta path simply isn't advertised.

This is `wave_packages` work and is specified in its own brief
(`tasks/14-app-setup-codes.md`). If scanning is supported, Wave reuses it
for adding a source by QR code.

## 9. Renaming the mods

`messages_argon`, `launcher_argon` and `music_argon` become Messages
Improved, Launcher Improved and Music Improved, living at
`wave_packages/mods/{messages,launcher,music}` with one shared toolkit
(`mods/tools`: apktool wrapper, `splice_apk.py`, `smalipatch.py`, packaging)
instead of three copies. In the code, `Argon` becomes `Wave` everywhere:
classes (`WaveTray`), log tags (`WaveMms`), the build flag (`WAVE_RCS`), files
on the phone (`wave_tray.txt`), preferences. Module ids become
`messages_improved`, `launcher_improved`, `music_improved`, each `replaces`
its old id. Anything stored on a phone under an old name is migrated on first
run (read the old name when the new one is missing), so a phone that already
runs the mods keeps its tray layout and settings. The stock APKs each mod
patches are committed, so CI can build the mods.

## 10. Order of work

`tasks/README.md` has the briefs and their dependencies. Work in parallel
where dependencies allow; advance when the brief's checks pass and record
what was verified. Screen implementation starts once the mockups pass the
frame and d-pad checklist and the app core is ready. The default source is
populated when `wave_packages` CI first publishes into it.
