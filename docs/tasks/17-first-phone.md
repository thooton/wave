# 17: First phone, end to end

**Repos** all. **Needs** 07, 08, 09, 12, 13, 15 for the stock run; 14 and
16 for migration. Each run needs a reserved device of the relevant type.

## Goal
The whole thing works once, on real hardware, from a stock phone to an
installed mod. Existing devices on the legacy channel are migrated to Wave.

## A stock phone
1. Root it from the site (09). Stock boot image saved.
2. Wave opens with root and the default source loaded.
3. Install an app; open it. Update it after a version bump goes through CI.
   Remove it.
4. Install Messages Improved, Launcher Improved and Music Improved; reboot;
   check each does what its docs say. Remove one; reboot; stock is back.
5. Add a second source by URL; search finds its packages; remove it.
6. Install the deliberately broken module from 08; the watchdog recovers.
7. Rescue and Restore from the site; the phone is stock again.

Record each step with a screenshot or a log line, and anything that needed a
workaround, in `docs/first-phone.md`.

## Legacy devices (already rooted, running the Argon modules, Updater and Installer)
1. Install Wave. It adopts the installed apps (same package names and key).
2. Update the mods through Wave: the `*_improved` modules replace the
   `*_argon` ones and settings survive (12).
3. Give up Installer's device-owner role, then let Wave remove Installer
   and Updater.
4. Add the private source (16) for Source and Tablet.
5. Give each reworked app its Delta setup payload (14).
6. Verify a refresh and package update through Wave. Track each known
   legacy device in the migration checklist; use non-identifying labels in
   the project log. Task 13 can retire the old channel only once all entries
   pass and the inventory is known.

## Done when
Both lists are done with no manual `adb` beyond what the lists name, and
`../decisions.md` has no open item that this run answered.

## Watch out
- This brief reboots and flashes. Run only on a test or migration device
  reserved by serial for this task (`../../AGENTS.md`). Leave the affected
  run pending if no suitable device is reserved.
- The test phone is on firmware `3A.0.1-02-8.1.0-11.20.00` and the mods were
  built from `3A.0.0-01-8.1.0-11.17.00`. This run is the first real check of
  the one-binary decision: note what, if anything, behaves differently.
