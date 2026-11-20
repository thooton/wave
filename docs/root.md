# XP3800 device guide and recovery

The Wave site roots an XP3800 from Chrome or Edge over WebUSB: it saves the
phone's stock boot image to the owner's computer, patches it on the phone with
Magisk 24.0, writes it over EDL and verifies every byte, then installs the
Magisk app and Wave (**Root**, `/wave/rooting/`). It also writes the saved
stock image back (**Stock boot**, `/wave/stock/`), reads a backup on its own,
checks saved backups, and disables broken modules (Rescue). It writes only the
`boot` partition, never `/system` or bootloader flags, and never grants Wave
root: Magisk asks on the phone. To use root on an already-rooted phone, open
**Wave → Root for System Mods → Check Again** and answer Magisk's prompt.

## Root from the browser

Implemented in `site/rooting/` (`src/root/rooting.mjs`, `device.mjs`,
`magisk.mjs`) over `src/protocols/` (`adb.mjs`, `adb-sync.mjs`, `zip.mjs`,
`boot-signature.mjs`, `firehose.mjs`, `firehose-write.mjs`, `sahara.mjs`,
`gpt.mjs`). It is in the default build; verified end to end on 8516067e
(below). The steps, each proving the one before:

1. **Files.** The Sonim programmer (local file, pinned SHA-256
   `e6f2dbcf…2767`), Magisk 24.0's APK (pinned `f7ac4e2a…346a`, 10,440,388 B)
   and a Wave APK. Magisk and Wave can be served from the site's own origin
   (`build.py --magisk-apk PATH --wave-apk PATH --wave-version X` stages them
   in `rooting/files/` with a `files.json`); the programmer never is.
2. **Phone over adb.** A fully booted XP3800, SDK 27, `armeabi-v7a`, Sonim
   fingerprint, battery at least 40%, and no `su` (a rooted phone is refused:
   its boot is not stock). Records serial and fingerprint; every later adb
   connection must match them. Then `reboot:edl`.
3. **Backup in EDL.** Sahara with the pinned programmer, Firehose read-only
   (`SkipWrite="1"`), GPT validated as the XP3800's, `boot` read twice and
   compared, its AVB 1.0 signature verified in the browser (WebCrypto) and its
   certificate required to be the XP3800's stock one (`8ad127ab…3b86`), and its
   ramdisk checked for Magisk's marker (`.backup/.magisk`). Firehose reset.
4. **Save.** The image and a manifest are offered as downloads; the owner
   must choose the saved image again and its SHA-256 must match. Nothing is
   written before this.
