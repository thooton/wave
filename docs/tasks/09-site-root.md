# 09: The rooting guide

**Repo** W (`site/`). **Needs** nothing to start; hardware verification
requires a stock test phone reserved for this brief (see `../../AGENTS.md`).

## Read first
`../root.md`, then `../rphone/ROOTING.md` end to end (every trap in it
is real), `../rphone/run.sh`, and `flipphoneguy/root-sonim-xp3800`. For the
protocols: `bkerler/edl` (Sahara and Firehose, the reference that is known
to work on this phone), `commaai/qdl.js` (a WebUSB implementation),
`@yume-chan/adb` (Tango), Magisk 24.0's `scripts/boot_patch.sh`.

## Goal
A person with Chrome, a USB cable and an XP3800 with USB debugging on clicks
through a page and ends with a rooted phone, Wave on the launcher, and their
stock boot image saved on their computer.

## Order of work (each step proves something before the next is built)
1. **Prove on-device patching over adb**, by hand, with `adb` and
   `bkerler/edl` on a desktop: extract the binaries from Magisk 24.0's APK,
   push them, run `boot_patch.sh` as the shell user, and compare the result
   with the image the Magisk app produced (`../rphone/boot_magisk.img` came
   from the app; expect small differences such as a random seed, so the real
   test is that it boots). Without this the design falls back to guiding the
   user through the Magisk app.
2. **adb over WebUSB**: connect, authorise, shell with exit status, push,
   pull, install, reboot. Tango or our own; write down why.
3. **Sahara and Firehose over WebUSB**: upload the loader, configure for
   eMMC, read the GPT, read `boot`, write `boot`, read back, reset. Test
   reads long before writes.
4. **The flow** in `../root.md`: steps, checks, resuming after a
   reload, the backup download.
5. **Rescue and Restore.**
6. Build and deploy to GitHub Pages from CI, with the loader, Magisk's
   files and the current Wave APK served from the site's own origin.

## Deliver
`site/` as a static site (TypeScript and a bundler are fine), a protocol
module each for adb, Sahara and Firehose with tests against recorded
exchanges, the rooting guide, Rescue and Restore pages, per-platform setup notes
(Linux udev and ModemManager, Windows WinUSB), the Pages workflow, and
`site/AGENTS.md`.

## Done when
A phone that was stock is rooted from the page on Linux, and Restore returns
it to its stock boot image, byte for byte over the partition. Then the same
on Windows and macOS, as far as machines are available; record what was and
wasn't tested in `../root.md`.

## Watch out
- **This flashes a phone.** Never write before the backup has been read,
  checked, and handed to the user. Never write an image that didn't come
  from patching that phone's own backup. Verify by reading back. Leave
  hardware validation pending if no reserved test phone is available.
- Find `boot` by name in the GPT. Refuse if the table doesn't look like an
  XP3800's.
- A patched image is shorter than the partition; compare only the bytes
  written (ROOTING.md step 5).
- `05c6:900e` is not EDL. Detect it and explain the cold start.
- After the Magisk app is installed, `su` from adb is denied by default
  (ROOTING.md step 7). Grant Wave and the shell in the policy table before
  that bites.
- GitHub release assets can't be fetched cross-origin; everything the page
  needs is on the page's origin.
