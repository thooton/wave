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
   This is the target, not the current implementation. Brief 09 delivers a
   read-only diagnostic guide, adb over WebUSB, root-consent-gated module
   Rescue, and local stock backup/readback verification. EDL reads were
   verified outside the deployed site; the public build does not include
   automated patch, flash, or authorization changes. See `jailbreak.md`.
6. **Root is required.** No unrooted mode. Wave installs through `su`;
   Installer and Updater are retired.
7. **Mods ship as full patched APKs in a Magisk module zip**, one binary for
   all firmware builds. Firmware differences are not to be designed around.
   The stock APKs are committed to `wave_packages` so CI can build.
8. **Default source contents**: every app in `../sonim` except HBSA (out),
   Source and Tablet (own repo and source), Installer and Updater (retired).
   AppDrawer stays: the programmable key can't open the stock tray, and
   the mod doesn't add a launchable component. Canvas, Charge, Mail,
   Places, Authenticator and Flashcards are reworked to not need a baked
   Delta login: the user supplies their own credentials, moved onto the phone
   by QR code from a generator tool, with Delta kept as a hidden alternative
   behind the same code. `camera_argon` is left out for now.
9. **Renames**: Messages Improved, Launcher Improved, Music Improved.
   `Argon` becomes `Wave` inside the code; module ids `messages_improved`,
   `launcher_improved`, `music_improved`.

10. **Privileged mod release signing.** The old build substituted DEX while
    keeping stale OEM signing records; it was not a valid OEM signature on
    the resulting APK. The imported histories omit that helper. New builds
    produce unsigned requests and the release gate verifies the actual OEM
    signer and unchanged requested payload before building a Magisk ZIP.
    Privileged mods stay unpublished unless authorized signing is available.

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
   `wavesrc` uses the v1 schemas and checks HTTPS URLs and square PNGs.
   The schemas now enforce one-line summaries and exact end anchors; the
   format text specifies folder starts and optional calendar dates. Integer
   fields are capped at the app parser's signed 64-bit range, with exact
   whole-number parsing. The existing example source already meets these
   rules. When publishing, CI
   restarts from the current branch tip on a concurrent push; an older or
   different same-version binary cannot replace the current release.
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
14. **The screens are `site/mockups/`** (brief 00; all 46 frames and the
    d-pad paths pass `site/mockups/CHECKLIST.md`). Choices made there, now in
    `design.md` section 4: a filled capsule is what OK presses, otherwise OK
    opens the row, so browsed rows (sections, Featured, search) open the
    details and show their state as an icon badge, while Installed keeps
    the Updater's press-the-capsule rows; on a details page the capsule is
    the only focus stop and Up/Down scroll; REMOVE and Reinstall only in
    the menu; the tab bar never takes focus and badges the update count;
    Add a Source fetches before the root-trust dialog so it can name the
    source; Search keeps its cursor at the end so Left/Right still switch
    tabs; the queue screens are modal (no tab bar). Mockup text uses the
    phone's own Roboto files, pulled from `/system/fonts` (read only).
15. **Source refresh commits by replacing `sources.json`** (brief 05). The
    list points at immutable cached generations under each source's `tree/`;
    switching the catalog pointer avoids a two-rename gap. Parse or fetch
    failure keeps the previous generation. Malformed package manifests are
    hidden individually. Between sources the winning version's type is used,
    even if that type cannot be installed by this version of Wave.

## Open

1. **The QR scanner.** Reading a QR code without a library means writing a
   decoder (finder patterns, perspective, Reed-Solomon). The alternative is
   allowing one vendored library (ZXing core) as the exception to the
   no-dependencies rule. Also unproven: whether the XP3's fixed-focus camera
   reads a code off a monitor reliably. Task 14 records the spike and
   chooses a transport. Default to setup-file import if reliable scanning
   cannot be demonstrated or hardware is unavailable.
2. **SQLite upgrade (not shipping).** The original `argon_sqlite` work is
   not committed upstream and was excluded from the import. No module or
   NDK job; revisit only after the upstream code and API checks are ready.
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
