# XP3800 device guide and recovery

The Wave site helps an owner identify their phone, keep a verified stock boot
backup (an opt-in build reads it over USB; see the first section), and recover
from a bad Magisk module. It does not patch or write boot
images, install rooting tools, alter Magisk’s policy database, or grant apps
root. Ordinary APKs can use Android’s Installer; Wave requires root for
system mods. To opt in on an already-rooted phone, open **Wave → Root for
System Mods → Check Again** and review the Magisk prompt on the phone.

## Read-only boot backup from the browser (off by default)

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
GPT sectors and the `boot` range, and `power reset`. Reading `boot` twice took well
under a minute in testing.

Failure modes the page names: `05c6:900e` (crash dump, cold start needed), no
device chosen or the cable, an interface that cannot be claimed (udev rule,
a bound kernel driver, another tool holding the device), a USB timeout (no
partition was written; re-enter EDL), a GPT that is not an XP3800's, a
programmer that is not the pinned one, two reads that differ, and a reset that
was not acknowledged (check the phone before unplugging).

Driver and platform setup (the Linux `qcserial` detach, Windows WinUSB with Zadig,
macOS) is in **Platform setup** below; only Linux with Chromium 156 is tested.

`site/src/protocols/` also contains the transport modules; replays of the
captured exchanges run in `site/src/protocols/tests/`. The write-capable parts
that ROOTING.md describes (patch on the phone, flash) are not implemented.

### Then verify the saved files

Open the Restore page and choose the saved image and its manifest. It checks
the size, the `ANDROID!` header, the SHA-256 against the manifest (and against
a hash you type, if you have one), prints the manifest's serial, build and
date, and can compare a second read byte for byte. It never connects to the
phone. A matching hash shows the file is intact, not that it came from the
right phone or is stock: check the serial and build against the phone, and
keep two copies offline.

## Platform setup

| Platform | Status |
| :--- | :--- |
| Linux, Chromium 156 | Tested for the read-only EDL backup on a stock XP3800. The Android check used the same protocol code over libusb, not Chromium's chooser. ModemManager was not running, so its rule is untested |
| Windows, Chrome or Edge | **Untested.** Written from documented behaviour |
| macOS, Chrome or Edge | **Untested** |
| ChromeOS, Chrome on Android | **Untested** |

All platforms: stop the desktop adb server (`adb kill-server`) and close other
service tools, since one program at a time can hold a USB interface. The phone
appears as `1d9c:8339` (Android with debugging) and `05c6:9008` (EDL);
WebUSB needs a new device choice for each. `05c6:900e` is crash-dump mode, not
EDL.

**Linux.** Two udev lines give the desktop user access to only `05c6:9008`
and tell ModemManager to leave it alone (the public USB guide shows them).
The driver problem we hit: the `qcserial` module (aliased to `05c6:9008`) was
loaded and a kernel driver held the EDL interface, which Chromium will not
take over (it detaches only `cdc_acm`, `usblp`, `ftdi_sio` and `usb-storage`).
We detached it with libusb before connecting; we did not read the bound
driver's name. Administrator options, untested: unbind the interface under
`/sys/bus/usb/drivers/`, `modprobe -r qcserial` for the session, or
`blacklist qcserial` in `/etc/modprobe.d/` (which affects other Qualcomm
modems).

**Windows (untested).** WebUSB only sees interfaces bound to WinUSB, and in EDL
Windows normally binds Qualcomm's serial driver ("Qualcomm HS-USB QDLoader
9008"). With the phone in EDL, open Zadig, Options, List All Devices, select the
entry with USB ID `05C6 9008` (stop if the ID differs or several match), choose
WinUSB, Replace Driver, reload the page. This changes that one device's
driver; never touch a composite parent or shared hardware. To undo, uninstall
the device in Device Manager with "delete the driver software" and reconnect.
Desktop service tools expecting the serial driver will not see the phone until
then. The Android interface (`1d9c:8339`) may also need a WinUSB-bound vendor
or Google USB driver.

**macOS (untested).** No driver step is expected for vendor interfaces. Expect
trouble only from another program or the adb server holding the interface, a
charge-only cable, or a policy that blocks WebUSB.

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
  patched the boot image on the phone. PC-side patching black-screened. Our
  finding (see **Rooted test phone** below): the stock boot carries an AVB 1.0
  signature, the Magisk app re-signs after patching and `boot_patch.sh` alone
  does not, which is the likely cause of that black screen rather than where
  the patch ran. This is not evidence that a particular patch boots on
  another build.
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

## Rooted test phone (8516067e)

