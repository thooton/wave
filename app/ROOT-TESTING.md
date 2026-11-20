# Rooted handset checks

Use `./gradlew :app:core:selfTest` first. It exercises the install engine and
watchdog against a fake phone; set `WAVE_TEST_SH="/path/to/busybox ash"` and
use `--rerun-tasks` to also check the Magisk shell. This guide is for real
Magisk, package installs and boot behavior. For UI-only checks, use the
[handset smoke checklist](../tools/qa/README.md).

## Before you start

- Use a reserved XP3800 with Magisk 24.0 and a verified stock boot backup.
  Hold the [phone lock](../tools/qa/README.md) throughout, including reboots.
  Do not use a phone with unbacked-up data or overwrite a Wave build without
  approval. Never write `/system`.
- Know how to recover before arming the broken module. Try Magisk safe mode
  (hold side Volume Down from power-on until the boot animation; the physical
  key path is still unverified here). If adb and root work, mark the test
  module for removal and reboot. If neither works, use the site's Recovery
  page with this phone's verified stock backup.
- Stop and collect logs if a result differs. Do not run the broken-module
  checks until the good module has booted and the watchdog has settled it.
- Wait two minutes after Home before ordinary `adb reboot` calls. An adb or
  shell reboot during a trial may skip the Android shutdown signal and count
  as a failed boot. The clean-restart check below deliberately uses Android's
  shutdown path during that window.

## Setup

