# The jailbreak

A static page, served from GitHub Pages out of `site/`, that roots an XP3800
from Chrome over WebUSB and leaves Wave on the launcher. It automates the
manual method in `../rphone/ROOTING.md` (read that first; every trap it lists
applies here). No software is installed on the computer.

## What stays true from the manual method

- **Magisk 24.0 exactly.** Other versions haven't been shown to boot.
- **The boot image is patched on the phone.** A PC-side patch black-screens.
  The page doesn't ask the user to tap through the Magisk app: it pushes
  Magisk 24.0's own ARM binaries (`magiskboot`, `magiskinit`, `magisk32`,
  `busybox`, `boot_patch.sh`, `util_functions.sh`) to `/data/local/tmp` and
  runs `boot_patch.sh` there over adb, which is what the app does when it
  patches a file. This equivalence is the first thing to prove on a real
  phone (`tasks/09-site-jailbreak.md`).
- **The phone's own boot image.** Never a downloaded one. Firmware builds
  differ; patching what is on the phone works for all of them.
- **EDL is the only way to write.** The bootloader stays locked. The signed
  loader `prog_emmc_firehose_8909_ddr.mbn` is uploaded over Sahara, then
  Firehose reads and writes `boot`, found by name in the GPT, never by a
  hardcoded sector.
- **`/system` is never touched.** Everything after this is systemless, so
  flashing the saved boot image back is a complete undo.

## The flow

WebUSB needs a click and a device chooser each time a new USB device appears,
and the phone is a different device in Android (adb) and in EDL
(`05c6:9008`). So the page is a sequence of steps, each with one button, and
it resumes where it was if the tab is reloaded.

| # | Step | Over | Checks |
| :- | :--- | :--- | :--- |
| 0 | On the phone: enable Developer options and USB debugging (pictures on the page) | | |
| 1 | Connect; accept the RSA prompt on the phone | adb | Model is XP3800, SDK 27, 32-bit ARM, battery at least 40% |
| 2 | Already rooted? (`su -c id`) Then skip to step 9 | adb | |
| 3 | Reboot to EDL; connect again | adb, then Sahara | Device answers Sahara hello; loader accepted |
| 4 | Read `boot`, hand it to the user as a download, keep a copy in the browser (OPFS) | Firehose | Starts with `ANDROID!`; size equals the partition's |
| 5 | Reset to Android; reconnect | Firehose, adb | Boot completes |
| 6 | Push the Magisk binaries and the boot image; run `boot_patch.sh`; pull `new-boot.img` | adb | Exit status; result starts with `ANDROID!` and differs from stock |
| 7 | Reboot to EDL; write the patched image to `boot`; read it back | Firehose | Read-back equals what was written, over the length written |
| 8 | Reset; wait for Android | adb | `su -c id` is uid 0. A phone that doesn't come back gets the Restore instructions |
| 9 | Install the Magisk app and Wave; grant Wave root in Magisk's policy table; install Wave's watchdog scripts; open Wave | adb | Wave reports root |

The page serves everything it needs from its own origin, because release
downloads on GitHub can't be fetched cross-origin from a browser: the
firehose loader, the Magisk 24.0 APK and its extracted binaries, and the
current Wave APK (copied in by CI when Wave is released).

## Rescue and Restore

- **Rescue** (the phone boots far enough for adb): disable every Magisk
  module, or one, and reboot. For a mod that broke the home screen.
- **Restore** (remove the jailbreak, or the phone doesn't boot): write the
  saved stock boot image back over EDL and verify. The page walks through the
  cold-start route into EDL when Android doesn't boot (battery out, hold both
  volume keys, battery in, plug in), and explains `05c6:900e`, which is not
  EDL. Optionally removes `/data/adb` and the Magisk and Wave apps.

## Platforms

| Platform | Works | Notes |
| :--- | :--- | :--- |
| Chrome or Edge on macOS, ChromeOS | yes | Nothing to set up |
| Chrome on Linux | yes | Needs a udev rule for `05c6:9008` and the phone's adb interface; ModemManager grabs 9008 and must be told not to. The page shows the two lines |
| Chrome on Windows | yes, with a driver step | WebUSB only sees a device bound to WinUSB. EDL shows up under Qualcomm's serial driver or none; the user binds WinUSB with Zadig once. The page detects Windows and explains |
| Chrome on Android (another phone, USB-OTG) | probably | Worth testing: it would make the jailbreak possible without a computer |
| Firefox, Safari | no | No WebUSB |

## Building blocks

The site is the one place third-party code is allowed (the phone apps have
none). Decide in the brief, after a spike:

- **adb over WebUSB**: Tango (`@yume-chan/adb`, MIT) or a small client of our
  own. We need connect and auth, shell with exit status, push, pull, install,
  reboot.
- **Sahara and Firehose over WebUSB**: `bkerler/edl` (Python) is the
  reference the manual method uses; `commaai/qdl.js` (MIT) is a WebUSB
  implementation written for another chipset. We need Sahara loader upload,
  Firehose configure for eMMC with 512-byte sectors, GPT read, read and
  program by partition name, and reset.
- The loader is Sonim's signed binary and the Magisk APK is GPL-3.0; the page
  links Magisk's source and credits both.

## Credits

The method is the community's: anonymousfliphones and flipphoneguy on JTech
Forums (`flipphoneguy/root-sonim-xp3800`). The page says so and links them.