**The project's XP3800 (serial 8516067e) is rooted as of 2026-11-19**, at the
owner's request, by a desktop procedure; the site itself still does not patch
or write boot images.

| Item | Value |
| :--- | :--- |
| Build | `Sonim/XP3832/XP3800:8.1.0/3A.0.1-02-8.1.0-11.20.00/22:user/release-keys` |
| Root | Magisk 24.0 (24000), app `com.topjohnwu.magisk` installed and set up, Zygisk off, no modules |
| Stock `boot` (way back) | `~/Documents/wave-phone-backups/8516067e/boot-stock.img` and `boot-stock-repeat.img`, 33,554,432 B, SHA-256 `489269c42090f3505394cd062cd9d95eed981bbdb837d491faa7803f65979455` |
| Flashed `boot` (way forward) | `boot-magisk.img` in the same folder, 19,641,640 B, SHA-256 `49eb168c1ee38caf8a4680c7c213ef041680ad6d5457e2548fa5f0687fe7d81d`; full-partition read `boot-magisk-partition.img` `648f3508…5d93` (`SHA256SUMS-magisk`) |
| Superuser policy | uid 2000 (shell) allowed forever. `com.wave.xp3` deliberately not pre-granted, so ROOT-TESTING 06.1 can test Magisk's prompt |

Get root from adb: `adb -s 8516067e shell su -c id` prints
`uid=0(root) gid=0(root) groups=0(root) context=u:r:magisk:s0` with no prompt.

How the image was made, all on the phone as the adb shell user from the
verified stock backup: Magisk 24.0's `armeabi-v7a` binaries with its
`boot_patch.sh` (KEEPVERITY, KEEPFORCEENCRYPT, RECOVERYMODE all false, which
is what the app picks on this unencrypted, non-system-as-root phone and what
the app-made reference image records). The result was then signed with
Magisk's own `com.topjohnwu.magisk.signing.SignBoot` from the APK, run on the
phone with `dalvikvm` (`-sign`, then `-verify` prints `Signature is VALID`).
Only `boot` was written, over EDL (bkerler/edl, pinned programmer `e6f2dbcf…2767`),
at sector 281696. Readback: the first 19,641,640 bytes match the image, the
padding to the next sector is zero and the rest of the partition is still
stock. A second, separate EDL read later gave the same result. It booted in
about 30 s, root survives reboots, and the 21 `*.xp3` packages and Wi-Fi state
(off) are unchanged. Commands are in `../rphone/ROOTING.md` step 4b.

**The signature finding.** Stock `boot` ends in a valid AVB 1.0
`BootSignature` (1320 bytes, target `/boot`, the public AOSP verity test key,
certificate SHA-256 `8ad127ab…3b86`). `boot_patch.sh`/`magiskboot` do not
sign: their `new-boot.img` is 32 MiB with zeros after the payload. The Magisk
app signs afterwards, which is why its image (`../rphone/boot_magisk.img`, from
another unit) is payload plus 1320 bytes. Both recompress the kernel (about
7.7 MB of the kernel region changes either way), so the missing signature,
not PC-side recompression, is the likely reason ROOTING.md's PC patch
black-screened. We did not flash an unsigned image to prove it. Our signed
image has the reference image's structure exactly: header offsets, payload
19,640,320 bytes, signature length and certificate. Note that 24.0's
`util_functions.sh` names the signer class `com.topjohnwu.signing.SignBoot`,
which is not in the 24.0 APK.

Two things to know on this phone. Magisk's su prompt times out to deny after
10 s and can be answered by stray d-pad keys; one such prompt stored "deny
forever" for Shell during setup, and it had to be switched on in the
Superuser tab. To get back to stock while Android boots: `adb reboot edl`, then
write `boot-stock.img` and verify the whole partition (ROOTING.md section 9).
A phone that does not boot needs a cold EDL start (battery pull).

## Verification

- **Linux, physical stock XP3800:** `Sonim/XP3832/XP3800:8.1.0/3A.0.1-02-8.1.0-11.20.00/22:user/release-keys`.
  bkerler/edl read the GPT and two independent 33,554,432-byte stock `boot`
  images, equal byte for byte. SHA-256:
  `489269c42090f3505394cd062cd9d95eed981bbdb837d491faa7803f65979455`.
  The owner’s copies and `SHA256SUMS` are in
  `~/Documents/wave-phone-backups/8516067e/`, never in Git. The phone reset
  back to Android and remained unrooted; no partitions were written. (It was
  rooted later; see **Rooted test phone** above.)
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
