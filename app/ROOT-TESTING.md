# Testing Wave's root side on a phone

Everything in `core/root` and `core/install` is tested on the desktop
(`./gradlew :app:core:selfTest`): the scripts Wave sends run against a fake
`su` and a pretend `/data/adb`, and the boot watchdog's two scripts run
through whole pretend boots. To run them under the shell Magisk uses:

```sh
WAVE_TEST_SH="/path/to/busybox ash" ./gradlew --rerun-tasks :app:core:selfTest
```

What only a rooted XP3 can show is below. **None of it has been run yet**:
the test phone isn't rooted. Run it once one is, and record the results in
`docs/tasks/README.md` under 06 and 08.

## Ground rules

- A rooted test phone, never one with the owner's data on it that isn't
  backed up. Magisk 24.0, and the stock boot image saved (the site's
  Restore puts it back if anything goes wrong).
- Nothing here writes `/system`. The test modules write only their own files
  in `/data/adb`; the test app is `com.wave.xp3.testapp`.
- Know the ways out before the watchdog test: Magisk's safe mode (hold
  Volume Down for three seconds while the phone boots), the site's Rescue,
  or `adb shell su -c 'touch /data/adb/modules/<id>/remove' && adb reboot`.
- Clean up at the end (last section).

## Setup

```sh
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :app:app:assembleDebug
S=<serial>
adb -s $S install -r app/app/build/outputs/apk/debug/app-debug.apk
app/root-tests/build.sh                       # test modules, test app, plans
(cd app/root-tests/build && python3 -m http.server 8765) &
adb -s $S reverse tcp:8765 tcp:8765           # the phone's 127.0.0.1:8765
adb -s $S shell su -c id                      # uid=0(root): rooted
```

The debug build has `DebugActivity`, which only adb can start (it needs
`android.permission.DUMP`). It prints to its screen and to logcat:

```sh
d() { adb -s $S shell am start -W -n com.wave.xp3/.DebugActivity --es do "$1"; sleep 3; adb -s $S logcat -d -s WaveDebug | tail -n 30; adb -s $S logcat -c; }
p() { adb -s $S push app/root-tests/build/$1.json /sdcard/Android/data/com.wave.xp3/files/plan.json && d plan; }
```

`d facts | modules | watchdog | install-watchdog | ack | self-result`, and
`p <plan>` runs one of the plans `build.sh` wrote through `EngineService`
(the same engine the screens use); its result is logged when it finishes
(`d plan` again or `adb logcat -s WaveDebug` to see it, as a download can
take a moment).

## 06: the install engine

1. **Root is asked for, not taken.** On a phone where Wave was never
   allowed: `d facts`. Magisk's prompt appears; deny it. Expect
   `error: ... Root was refused. Open Magisk, Superuser, and allow Wave.`
   Then allow Wave in Magisk's Superuser list and `d facts` again: expect
   `magisk=24.0:MAGISK`, `magiskCode=24000`, empty watchdog lines.
2. **APK install, update, remove.** `p apk-install`: expect
   `finished; succeeded=true`, and `adb shell dumpsys package
   com.wave.xp3.testapp | grep versionName` says 1.0. `p apk-update`: 2.0.
   `p apk-remove`: success and the package gone. Check
   `adb shell run-as com.wave.xp3 cat files/receipts.json`
   lists the test app after install and not after removal.
3. **Checks before installing.** `p bad-hash`: FAILED, "The download
   doesn't match the source." `p bad-package`: FAILED, "The download is
   com.wave.xp3.testapp, not com.wave.xp3.testother." Neither installs
   anything; `run-as com.wave.xp3 ls cache/downloads` is empty.