In the locked shell, set `S` to the reserved serial shown by `adb devices`.
Build with the [app's SDK and JDK setup](AGENTS.md), from the repository root:

```sh
./gradlew :app:app:assembleDebug
adb -s "$S" install -r app/app/build/outputs/apk/debug/app-debug.apk
app/root-tests/build.sh
python3 -m http.server 8765 --bind 127.0.0.1 --directory app/root-tests/build &
fixture_server=$!
adb -s "$S" shell su -c id  # expect uid=0(root); allow the adb shell if asked
```

Define these helpers in the same shell. The debug activity is adb-only.
`p` reinstates `adb reverse` because the phone drops it on reboot.

```sh
d() { # d ACTION [RESULT_PATTERN] [TIMEOUT_SECONDS]
  adb -s "$S" logcat -c
  adb -s "$S" shell am start -W --activity-clear-task -n com.wave.xp3/.DebugActivity --es do "$1" >/dev/null
  for ((t=0; t<${3:-30}; t++)); do
    adb -s "$S" logcat -d -s WaveDebug | grep -Eq "${2:-exit |error:|unknown:}" && break
    sleep 1
  done
  adb -s "$S" logcat -d -s WaveDebug
}
p() { # p PLAN
  adb -s "$S" reverse tcp:8765 tcp:8765 >/dev/null &&
  adb -s "$S" push "app/root-tests/build/$1.json" /sdcard/Android/data/com.wave.xp3/files/plan.json >/dev/null &&
  d plan 'finished;|refused:|busy|error:|no plan' 600
}
```

Collect state with `d facts`, `d modules`, `d watchdog`, `adb -s "$S" logcat -d`,
and the listing and log in `/data/adb/wave` (read with `adb -s "$S" shell su -c`).

## Install engine

Run in this order; `p` prints the result for each package and a final
`finished; succeeded=... reboot=... self=...` line.

| Check | Expected |
| --- | --- |
| Remove any existing Wave grant in Magisk. Run `d facts`, deny the prompt, then force-stop Wave and grant on the next run or in Magisk's Superuser screen. | Denial reports root was refused. A grant reports `exit 0` and Magisk 24.0. |
| `p apk-install`, `p apk-update`, `p apk-remove` | `com.wave.xp3.testapp` goes from 1.0 to 2.0 to absent. There is no Android install/uninstall prompt or `installerPackageName`; Wave's `files/receipts.json` records the installed version and drops it after removal. |
| `p bad-hash`, `p bad-package` | Each fails validation. No APK is installed and `cache/downloads` is empty (`adb -s "$S" shell run-as com.wave.xp3 ls cache/downloads`). |
| `p big-download`, removing `adb reverse` for `tcp:8765` in another shell as soon as `plan:` appears | Connection/download failure with no cached file. If it completes first, "isn't a module" is expected for the dummy ZIP; retry for an interrupted download. |
| `p no-space` | Insufficient-space failure with no cached file. Skip if `/data` has enough space for the fixture (about 505 MB); don't fill it for the test. |
| `p module-install`, reboot, wait two minutes | Plan needs a reboot. `d facts` reports the same nonempty watchdog version for both halves, and both `wave-watchdog.sh` scripts under `/data/adb/post-fs-data.d` and `/data/adb/service.d` are root-owned and executable. After boot, `/data/adb/wave_test_ok.mounted` says `version=1.0`, both `pending` and `trying` are gone, and the log says `boot settled`. |
| `p module-update`, reboot and wait, then `p module-remove` and reboot | The marker says `version=1.1` after the update; the module directory and marker are gone after removal. |
| `p apk-install`, then remove Wave Test App through Installed → Menu → Remove | The plan finishes and the APK is gone without an Android uninstall prompt. |

To test self-update, build a second debug APK signed with the same key and
with higher `versionCode` and `versionName` in a scratch checkout. Run
`WAVE_NEXT_APK=/path/to/next.apk app/root-tests/build.sh`, then `p self-update`.
Wave should reopen itself with the new version and show the update toast.
If it doesn't, inspect `/data/adb/wave/self-update` and `d self-result`.

## Boot watchdog

1. **Timeout.** Run `p broken-install`, verify `wave_test_broken` is pending,
   then arm it and reboot on the reserved test phone:
   ```sh
   adb -s "$S" shell su -c 'touch /data/adb/wave_test_broken.armed'
   adb -s "$S" reboot
   ```
   Allow eight minutes: the watchdog reboots once after a five-minute timeout.
   The next boot should finish with a `disable` flag on the broken module and
   `boot-timeout` in `/data/adb/wave/disabled`. Use recovery if it keeps rebooting.
2. **Alert and re-enable.** Open Wave: OK is focused, Left reaches Remove It,
   and Installed marks the mod as turned off. OK acknowledges the alert but
   leaves the module disabled. Use Installed → Menu → Turn On, reboot, and
   wait for the unarmed module to settle. Remove it and reboot again.
3. **Incomplete trial.** Install the good module again. On a dedicated test
   handset, interrupt the trial boot during the boot animation before Home.
   The next boot should show `boot-incomplete` and the module disabled. Use
   Remove It from the alert, confirm and reboot to verify removal.
4. **Magisk safe mode.** Install the good module, reboot and let it settle,
   then boot with Volume Down held. Magisk should disable it without a Wave
   watchdog entry or alert. For a property-only test, set
   `persist.sys.safemode=1` as root before rebooting, then boot normally again
   before opening Wave. Record which path was used.
5. **Clean restart.** Put the good module back on trial with `p module-install`
   and reboot. Within a minute of Home, run
   `adb -s "$S" shell su -c 'svc power reboot'` (not `adb reboot`). The module
   stays enabled and the log says `shutdown asked for after boot completed`.

Previously verified on XP3800 firmware `3A.0.1-02-8.1.0-11.20.00` with Magisk
24.0. The safe-mode property and an immediate reboot were tested, not the
physical key or a real power cut. The self-update installed and relaunched;
the toast wasn't observed. The no-space check was skipped for lack of a
suitable handset. Record the commit, firmware, Magisk version, pass/fail or
skips, and logs for any failure with the change being tested.

## Clean up

After collecting logs and outside the watchdog trial window:

```sh
adb -s "$S" shell su -c 'for m in wave_test_ok wave_test_broken; do [ ! -d /data/adb/modules/$m ] || touch /data/adb/modules/$m/remove; done; rm -f /data/adb/wave_test_*'
adb -s "$S" uninstall com.wave.xp3.testapp
adb -s "$S" reverse --remove tcp:8765
adb -s "$S" shell rm -f /sdcard/Android/data/com.wave.xp3/files/plan.json
adb -s "$S" reboot
kill "$fixture_server"
```

Leave Wave's watchdog and `/data/adb/wave` in place. Revoke the test grant
in Magisk's Superuser screen before uninstalling a test-only Wave build;
Android can reuse the UID and uninstalling alone may leave the grant behind.
