# XP3800 device guide and recovery

The Wave site helps an owner identify their phone, keep a verified stock boot
backup, and recover from a bad Magisk module. It does not patch or write boot
images, install rooting tools, alter Magisk’s policy database, or grant apps
root. Wave requires a working root installation; the owner approves any
request for superuser access on the phone.

## What the public pages do

| Page | Automated | Owner action |
| :--- | :--- | :--- |
| Jailbreak guide | With WebUSB, reads model, SDK, ABI, fingerprint, boot status, battery and presence of `su` (not whether root is granted). | Enable USB debugging and approve the browser’s RSA key. Keep a personal-data backup and have a trusted service tool read the stock boot image before changing anything. |
| Rescue | On an already-rooted XP3800, lists modules, marks only selected modules disabled in Magisk’s installed/staged directories, and requests a normal Android reboot if selected. | Authorize USB debugging and any Magisk request for Shell. Review the list, explicitly choose modules, and click Reboot separately. |
| Restore | Locally validates a 32 MiB `ANDROID!` XP3800 boot partition image, computes SHA-256, compares it with an original record if provided, and optionally compares a second full read or post-restore readback byte for byte. | Verify the backup is from this exact phone and build. Stock recovery itself is done outside the browser using a trusted stock service procedure. Wait for Android and uninstall unwanted apps through Settings. |

The backup verifier cannot prove origin from a header or checksum alone. Save
two matching, independent reads together with the original hash, serial and
firmware. Do not use an image from another unit, build, or carrier. A stock
boot restore does not remove app data, Magisk modules, or arbitrary changes to
other partitions. Nothing in this site touches `/system`.

## Device caveats

- Verify an XP3800, Android 8.1 (SDK 27) and 32-bit ARM. Charge to at least 40%
  before servicing. Firmware support is based on an observed layout and device
  checks rather than a hard-coded fingerprint.
- On a phone that cannot boot, disconnect USB, remove the battery for about
  30 seconds, hold both volume buttons, replace the battery, and reconnect USB
  while still holding the buttons. `05c6:9008` is EDL; `05c6:900e` is crash-dump
  mode and must not be treated as EDL. This cold-start route comes from the
  community procedure; it was not needed in our read-only tests.
- A read-only stock backup uses the device’s GPT to find `boot`. The observed
  single-slot partition is 32 MiB and has a 2048-byte-page Android boot header.
  For stock restore verify the whole partition, not only the populated prefix.
- The earlier community research in `../rphone/ROOTING.md` used Magisk 24.0 and
  patched the boot image on the phone. PC-side patching black-screened. This
  report is not evidence that a particular patch will boot on another build.
- Magisk can deny adb shell even if the root installation works. If the owner
  needs Rescue, check the Magisk app’s Superuser access settings and review
  the Shell prompt. The browser does not grant itself access.

## Protocols and checks

The site uses native ES modules without npm. A small adb client was chosen
instead of Tango so the allowlisted static build does not need a bundler or
transitive packages. It implements USB bulk transport, RSA authorization,
`shell_v2` with separate stdout/stderr and exit code, SYNC push/pull, APK
install via Android’s package manager, and Android reboot. A browser-only
ADB key stays in memory, so don’t select “Always allow” on the debugging
prompt. The public guide only uses read-only shell checks; the Rescue page
additionally performs the root operations described above.

`site/src/protocols/` also contains a **non-deployed read-only** development
client for Sahara and Firehose. It checks the hash of the specific
Sonim-signed MSM8909 programmer, transitions to eMMC Firehose, validates GPT
header and table CRCs, reads only `boot`, and supports Android reset. It has
no flash, patch or erase command. The public Pages artifact does not include
that client, a signed programmer, boot images, or Magisk files.

Run `node --test site/src/protocols/tests/*.test.mjs` for redacted XP3800
replays. ADB token/signature bytes are replaced with zeroes; GPT GUIDs are
removed with CRCs recalculated. Synthetic boot payloads and programmer slices
exercise the captured framing without redistributing either binary. The
Sahara/Firehose references are bkerler/edl and commaai/qdl.js.

## Verification

- **Linux, physical stock XP3800:** `Sonim/XP3832/XP3800:8.1.0/3A.0.1-02-8.1.0-11.20.00/22:user/release-keys`.
  bkerler/edl read the GPT and two independent 33,554,432-byte stock `boot`
  images, equal byte for byte. SHA-256:
  `489269c42090f3505394cd062cd9d95eed981bbdb837d491faa7803f65979455`.
  The owner’s copies and `SHA256SUMS` are in
  `~/Documents/wave-phone-backups/8516067e/`, never in Git. The phone reset
  back to Android and remained unrooted; no partitions were written.
- **ADB protocol on Linux hardware:** the same JavaScript adb client ran
  over a temporary libusb bridge, using an already authorized desktop key.
  It verified shell stdout/stderr/exit 7 and pushed, pulled, and removed a
  temporary text file under `/data/local/tmp`. The browser device picker and
  new RSA authorization prompt were not verified live. APK install was
  tested with a mocked package-manager failure, not on the phone through
  WebUSB.
- **Sahara:** the implementation transferred the pinned programmer on the
  stock device and got the successful handoff response. Verification of the
  Firehose client over live USB is pending. The complete read-only operation
  was verified using bkerler/edl as above.
- **Not tested:** rooted Rescue, rooting or stock restore from the public
  pages, and device USB workflows on macOS, Windows, ChromeOS or Android OTG.
  The public pages do not perform rooting or partition writes. Firefox and
  Safari do not supply WebUSB; the local verifier does not require WebUSB.
  See the site’s USB guide for Linux udev/ModemManager and Windows WinUSB.

Credit for the XP3800 research goes to anonymousfliphones and flipphoneguy
on JTech Forums and the `flipphoneguy/root-sonim-xp3800` community project.
[Magisk 24.0 source](https://github.com/topjohnwu/Magisk/tree/v24.0) is GPL-3.0.
