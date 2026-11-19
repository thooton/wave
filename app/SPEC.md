# Wave

## Purpose

Wave is the package manager for the Sonim XP3 (see `../docs/design.md`).
This file says how the app behaves today, and changes with it. At 0.1.0
(brief 04) Wave is the Updater under a new name: one screen, drawn as the
App Store's Updates page, listing the apps in the rolling `latest` release
of thooton/sonim, with UPDATE, GET and OPEN. That screen becomes the
Installed tab; briefs 05 to 07 change where its data comes from (sources),
not how it looks. Nothing updates by itself: the phone only changes when
the user asks.

## Not wanted

- A blue "more" on each row, or Left/Right moving to it (dropped in 1.2.4).
  What's New lives in the menu only.
- Asking the GitHub API for release notes (dropped in 1.3.0); everything
  comes from `manifest.json`. `versions.txt`/`packages.txt` exist only for
  Updaters older than 1.3.0, and Wave ignores them.
- Any text telling the user which key to press, or explaining the screen
  (`wave_packages` DESIGN.md rule 7).
- Caching the release: Check Again must see a release made a minute ago.
- Using the XP3's own installer as the normal path: it refuses anything not
  Sonim-signed, so without root APKs go to Installer (`wave_packages/installer`);
  once root has worked on the phone they install through it (brief 06).

## Screens

### The list (the only screen until brief 07)

**Bar** (near-white, grey hairline under it): "Updates" centred. On the
right, **Update All**, shown only while at least one app is in Available
and not already queued or working. On the left, a small spinner while a
check runs over a list already on screen.

**List**: one row per app in the release, in three groups, each led by a
large bold heading, in this order:

1. **Available**: on the phone, with an older `versionName` than the
   release. Versions compare part by part, numbers as numbers (1.10.0 is
   newer than 1.9.0; 1.0 equals 1.0.0; 1.0.0-beta is older than 1.0.0).
2. **Not Installed**: no app with that package on the phone.
3. **Installed**: the release's version, or newer (a hand-installed build).

A group with no apps has no heading. Within a group, rows are sorted by
name, ignoring case.

Each row: a rounded-square icon (the installed app's own; a coloured tile
with its initial for one not on the phone), the name (the phone's label if
installed, else the manifest's; at most 2 lines, then "…"), under it the
date of the newest change ("Today", "Yesterday", else "Oct 17, 2026"), and
on the right a capsule: **UPDATE** (Available), **GET** (Not Installed) or
**OPEN** (Installed). Under that, full width, the newest change's text, at
most 2 lines, then "…". An app whose manifest has no notes shows
"Version <release version>" in place of the date and no text below.

While an app is downloading, its capsule is a ring that fills with the
percentage, with a stop square in it. While it is waiting its turn or
installing, the ring spins.

The highlighted row greys and its capsule fills blue with white text. The
last row has no hairline under it.

**Keys on a row**

| Key | Does |
|---|---|
| Up / Down | Move one row. Down on the last row stays there. Up on the top row moves to Update All if it is shown, otherwise stays. |
| Left / Right | Nothing; focus never leaves the list. |
| OK (released) | Presses the capsule: UPDATE or GET queues the app; OPEN launches it; on a spinning ring (an app waiting its turn) takes it out of the queue. On the app that is downloading or installing, nothing. |
| OK (held) | Opens the menu beside the row; releasing afterwards does not also press. |
| Menu (on release) | Opens the menu beside the row. |
| Back | If nothing is queued or working, closes the app. Otherwise toasts "Updating in the background" and sends the app to the back; the work carries on. |

**Keys on Update All**: OK queues every app in Available that isn't
already queued, and returns focus to the list. Down returns to the list.

**Menu** (a popup beside the highlighted row, items in this order, each only
when it applies):

- **Get**: app not installed, not queued.
- **Update**: app in Available, not queued.
- **Open**: app installed, and it isn't Wave itself.
- **Reinstall**: app up to date or newer, not queued.
- **What's New**: always.
- **Update All (n)**: when more than one app is in Available and not queued;
  n is how many.
- **Check Again**: unless a check is running.

Back closes the menu without doing anything.

**After a check answers** (on opening, or Check Again), the highlight goes
to the top row. Any other redraw (progress, an install finishing, returning
to the app) keeps the highlight on the same app, wherever it now sorts.
Returning to the app (`onResume`) re-reads what is installed, so an app
installed or removed elsewhere moves group, but does not re-check the
release.

### Message (in place of the list)

Shown when there are no rows to list:

- While the first check runs: a spinner in the middle, no text.
- The check failed: **Cannot Check for Updates**, and under it the reason
  (see Behaviour). OK retries the check.
- The release lists no apps: **No Apps**.

Menu or holding OK opens a menu with only **Check Again** (absent while a
check runs). Back closes the app.

### What's New (dialog, from the menu)

The app's icon and name (at most 2 lines), under it "This phone has
<version>" if installed, else "Not installed". Then **What's New** in bold,
"Version <release version>" with the newest change's date on the right,
then up to 10 changes, newest first: the first just its text, each later
one under a small line "Version <v> · <date>" (just the date if the commit
named no version). Up/Down scroll when it is longer than the screen. OK on
**Done** or Back closes it, leaving the highlight where it was.

## Behaviour

**The release.** On opening, Wave fetches only
`https://github.com/thooton/sonim/releases/download/latest/manifest.json`.
Every request uses its own connection and a never-before-used query, so
Check Again sees a release made seconds ago just as reopening does. Only
https; no other host but GitHub's redirect to its file host.

**What it offers.** An app is listed only if its manifest name is letters,
digits, `_` or `-` (at most 64), and it has a version and a valid package.
A manifest whose `schema` isn't 1 is "The release's manifest is
unreadable."

**Errors**, in words: "No network.", "The connection timed out.",
"Could not connect.", "The connection was lost.", "The secure connection
failed." (only a failed handshake or certificate; a connection dropped
during a download is "The download was cut short."), "The release has no
manifest.json.", "GitHub answered <code> for <file>.". A failed check with
no list shows the message screen; with a list already showing, the list
stays and a toast says "Couldn't check: <reason>".

