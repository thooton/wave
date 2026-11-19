# XP3800 device guide and recovery

The Wave site helps an owner identify their phone, keep a verified stock boot
backup, and recover from a bad Magisk module. It does not patch or write boot
images, install rooting tools, alter Magisk’s policy database, or grant apps
root. Ordinary APKs can use Android’s Installer; Wave requires root for
system mods. To opt in on an already-rooted phone, open **Wave → Root for
System Mods → Check Again** and review the Magisk prompt on the phone.

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
`shell_v2` with separate stdout/stderr and exit code, and Android reboot.
Development-only ADB SYNC push/pull and APK install via Android’s package
manager are tested separately and are excluded from the public artifact. A browser-only
ADB key stays in memory, so don’t select “Always allow” on the debugging
prompt. The public guide only uses read-only shell checks; the Rescue page
additionally performs the root operations described above.

## Optional read-only browser backup (off by default)

**Implemented and verified on one XP3800, read-only, and off by default.** The
owner enables it deliberately by building the site with the flag:

```sh
python3 site/scripts/build.py --with-device-backup OUTPUT_DIR   # OUTPUT_DIR must not exist
python3 site/scripts/check_pages.py OUTPUT_DIR
```

The default build (and the Pages workflow) leaves out `backup/index.html`,
`src/jailbreak/backup.mjs` and the Sahara, Firehose and GPT modules, and the
guide does not link to the backup page. The flag adds exactly those files and
a link. No programmer binary, boot image or Magisk file is ever bundled: the
owner selects their own copy of the Sonim-signed MSM8909 programmer as a local
file, and the page refuses anything but the pinned size and SHA-256
(`e6f2dbcf...2767`) before a single byte goes to USB.

What the page does, in order: optionally reads the Android serial and build
fingerprint over adb (or the owner types them from their records; EDL cannot
authenticate either, so the manifest records which it was); claims the
`05c6:9008` bulk interface; uploads the pinned programmer over Sahara;
configures eMMC; validates the primary GPT header and partition array CRCs and
refuses a layout that is not the single-slot XP3800 table (`boot` found by name,
fifth entry, 65536 sectors between `sec` and `system`, no `vbmeta` or A/B
slots); reads `boot` twice with a progress bar, requires the two reads to be
byte-identical and to start with `ANDROID!` with sane component sizes; computes
SHA-256; asks the programmer to reset to Android; then offers the image
and a manifest (`format`, `model`, `serial`, `fingerprint`, `identitySource`,
`partition`, `size`, `sha256`, `date`) as downloads. The Restore page's local
verifier accepts the pair and checks the image against the manifest hash.

The transport has no command for writing, erasing or patching storage. It
sends only `configure` (with `SkipWrite="1"`), `getstorageinfo`, `read` of
GPT sectors and the `boot` range, and `power reset`. The cost of reading `boot`
twice is under a minute.

Failure modes the page names: `05c6:900e` (crash dump, cold start needed), no
device chosen or the cable, an interface that cannot be claimed (udev rule,
a bound kernel driver, another tool holding the device), a USB timeout (no
partition was written; re-enter EDL), a GPT that is not an XP3800's, a
programmer that is not the pinned one, two reads that differ, and a reset that
was not acknowledged (check the phone before unplugging).

Platform notes: verified on Linux with Chromium 156. Chromium's usbfs backend
detaches a bound `cdc_acm`, `usblp`, `ftdi_sio` or `usb-storage` driver itself,
but not others; on this host the `qcserial` driver was bound to the EDL
interface and had to be detached first (pyusb `detach_kernel_driver`, or
unbind it in sysfs, or blacklist `qcserial` and `option` for the session).
Windows (WinUSB via Zadig) and macOS are untested.

`site/src/protocols/` also contains the transport modules; replays of the
captured exchanges run in `site/src/protocols/tests/`. The write-capable parts
that ROOTING.md describes (patch on the phone, flash) are not implemented.

For reserved-hardware QA, serve the opt-in build on loopback and open
`/backup/?policy=1`, or serve `site/src/protocols` and open
`/tests/manual-edl/`, in Chromium. Supply the verified programmer as a local
file. The page uses the normal USB picker, reports named GPT geometry, reads
only `boot`, optionally compares its SHA-256 with a known backup, and offers
an image download after the reset acknowledges. A locally managed QA browser
can use `?policy=1` (loopback hosts only) to use `navigator.usb.getDevices()`
instead of the picker.
Scope the Chromium `WebUsbAllowDevicesForUrls` policy to vendor `1478`, product
`36872` and that one loopback origin. Verify policy load in `chrome://policy`;
a command-line feature toggle does not grant device access by itself.

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
- **Chromium, staged Restore page:** checked both 33,554,432-byte backups
  entirely in the browser, confirmed the known hash and byte-for-byte
  equality, and verified that the page refuses a different expected hash.
- **Development EDL client, Chromium 156 on Linux:** headless Chromium
  used actual WebUSB on the reserved XP3800. `WebUsbAllowDevicesForUrls`
  was mounted into an isolated Bubblewrap profile, not installed on the
  host, and `chrome://policy` reported the policy as active. On this host
  the bound kernel driver had to be detached from the EDL interface before
  Chromium could claim it. The browser validated both GPT CRCs, listed 36
  partitions, and read all 33,554,432 bytes of `boot` at LBA 281696. Its
  SHA-256 matched both desktop backups above. The download became
  available and Firehose acknowledged reset. No partitions were written.
- **Opt-in backup page, end to end (Linux, Chromium 156):** built with
  `--with-device-backup`, served from loopback, driven in headless Chromium
  with the scoped policy above. The page claimed EDL, validated the 36-entry
  GPT, read `boot` twice with progress, saw the two reads identical, and got a
  reset acknowledgement; Android booted afterwards. The downloaded image is
  `cmp`-identical to the saved desktop backup (SHA-256 `489269c4...9455`), and
  the Restore page accepted image plus manifest ("Backup matches the manifest").
  The manifest's identity was owner-entered (`identitySource: owner-entered`);
  the adb identity button and the interactive USB chooser were not part of this
  run. Failure-mode messages (900e, GPT mismatch, timeouts) are covered by the
  code and replays, not by induced hardware faults.
- **Why the first Firehose run timed out:** the desktop capture showed a
  separate `logbuf@...` IN frame *after* configure ACK. The client now
  consumes it before its next OUT transfer, reads GPT sector 1 before
  `getstorageinfo`, and requires `ACK rawmode="true"` before each exact
  binary read and `ACK rawmode="false"` afterward. The captured trailing
  ZLP is accepted without including it in the image. Merely adding a
  longer timeout did not fix the blocked command.
- **Not tested:** rooted Rescue, rooting or stock restore from the public
  pages, and device USB workflows on macOS, Windows, ChromeOS or Android OTG.
  The public pages do not perform rooting or partition writes. Firefox and
  Safari do not supply WebUSB; the local verifier does not require WebUSB.
  See the site’s USB guide for Linux udev/ModemManager and Windows WinUSB.

Credit for the XP3800 research goes to anonymousfliphones and flipphoneguy
on JTech Forums and the `flipphoneguy/root-sonim-xp3800` community project.
[Magisk 24.0 source](https://github.com/topjohnwu/Magisk/tree/v24.0) is GPL-3.0.