5. **Patch on the phone**, as ROOTING.md step 4b, as the adb shell user: the
   browser unzips `boot_patch.sh`, `util_functions.sh` and the `armeabi-v7a`
   `magiskboot`, `magiskinit`, `magisk32` and `busybox` from the APK and pushes
   them with the stock image and the APK to `/data/local/tmp/wave-magisk`,
   checks their SHA-256 on the phone, runs `boot_patch.sh` with
   `KEEPVERITY=false KEEPFORCEENCRYPT=<true only if ro.crypto.state is
   encrypted> RECOVERYMODE=false` (system-as-root is refused), signs with
   `com.topjohnwu.magisk.signing.SignBoot` under `dalvikvm`, requires
   `-verify` to say `Signature is VALID`, pulls the result and checks it again
   in the browser (`checkPatchedBoot`: signature verifies over exactly its
   payload with the stock image's certificate, nothing after it, same header
   fields as stock, a different ramdisk, Magisk's marker present). The work
   folder is removed even on failure. Then `pm install` of Magisk (must report
   24.0) and Wave (`com.wave.xp3` must then exist), and `reboot:edl`.
6. **Write in EDL** (`FirehoseBootWriter`, `SkipWrite="0"`): read `boot` and
   require it to equal the saved backup, `program` the image padded to a
   sector (1 MiB chunks, each ended by a ZLP, as bkerler/edl), read the whole
   partition back and require image, zero padding and the untouched stock
   tail. A readback mismatch writes the stock image back in the same session
   and verifies it. Then reset.
7. **First rooted boot.** `/sbin/su` must link to `magisk`; the Magisk app is
   opened so the owner can answer "Requires Additional Setup" if it appears.
8. **Optional `su` for adb** (Rescue needs it): one `su -c id`, answered by
   the owner in Magisk's prompt; then a root check that Magisk's environment
   (`/data/adb/magisk`) is complete. **Open Wave** starts it on the phone and
   the page forgets its copies of the images.

The stage is kept in `localStorage` and the stock and patched images in
IndexedDB, so a reload resumes at the right step (verified: the page resumed
at step 8 after a reload). **Start over** clears both.

**Stock boot** (`site/stock/`, `src/root/stock.mjs`) takes the programmer,
the image and its manifest (or a typed SHA-256, one is required), requires a
32 MiB image whose SHA-256 matches, whose stock signature verifies with the
XP3800 certificate and that Magisk has not patched. If Android starts, it
checks the phone's serial and build against the manifest and restarts it into
EDL; otherwise the owner confirms it is the same phone and uses the cold
start. It reads what `boot` holds first (offered as a download), writes the
whole image, reads all 32 MiB back and compares, then resets.

Things learned on the phone and handled in code:
- Just after `sys.boot_completed` becomes 1 the XP3800 drops off USB and
  re-enumerates once. A connection that had to wait for boot now waits 8 s,
  closes and reconnects (`device.mjs`); before that, the next command failed.
- adbd can flush packets from an abandoned session (a late `CLSE`, a stale
  `AUTH` token) at a new connection. `Adb.connect` skips stale stream packets
  and signs twice before offering the key, instead of failing the handshake.
- The patch is deterministic: the page-made image is byte-identical
  (`49eb168c…7d81d`) to the one made by hand in November.

## Read-only boot backup from the browser

**Implemented and verified on one XP3800, read-only.** It was opt-in
(`--with-device-backup`) until the owner put rooting in the default build on
2026-11-20; the backup page is now always staged. No programmer binary or
boot image is ever bundled: the owner selects their own copy of the
Sonim-signed MSM8909 programmer as a local file, and the page refuses anything
but the pinned size and SHA-256 (`e6f2dbcf...2767`) before a single byte goes
to USB.

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
captured exchanges run in `site/src/protocols/tests/`. The write path is
separate (`firehose-write.mjs`, used only by Root and Stock boot).

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
| Rooting guide | With WebUSB, reads model, SDK, ABI, fingerprint, boot status, battery and presence of `su` (not whether root is granted). Links Root, Backup and Stock boot. | Enable USB debugging and approve the browser’s RSA key. Keep a personal-data backup. |
| Root | The whole flow above: backup over EDL, patch on the phone over adb, Magisk and Wave install, EDL write with full readback, root check. | Choose the programmer (and Magisk and Wave unless the site serves them), approve USB debugging, pick the devices in the chooser, save the backup and choose the saved file, answer Magisk on the phone. |
| Backup | Reads `boot` twice over EDL and offers image and manifest. Writes nothing. | Choose the programmer, enter EDL. |
| Stock boot | Checks the saved image (size, hash against manifest or typed hash, stock signature, not patched), checks the phone's identity over adb when Android runs, writes it over EDL and compares all 32 MiB read back. | Confirm it is this phone's backup; cold start into EDL if Android does not run. |
| Rescue | On an already-rooted XP3800, lists modules, marks only selected modules disabled in Magisk’s installed/staged directories, and requests a normal Android reboot if selected. | Authorize USB debugging and any Magisk request for Shell. Review the list, explicitly choose modules, and click Reboot separately. |
| Restore | Locally validates a 32 MiB `ANDROID!` XP3800 boot partition image, computes SHA-256, compares it with an original record if provided, and optionally compares a second full read or post-restore readback byte for byte. Never connects. | Verify the backup is from this exact phone and build. Writing it back is Stock boot. |

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
ADB SYNC push/pull (`adb-sync.mjs`) and `reboot:edl` are used by Root.
`shell` takes a per-command timeout for the silent minute of patching or
signing. A browser-only
ADB key stays in memory, so don’t select “Always allow” on the debugging
prompt. The guide only uses read-only shell checks; Rescue performs the root
operations described above; Root runs as the shell user and asks for `su`
once, only when the owner clicks it.

For reserved-hardware QA, serve the build on loopback and open
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
owner's request, first by a desktop procedure and, since 2026-11-20, by the
site's own Root page (twice, from stock written back by Stock boot; the
result is byte-identical).

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
- **Rescue on the rooted XP3800 (Linux, Chromium 156, 2026-11-19):** the
  built Rescue page ran in headless Chromium over real WebUSB to the phone's
  adbd (`WebUsbAllowDevicesForUrls` for `1d9c:8339` on a loopback origin,
  mounted in a Bubblewrap profile). The harness changed two things only: the
  chooser was replaced by the one device the policy granted, and the page's
  fresh RSA key by the desktop's already-authorized adb key. So the chooser and
  the "Allow USB debugging?" prompt are still untested. A marker module
  (`wave_rescue_marker`: `module.prop` and a `service.sh` that logs the boot
  ID) was installed with `magisk --install-module`. Results:
  - The page listed it while pending (`modules_update`) and when active.
  - **Disable selected** wrote `disable` and reported "1 module marked
    disabled. Restart Android to apply the change.", and the list emptied.
  - **Reboot Android** restarted the phone. The next boot ran no
    `service.sh`, and the page then said "No enabled modules found."
  - After removing `disable` (what Magisk's toggle does), the page listed it
    again and the next boot ran `service.sh`. After `remove` and a reboot it
    was gone.
  - Magisk's policy table was the same before and after each run, as the page
    says.

  One mismatch was found and fixed: after a successful **Reboot Android** the
  page reported "The browser could not claim the USB interface", because
  Chromium surfaces the phone dropping off USB as `NetworkError`. `Adb.reboot()`
  now treats `NetworkError` after `reboot:` was accepted like `NotFoundError`.
  Re-run on the phone, it shows "Restart requested. Wait for Android to finish
  booting." Notes:
  - A module marked for removal still appears in the list until the next boot,
    since it has no `disable` file. That is harmless.
  - The "authorize Shell if prompted" step was not exercised, because Shell is
    already granted on this phone.
  - The Volume Down safe-mode gesture is still unverified.
- **Magisk app (24.0) on this phone:** `../rphone/Magisk-v24.0.apk` is
  byte-identical to the official GitHub release `v24.0/Magisk-v24.0.apk`
  (SHA-256 `f7ac4e2a…346a`, signer CN=John Wu). `am start` opens
  `.ui.MainActivity` (about 9 s cold) and the crash buffer is empty. Agent 1
  also saw no crash during ROOT-TESTING. A "Magisk has stopped" dialog was
  seen once, before the app's first-run setup and reboot, and could not be
  reproduced. Its only logged error now is the update check failing with
  Wi-Fi off. No warning about the APK is needed.
- **Root and Stock boot, end to end on the XP3800 (Linux, Chromium 156,
  2026-11-20, owner-approved).** The built site was served on loopback and
  driven in headless Chromium over real WebUSB (`WebUsbAllowDevicesForUrls`
  for `1d9c:8339` and `05c6:9008` on that origin, in a Bubblewrap profile).
  Harness-only changes, as for Rescue: the chooser was replaced by the one
  policy-granted device of the requested vendor, the page's fresh RSA key by
  the desktop's authorized adb key, downloads went to a folder the harness
  then chose from, and `qcserial` was detached from the EDL interface each
  time. Run twice; the second run went from start to finish with no retry:
  - **Stock boot** checked `boot-stock.img` against a manifest (hash, stock
    signature, not patched), matched serial and build over adb, restarted into
    EDL, read the Magisk boot, wrote the 32 MiB stock image, read all of it
    back identical, reset. Android booted with no `su`.
  - **Root** from that stock phone: identity and `reboot:edl`; two reads of
    `boot`, identical, SHA-256 `489269c4…9455` (the known stock), stock
    signature verified; the downloaded image was chosen again and matched;
    patched on the phone in about 3 minutes (`- Stock boot image detected`,
    `- Patching ramdisk`, `- Repacking boot image`), SignBoot `VALID`, the
    19,641,640-byte image checked in the browser; Magisk 24.0 and Wave 0.7.30
    installed; written over EDL with the whole partition read back as
    expected; `/sbin/su -> ./magisk` on the next boot; `su -c id` for adb
    (Shell was already allowed in this phone's policy table, so no prompt
    appeared); Wave opened on the phone.
  - **Independent check:** bkerler/edl read `boot` afterwards: the first
    19,641,640 bytes verify as a signed patch of stock and are SHA-256
    `49eb168c…7d81d`, the same image as the November hand-made one; the
    padding is zero, the rest is stock, and the whole partition is
    `648f3508…5d93`, as before. Root survives restarts; Magisk's policy table
    still holds only uid 2000.
  - Each EDL step took 10 to 25 s (reading 32 MiB about 8 s, writing 19.6 MB
    about 2 s).
- **Not tested:** the device chooser and "Allow USB debugging?" prompt in a
  normal browser profile, Magisk's Shell prompt and "Requires Additional
  Setup" on a phone that never had Magisk (this phone's `/data/adb` survived
  the stock write-back), the cold-start path into Stock boot, an induced
  readback mismatch on hardware (covered by replays), and macOS, Windows,
  ChromeOS or Android OTG. Firefox and Safari do not supply WebUSB. See the
  site's USB guide for Linux udev/ModemManager and Windows WinUSB.

Credit for the XP3800 research goes to anonymousfliphones and flipphoneguy
on JTech Forums and the `flipphoneguy/root-sonim-xp3800` community project.
[Magisk 24.0 source](https://github.com/topjohnwu/Magisk/tree/v24.0) is GPL-3.0.