**Updating.** Queued apps go one at a time, in the order chosen, except
that Installer goes after all other apps and Wave itself last. For
each:

1. Download `<app>.apk` into the app's own `files/apks/` (through a
   `.part` file), with the ring showing the percentage.
2. Check it against the manifest's size and SHA-256 ("The download was cut
   short.", "The download isn't the size the release gives.", "The download
   doesn't match the release.").
3. Check the APK is the package the release names, signed with the same key
   as the phone's copy, and not an older versionCode ("The download is X,
   not Y.", "The phone's copy is signed with a different key.", "The
   release's X is older than the phone's Y.").
4. Once root has worked here, `pm install` it from the root shell (Wave
   itself from a detached one that starts Wave again). Otherwise, or when
   root is refused, hand it to Installer with `package` set, so Installer
   refuses any other package. Without Installer, fall back to the system
   installer. Before
   updating itself, Wave toasts "Wave closes to update itself".
5. On success, toast "Updated <app> to <v>", "Installed <app> <v>" or
   "Reinstalled <app> <v>"; the row moves to Installed with OPEN.

A failure in steps 1–4 shows an alert titled "<app> wasn't updated" (or
"wasn't installed" / "wasn't reinstalled") with the reason, and the next
queued app starts. A cancelled install with no reason is a toast with the
same title. When Installer gives no answer (as when it replaces itself),
Wave looks at the phone's copy about 2.5 s later and counts it a
success if it now has the release's version. No installer at all: toast
"No installer on this phone".

**Storage.** Nothing is kept between runs: no settings, no cache. Each
downloaded APK is deleted when its install ends, and any left by an earlier
run are deleted on opening. The queue does not survive the app being
killed.

**Permissions.** Internet, request-install-packages (system installer
fallback only), and Installer's signature permission. No storage or SD card
access.

**Timed.** Connect timeout 20 s, read timeout 60 s. The 2.5 s settle after
a silent installer. Nothing runs in the background except a download the
user started.

## Source core (briefs 03 and 05)

The source screens can use `com.wave.xp3.core.sources` without Android calls:

- Keep one `SourceStore(filesDir)`. `preview(url)` fetches without persisting;
  show its manifest name, maintainer and valid package count in the root-trust
  dialog, then call `add(preview)` only on approval. `refresh(id)` and
  `refreshAll()` keep the last good tree on failure. The built-in Wave source
  is first and cannot be removed. Run fetches, mutations and `index()` on a
  worker, not the UI thread.
- `Index.sources()` / `source(id)` expose per-source metadata, validation
  issues, package rows, sections and counts. `all()`, `get(packageId)`,
  `sections()` and `search(query)` use merged candidates: highest version,
  then earliest source, including the winner's type. Search ranks name
  matches before summary or description. A blank query has no results.
- `Compat.of(entry, phone)` separates a blocking reason from an advisory
  firmware note. `Status.of(entry.manifest, phoneState)` reads actual APK or
  Magisk state through `Status.Phone`, including pending changes.
- `sources.json` is the atomic pointer to each immutable cached generation in
  `sources/<source-id>/tree/<HEAD>/`. Rebuild the index after refresh or removal;
  old icon paths may no longer exist. A missing or invalid icon means use the
  initial tile. Decode icons at display size, not at the source's pixel size.
  The catalog's URL is safe to display; `sources.json` can contain Basic-auth
  credentials and must be excluded from backup.

### Android data wiring (brief 07)

`com.wave.xp3.data.SourceController` publishes immutable `WaveData.Snapshot`
values to the screens on the main thread. Source fetch and validation run on
two bounded workers; search has its own worker and only delivers the latest
query. Cached rows stay available during refresh, and one failing source does
not clear another's rows. An empty cache with a failed refresh exposes a retry
message instead of an empty catalog.

The Installed tab includes APKs found through a known package ID, Wave itself,
and still-installed packages with Wave receipts, even if their source has
been removed. The phone decides if a package is installed. Pending module
changes and watchdog-disabled modules are read from the existing Magisk
state; acknowledgment does not enable a module. Root is opt-in. Wave does
not open an unapproved `su` shell at startup and can browse sources without
root.

A queue preview is made from the current index and installed inventory. An
incompatible winner cannot be used as a dependency or action target. Confirm
accepts only the latest preview from the current index and sends it to
`EngineService`, which owns progress independently of the open screen. APK
plans also work through the Android Installer on stock; plans to change a
Magisk module require approved root.

## Look

`wave_packages` DESIGN.md's "Updater: as the App Store shows it" governs, and sets aside
the family rules where they differ (bold headings, Apple's greys and blue
`#007AFF`, "Updates" in the bar). In addition: the screen is portrait only,
and the status bar matches the near-white bar with dark icons.

## Open questions

1. The downloading ring shows the App Store's stop square, but OK on the
   app that is downloading does nothing; there is no way to cancel a
   download in progress. Should OK there stop it?
2. Wave's own row, when up to date, shows OPEN, but OK on it does
   nothing (it is already open) and the menu leaves Open out. Should its
   capsule show something else, or nothing?
3. Update All is on the bar whenever one app has an update, but in the menu
   only when two or more do. Intended (with one, Update is the same thing),
   or should the menu match the bar?
4. Not Installed apps are never included in Update All. Assumed intended:
   GET is a choice, UPDATE is upkeep.
