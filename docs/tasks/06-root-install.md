# 06: Root and the install engine

**Repo** W (`app/core`, `app/app`). **Needs** 04. **Blocks** 07, 08.

## Read first
`../design.md` section 4 ("Doing things", "Root"),
`../sonim/updater` (the install queue in `MainActivity`, `Releases`,
`ApkProvider`), `../sonim/installer/AGENTS.md` (what is being replaced),
`../messages_argon/tools/deploy.sh` and `package.sh` (how modules are
installed and removed today), Magisk 24.0's `scripts/` for
`--install-module`.

## Goal
Given a list of things to install, update and remove, do it through root,
correctly and observably, with no Installer app.

## Deliver
In `:core` (no Android):
- `Plan`: turns requests into ordered steps using `depends`, `conflicts` and
  `replaces`; refuses with a reason; puts Wave's own update last.
- `Download`: to a file with progress, size and SHA-256 checks, resumable,
  from the Updater's `Releases`.
- `Scripts`: the shell text for each operation in the design's table, and
  parsers for what comes back (`pm` results, the module listing, `id`).
- `Receipts`: what Wave installed (`files/receipts.json`).
- Checks before install: an APK's package name and `versionName` (parse the
  binary manifest in Java, or ask `pm` after the fact; decide and say why),
  a zip's `module.prop`.

In `:app`:
- `RootShell`: one long-lived `su` process, scripts in, exit status and
  output out, timeouts, and a clear "no root" result.
- `Engine`: runs a plan step by step on a worker, reports progress to the
  UI, survives the activity going away, and after a module step marks a
  reboot as pending.
- `Phone`: installed apks from PackageManager and modules through root, as
  05's `Status` needs.
- Wave updating itself: from a detached root shell, then relaunch.

## Done when
On a rooted phone (ask before using it), from a debug screen or instrumented
entry point: install, update and remove an apk; install, update and remove a
module with a reboot in between; a wrong hash, a wrong package name, no
network mid-download and no space each fail cleanly and leave nothing
behind; Wave updates itself. Self-tests cover the plan, scripts and parsers.

## Watch out
- `pm install` run as root must be able to read the file. Stream it
  (`cat file | pm install -S <size>`) or put it where `system_server` can
  read; test on the phone.
- A module installed by `magisk --install-module` sits in `modules_update`
  until reboot, and one being removed still has its folder. "Installed",
  "pending install" and "pending removal" are three states.
- Magisk's superuser prompt can't be relied on with a d-pad. The jailbreak
  pre-grants Wave; if the grant is missing, say exactly what to do.
- Never write outside `/data/adb`, the app's own files and what `pm` and
  `magisk` do themselves.
