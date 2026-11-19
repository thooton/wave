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
   Rescue, and local stock backup/readback verification. A read-only browser
   backup over EDL (Sahara and Firehose, hash-pinned programmer chosen locally,
   two reads compared, image plus manifest download) is implemented and verified
   on the XP3800 but excluded from the default Pages bundle: it is built only
   with `build.py --with-device-backup`. The site build owner declined to stage
   an EDL transport by default; the owner enables it deliberately. Patch and
   flash are not implemented. See `jailbreak.md`.
6. **Root is required for system mods**, and Wave installs through `su`
   when it has root. Amended by working default 16: root is opt-in, and
   without it Wave still installs apps, through Installer where a phone
   has it or Android's own installer otherwise. Installer and Updater are
   retired as packages (no new releases, `gradle/retired-apps.txt`), not
   removed from phones that have them.
7. **Mods ship as full patched APKs in a Magisk module zip**, one binary for
   all firmware builds. Firmware differences are not to be designed around.
   The stock APKs are committed to `wave_packages` so CI can build.
8. **Default source contents**: every app in `../sonim` except HBSA (out),
   Source and Tablet (own repo and source), Installer and Updater (retired).
   AppDrawer stays: the programmable key can't open the stock tray, and
   the mod doesn't add a launchable component. Canvas, Charge, Mail,
   Places, Authenticator and Flashcards are reworked to not need a baked
   Delta login: the user supplies their own credentials, moved onto the phone
   by a generator tool, with Delta kept as a secondary alternative behind
   the same code. The original QR-first plan is deferred; version 1 uses
   setup files (`../spec/setup-payload.md`). `camera_argon` is left out for now.
9. **Renames**: Messages Improved, Launcher Improved, Music Improved.
   `Argon` becomes `Wave` inside the code; module ids `messages_improved`,
   `launcher_improved`, `music_improved`.

10. **Privileged mod release signing.** The old build substituted DEX while
    keeping stale OEM signing records; it was not a valid OEM signature on
    the resulting APK. The imported histories omit that helper. New builds
    produce unsigned requests and the release gate verifies the actual OEM
    signer and unchanged requested payload before building a Magisk ZIP.
    Privileged mods stay unpublished unless authorized signing is available.