4. **Resume.** `p big-download`, and while it downloads stop the server
   (or `adb reverse --remove tcp:8765`): FAILED, "The download was cut
   short." (or the network message), and `run-as com.wave.xp3 ls -l
   cache/downloads` shows nothing (the engine cleans up when a run ends).
   Resume itself is covered by the desktop tests; on the phone check only
   that nothing is left behind. It then fails as "The download isn't a
   module." when it completes, which is expected: big.zip is noise.
5. **Out of space.** `p no-space`: FAILED, "Not enough space: it needs
   505 MB." (the XP3 has less free). Nothing left in the cache.
6. **A module, and the watchdog put in place.** `p module-install`: expect
   `Boot watchdog 1 installed.`, `reboot=true`, and:
   ```sh
   adb -s $S shell su -c 'ls -l /data/adb/post-fs-data.d /data/adb/service.d; cat /data/adb/wave/pending; ls /data/adb/modules_update /data/adb/modules/wave_test_ok'
   ```
   Both scripts `-rwxr-xr-x root root`, `pending` says `wave_test_ok`, the
   module waits in `modules_update` and `modules/wave_test_ok` has `update`.
   `d modules`: `@modules_update wave_test_ok`. Reboot (`adb reboot`).
   After boot, wait two minutes, then: `/data/adb/wave_test_ok.mounted`
   exists (the module ran), `/data/adb/wave/pending` and `trying` are gone,
   `/data/adb/wave/log` says `boot settled ... modules kept`.
7. **Module update and removal.** `p module-update`, reboot, then the
   marker says `version=1.1`. `p module-remove`: `modules/wave_test_ok/
   remove` exists; reboot; the module and its marker are gone.
8. **Wave updates itself.** Build a second debug APK with a higher
   versionCode, serve it, and write a plan installing `com.wave.xp3` from
   it (copy `apk-install.json`, change id, version, file, size, sha256).
   `p` it: Wave closes, and comes back by itself within a minute; `d
   self-result` once shows `Success` (the main screen's toast "Wave is up
   to date" also reads it, so it may already be empty).
9. **The Updater's screen through root.** In the app, update any listed
   app: no Installer screen appears; it installs directly.

## 08: the boot watchdog

Before this, the watchdog is in place from step 6 above (`d facts` shows
`watchdogPfd=1`, `watchdogService=1`).

1. **A broken mod is turned off.** `p broken-install`, then arm it and
   reboot:
   ```sh
   adb -s $S shell su -c 'touch /data/adb/wave_test_broken.armed'
   adb -s $S reboot
   ```
   The module stops the framework from starting, so the phone sits at the
   boot animation. About five minutes after boot started the watchdog
   reboots it (one reboot), and the second boot completes normally. (The
   module also disarms itself first and gives the boot back after six
   minutes, so this boot ends even if the watchdog doesn't work.) Then:
   ```sh
   adb -s $S shell su -c 'cat /data/adb/wave/disabled /data/adb/wave/log /data/adb/wave_test_broken.log; ls /data/adb/modules/wave_test_broken'
   ```
   Expect `disabled` with `wave_test_broken	boot-timeout`, a `disable`
   file in the module's folder, and the log lines `boot not completed after
   300s; rebooting once`, then `disabled wave_test_broken (boot-timeout)`.
2. **Wave says so.** Open Wave: a dialog "Wave Test Broken was turned off"
   with Remove, Try Again and Leave Off. Leave Off; `d watchdog` shows
   nothing under `@disabled` (moved to `disabled.seen`).
3. **Try Again and Remove.** `adb shell su -c 'echo
   "$(date +%s)	wave_test_broken	boot-timeout" >> /data/adb/wave/disabled'`
   and open Wave again: Try Again turns the module on again and adds it to
   `pending` (it is disarmed, so the next boot settles and keeps it). Do the
   same and press Remove: `modules/wave_test_broken/remove`; reboot; gone.
4. **An incomplete boot.** Install `wave_test_ok` again (`p
   module-install`), reboot, and pull power (or hold the power key until it
   restarts) at the boot animation, before the home screen. On the next
   boot it is disabled with `boot-incomplete`. Remove it afterwards.
5. **Magisk's safe mode.** With `wave_test_ok` installed and settled, hold
   Volume Down for three seconds while the phone boots (find when on the
   XP3: from the Sonim logo). Every module comes up disabled. Open Wave:
   "Every mod is off", pick one to turn it back on. Record which key timing
   worked.
6. **A normal restart doesn't cost a good mod.** Install `wave_test_ok`,
   reboot, and as soon as the home screen appears restart from the power
   menu (within the minute). The module stays enabled.

## Clean up

```sh
adb -s $S shell su -c 'touch /data/adb/modules/wave_test_ok/remove /data/adb/modules/wave_test_broken/remove 2>/dev/null; rm -f /data/adb/wave_test_*'
adb -s $S uninstall com.wave.xp3.testapp
adb -s $S reverse --remove tcp:8765
adb -s $S shell rm -f /sdcard/Android/data/com.wave.xp3/files/plan.json
adb -s $S reboot
```

Leave the watchdog and `/data/adb/wave` in place: they belong to Wave. To
take them out too: `rm -f /data/adb/post-fs-data.d/wave-watchdog.sh
/data/adb/service.d/wave-watchdog.sh; rm -rf /data/adb/wave`.
