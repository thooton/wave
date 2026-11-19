# Decisions

Decisions, working defaults, and open questions for Wave. Update this log
in the same commit that changes the design. Use the defaults to keep work
moving; resolve open questions when the relevant brief has evidence.

## Decided

1. **Three repos.** `thooton/wave` (app, site, spec, tools),
   `thooton/wave_packages` (`thooton/sonim` renamed with its history, the
   three mods added as subfolders), `thooton/wave_repository` (the default
   source, pointing at `wave_packages` release assets). Everything ships
   through CI and GitHub. Source and Tablet are the private exception
   (task 16). The product is "Wave" ("Wave for Sonim XP3800").
2. **Tabs**: Wave, Sources, Installed, Search. Installed works as the Updater
   does now (updates first, then alphabetical).
3. **A source is a git repo**: a top-level manifest with a name and icon,
   section folders, package folders with an icon, name, description and a
   link to the binary. Binaries are downloaded on demand.
4. **Fetching**: git's smart HTTP protocol in plain Java, shallow.
5. **Jailbreak**: a WebUSB page in Chrome, Magisk 24.0 over EDL.
6. **Root is required.** No unrooted mode. Wave installs through `su`;
   Installer and Updater are retired.
7. **Mods ship as full patched APKs in a Magisk module zip**, one binary for
   all firmware builds. Firmware differences are not to be designed around.
   The stock APKs are committed to `wave_packages` so CI can build.
8. **Default source contents**: every app in `../sonim` except HBSA (out),
   Source and Tablet (own repo and source), AppDrawer (dropped; the launcher
   mod absorbs it), Installer and Updater (retired). Canvas, Charge, Mail,
   Places, Authenticator and Flashcards are reworked to not need a baked
   Delta login: the user supplies their own credentials, moved onto the phone
   by QR code from a generator tool, with Delta kept as a hidden alternative
   behind the same code. `camera_argon` is left out for now.
9. **Renames**: Messages Improved, Launcher Improved, Music Improved.
   `Argon` becomes `Wave` inside the code; module ids `messages_improved`,
   `launcher_improved`, `music_improved`.

## Working defaults

1. **Wave's package name is `com.wave.xp3`**, signed with the shared key from
   `wave_packages/signing`.
2. **Package names and the signing key of the existing apps don't change**, so
   Wave adopts what is already on a phone.
3. **Release assets are immutable**, one release per package version
   (`<name>-<version>`), replacing the rolling `latest` release. The old
   `latest` release and `manifest.json` stay until the migration checklist
   verifies that all known legacy devices have switched to Wave. If the
   inventory or migration status is unknown, keep the old channel.
4. **`wave_repository` is generated.** A package's fixed metadata and icon
   live beside its code in `wave_packages/<pkg>/wave/`; CI writes the source.
5. **Wave keeps a copy of `common/`** (`common/core`, `common/ui`), synced
   from `wave_packages` by a script that records the commit it came from.
   `wave_packages/common` stays the original. The alternative is making this
   repo the home of `common/` and having `wave_packages` pull it in.
6. **Firmware is advisory.** `requires.firmware` only adds a note. The boot
   watchdog, Rescue and Restore are the protection against a mod that doesn't
   suit a build. The mods' zips stop refusing by fingerprint.
7. **The mods live at `wave_packages/mods/{messages,launcher,music}`** with
   one shared toolkit, their histories imported, `stock` tags renamed
   `stock-<mod>`.
8. **The site may use third-party JavaScript**; the phone apps still use
   none.
9. **The Magisk app stays installed** after the jailbreak, and Wave is
   granted root ahead of time so no prompt appears.
10. **The default source can't be removed** from Wave.
11. **Bottom tab bar, as Cydia**. Validate the mockups against the frame
    and d-pad checklist in task 00 before building the screens.
12. **Dependencies are simple**: `depends`, `conflicts`, `replaces`, no
    solver.
13. **Sections of the default source**: Applications, System.

## Open

1. **The QR scanner.** Reading a QR code without a library means writing a
   decoder (finder patterns, perspective, Reed-Solomon). The alternative is
   allowing one vendored library (ZXing core) as the exception to the
   no-dependencies rule. Also unproven: whether the XP3's fixed-focus camera
   reads a code off a monitor reliably. Task 14 records the spike and
   chooses a transport. Default to setup-file import if reliable scanning
   cannot be demonstrated or hardware is unavailable.
2. **The SQLite upgrade module** (`argon_sqlite`, replaces the system
   `libsqlite.so`): publish it as its own package, fold it into Messages
   Improved, or leave it out?
3. **Nightly QA.** Delta's nightly QA runs on `thooton/sonim`. It follows the
   rename, but should it cover the mods and Wave too?
4. **Private asset access.** Source and Tablet use a private repo and source
   (task 16). Verify a supported authenticated download path; keep repository
   and asset credentials out of manifests. Use QR for source setup only if
   scanning is supported.
5. **Windows.** The WinUSB driver step for EDL is the one part of the
   jailbreak that isn't "open a page". Acceptable, or worth a small helper?
6. **Local folder names (closed).** W is already `wave`; keep P at
   `../sonim` on this shared machine. GitHub uses `wave_packages`. A later
   fresh clone can use the remote name. See `tasks/01-owner-checklist.md`.
7. **Wave updates in the background?** Version 1 checks when opened and on
   Refresh. A daily check with a notification is easy to add later.