11. **The apps' logins stay in Android backup** (the owner, 2026-11-19).
    Every app in `wave_packages` keeps `allowBackup="true"`, so `adb backup`
    of a phone carries each app's setup file (`files/wave-setup.json`):
    the Tesla tokens (Charge), the Authenticator accounts and its command
    center sign-in, Mail's login, the Canvas and Flashcards tokens, and the
    rest. **The trade-off, plainly:** anyone who holds an `adb backup` of the
    phone, or the phone unlocked with USB debugging on, can read those
    secrets, and Android's backup password is the only protection. The owner
    judged that moving a phone with its logins intact is worth more than that
    risk on their own device. It is not a recommendation for anyone else's
    phone; the setup screen's Sign out and the apps' own revocation (the
    command center, Tesla, Canvas) are the remedy after a backup is lost.
    Wave itself is the exception for its own data: `sources.json` can hold a
    private source's sign-in and is excluded from backup
    (`app/app/src/main/res/xml/backup.xml`). Written in `wave_packages`
    `common/SETUP.md` ("Where it is kept").

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
9. **The Magisk app stays installed** after the jailbreak. The original
   plan to pre-grant Wave root so no prompt appears is superseded by
   working default 16 (owner opt-in).
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
16. **Root is opt-in; nothing pre-grants Wave** (briefs 06 and 08, by the
    owner's instruction). Wave opens `su` only when the user asks for
    something that needs it; Magisk's prompt or Superuser screen decides.
    Replaces the design draft's "the jailbreak pre-grants Wave". Without
    root, apps still install through the Updater's Installer.
17. **APKs are checked by the platform's parser** (brief 06):
    `PackageManager.getPackageArchiveInfo`, the parser `pm` uses, rather
    than a binary-manifest reader in `:core`. `:core` decides from what it
    read (`Verify.apk`).
18. **The boot watchdog judges only the boot** (brief 08). A module is kept
    once boot completed and `system_server` stayed up for a minute; one
    forced reboot after five minutes at most, never two in a row; a
    launcher crashing after boot isn't watched, to have no false positives.
    Tested by running the real scripts through pretend boots on the desktop
    (bash and busybox ash); on the phone it is unverified until a rooted
    test phone exists (`app/ROOT-TESTING.md`).

## Open

1. **The QR scanner.** Reading a QR code without a library means writing a
   decoder (finder patterns, perspective, Reed-Solomon). The alternative is
   allowing one vendored library (ZXing core) as the exception to the
   no-dependencies rule. Also unproven: whether the XP3's fixed-focus camera
   reads a code off a monitor reliably. Task 14 records the spike and
   chooses a transport. Default to setup-file import if reliable scanning
   cannot be demonstrated or hardware is unavailable.
   *Spike (task 14):* the XP3800 has one back camera, fixed focus, stills
   2592x1944, preview at most 1920x1080; three camera apps answer
   IMAGE_CAPTURE, so a capture intent opens a chooser. No library is
   needed: Authenticator already carries a QR decoder of its own
   (`authenticator/android/core/.../qr/`). Not shown: reliable reads off
   a monitor, which needs a person aiming the phone. *Transport chosen:
   the setup file* (`spec/setup-payload.md`: Import from the app's own
   folder, or the system file picker, which works with the d-pad). The
   QR chunk format (`WS1/i/n/id8/part`) is specified and parsed by
   `Config.Chunks` in P `common/core`, with no scanner behind it yet;
   revisit once someone has tried aiming at a screen.
   *Recommendation (agent 8, for the owner to decide):* keep setup files and
   do not build a scanner now. Evidence: no scanning attempt has been made, so
   there is nothing to weigh against the file path; the file path is
   implemented and generator output parses in all six apps (`status.md`);
   today's device work found that the d-pad phone's real friction was USB,
   permissions and drivers, not the setup transfer; and a scanner would add a
   camera permission and a chunk-assembly UI to six apps for a path that
   needs a person aiming at a monitor. Revisit if all of these hold: someone
   reads a real generated code off a monitor with the XP3800 camera, in at
   least 10 of 10 tries in ordinary room light, using Authenticator's existing
   decoder (no vendored library); the largest real payload splits into few
   enough chunks to scan in under a minute; and owners report the file import
   as the obstacle. A failure on the first condition ends the question.
2. **SQLite upgrade (not shipping).** The original `argon_sqlite` work is
   not committed upstream and was excluded from the import. No module or
   NDK job; revisit only after the upstream code and API checks are ready.
3. **Nightly QA.** Delta's nightly QA runs on `thooton/sonim`. It follows the
   rename, but should it cover the mods and Wave too?
4. **Private asset access.** Source and Tablet use `wave_private_packages`
   (task 16). CI emits credential-free HTTPS API URLs pointing at immutable
   GitHub release asset IDs; no credential or signed redirect
   belongs in a manifest. Git refresh and binary download authenticate
   separately. *Download side done (Wave 0.7.14):* a source added with
   `user:token@` in its address signs in to its own downloads. A GitHub API
   asset URL of the same repository gets `Bearer` and `Accept:
   application/octet-stream`, and the source's own host gets `Basic`. Each
   redirect hop is judged again, so the CDN never gets the token. Nothing the
   phone keeps or shows carries the credential. A 401 or 403 says the source
   needs a sign-in, or that its sign-in was refused. Host tests cover it
   (`Install: private source sign-in`). Still open: a real private
   repository's asset fetched on the migration device before cutover; see
   the [owner checklist](tasks/01-owner-checklist.md#source-and-tablet-private-cutover).
5. **Windows.** For the intended browser flow, the required WinUSB driver
   binding might warrant a helper. The current device guide explains the
   driver requirements; the site does not install any drivers.
   *Recommendation (agent 8, for the owner to decide):* no helper for now;
   keep the Zadig steps in the guide. Evidence: in EDL (`05c6:9008`) Windows
   binds Qualcomm's serial driver, and WebUSB only reaches WinUSB-bound
   interfaces, so a driver swap is unavoidable. Zadig does that for one device
   ID with administrator approval and is undoable, so a helper would replace
   one manual step with something Wave must sign, host and maintain, which
   fits poorly with a site that ships no binaries and asks owners to trust
   nothing they cannot read. The backup page is off by default and meant for
   one-time use per phone. Nothing on Windows is tested: the guide's steps come
   from documentation, and the only driver problem observed was on Linux (the
   `qcserial` module holding the interface, which needed a detach). Revisit if
   a Windows owner follows the guide on a real XP3800 and the Zadig step is
   what fails or is not understood, or if the backup page becomes part of the
   default site. A helper would then only need to bind WinUSB to that single
   ID and offer the undo; it should not touch any other driver.
6. **Local folder names (closed).** W is already `wave`; keep P at
   `../sonim` on this shared machine. GitHub uses `wave_packages`. A later
   fresh clone can use the remote name. See `tasks/01-owner-checklist.md`.
7. **Wave updates in the background?** Version 1 checks when opened and on
   Refresh. A daily check with a notification is easy to add later.
