# Testing Wave's root side on a phone

Everything in `core/root` and `core/install` is tested on the desktop
(`./gradlew :app:core:selfTest`). The scripts Wave sends run against a fake
`su` and a pretend `/data/adb`, and the boot watchdog's two scripts run
through whole pretend boots. To run them under the shell Magisk uses:

```sh
WAVE_TEST_SH="/path/to/busybox ash" ./gradlew --rerun-tasks :app:core:selfTest
```

This file is for what only a rooted XP3 can show. Follow it top to bottom,
in order: later steps rely on earlier ones (06.1 lets Wave use root, 06.6
puts the watchdog in place for 08).

**Status.** None of the rooted steps have been run, because the test phone
isn't rooted. On 2026-11-19 the harness was dry-run on the stock phone
(`8516067e`, Wave 0.7.15 debug), and it works:
- the debug build installs, and `d` and `p` drive `DebugActivity`;
- the fixtures are served through `adb reverse`, and `run-as` reads the
  receipts;
- without root, the APK plans went through Installer with the messages shown
  below: install, update, remove (Android's uninstall prompt), bad hash and
  bad package;
- `d facts` said "This phone has no root".

Record each run's results in `docs/tasks/README.md` under 06 and 08 (see
"Recording" at the end).

## What is already proven, and what is assumed

Two harnesses in `app/root-tests/magisk/` check the root side against the
real Magisk 24.0 (from `../rphone/Magisk-v24.0.apk`) before any rooted
phone is involved. Rerun both after changing anything in `core/root`:

```sh
app/root-tests/magisk/sim.py            # desktop, about 6 minutes (--fast: 2)
app/root-tests/magisk/phone.sh <serial> # any XP3, rooted or not, 30 seconds
```

**`sim.py`** boots a pretend phone in a `bwrap` sandbox as often as each case
needs. These parts are real:
- the shell and its applets: Magisk's own busybox, taken from the APK. The
  x86_64 build runs on the desktop; its applet table is byte-identical to
  the armeabi-v7a build the XP3 gets, and `sim.py` checks that each run;
- module installs: `magisk --install-module` runs the APK's
  `util_functions.sh` `install_module` unchanged;
- Wave's scripts, exactly as the app builds them (`Gen.java`), and the
  `root-tests` modules;
- what magiskd does at each stage (`boot.sh`, copied step by step from
  v24.0's `bootstages.cpp`, `scripting.cpp` and `module.cpp`):
  - the environment: `ASH_STANDALONE=1`, and `PATH` is init's plus `:/sbin`;
  - post-fs-data.d runs only regular executable files, waiting for them at
    most 35 seconds in all, before `modules_update` is moved into place and
    the modules are collected;
  - service.d runs in the background at late_start;
  - safe mode writes `disable` into every module and runs no scripts;
  - `remove` runs the module's `uninstall.sh`;
- the layout: `/data/adb`, and `/sbin/.magisk/busybox/busybox`. The XP3
  isn't system-as-root, so Magisk's tmpfs is `/sbin`.

These parts are pretend: `getprop`, `setprop`, `/system/bin/reboot`, zygote
and `system_server`, and mounting. The cases, all passing:
- a good module settles;
- the broken module hangs the boot, the watchdog reboots once, and the
  module is turned off (`boot-timeout`);
- a framework that keeps restarting leads to one reboot and the module
  turned off;
- a power cut gives `boot-incomplete`, and Turn On brings the module back;
- a user restart right after boot keeps the module;
- safe mode leaves the watchdog out, and Turn On recovers;
- a slow neighbour in post-fs-data.d (see the risks below).

**`phone.sh`** runs on the real XP3 as the shell user, only inside
`/data/local/tmp/wave-sim`, and removes it afterwards. On `8516067e`
(stock, Android 8.1) all 24 checks passed on 2026-11-19. That run used
watchdog version 2; version 3 changes only the default poll interval,
which `phone.sh` sets itself. These parts are
real:
- Wave's root scripts under the phone's own `/system/bin/sh` (mksh R55),
  its toybox and its `grep`, against a pretend `/data/adb`;
- both watchdog halves under Magisk's armeabi-v7a busybox on the XP3's
  kernel, with the phone's real `getprop sys.boot_completed` and `pidof
  system_server`. The settle path ended in "boot settled".

`setprop`, `reboot`, `chown` and `magisk` are stubs there that only record
the call, so nothing can reboot or change the phone.

**Found and fixed: a user restart could cost a good mod.** The late half
looked every five seconds. Android sets `sys.shutdown.requested` only a few
seconds before the phone goes down, so a restart in the first minute after
the home screen was often missed, and the next boot turned the module off
as `boot-incomplete`. `sim.py` caught it at the real timings; the desktop
tests hadn't, because they keep the property set. The watchdog now looks
every second (version 3). Step 08.6 is the check on hardware.

**Proven: nothing on a device should break.**
- Every command the watchdog runs is an applet of Magisk's busybox, except
  `getprop` and `setprop`. Those are in the XP3's `/system/bin`, which is
  on magiskd's `PATH`, as is `/system/bin/reboot`.
- Every command Wave's root scripts use exists on the XP3. The XP3 has no
  `unzip`, but Wave doesn't use it; Magisk's installer uses its busybox's.
- The watchdog reads no variable from Magisk. Post-fs-data.d and service.d
  scripts get only `ASH_STANDALONE`, `PATH` and maybe `ZYGISK_ENABLED`;
  `MODDIR` is for module scripts. The `WAVE_*` settings are for tests, so
  on a phone the defaults apply.
- Magisk ignores these scripts' exit status. Even so, both halves exit 0
  in every case.
- `/data/adb` is usable at post-fs-data. The XP3 reports
  `ro.crypto.state=unencrypted`, so there is no wait for decryption.
- Installing a module, which is how Wave updates one, clears
  the module's `disable`: Magisk's `install_module` deletes it. So
  updating a mod the watchdog turned off brings it back, on trial.
- The safe-mode key is visible to Magisk: see "The ways out".

**Risks, accepted for now.**
- *A slow neighbour.* If another post-fs-data.d script runs first and
  takes over 35 seconds, Magisk stops waiting. The watchdog still runs, but
  races the module collection, so a `disable` it writes can come one boot
  late. A broken mod would then hang that boot once more, with no forced
  reboot, because `trying` is already cleared; the boot after that is
  clean. Magisk runs these scripts in directory order, which isn't sorted.
  Nothing Wave installs blocks.
- *`adb reboot` in the first minute.* A reboot that doesn't go through
  Android's shutdown (`adb reboot`, or `reboot` in a root shell) may not
  set `sys.shutdown.requested`. One in the first minute after the home
  screen then counts as an incomplete boot for a mod on trial. That's why
  the steps below wait two minutes before such a reboot.

**Still assumptions, only a rooted XP3 can settle them.** These are the
point of the steps below:
- Magisk's `su`: the prompt, the remembered grant, and that Wave's scripts
  run as root in Magisk's SELinux domain and can write `/data/adb` (06.1,
  06.6);
- `pm install` and the self-update through root (06.2, 06.8);
- that the broken module's `setprop ctl.stop zygote` really holds the boot
  at the animation, and that `sys.boot_completed` stays unset meanwhile
  (08.1);
- that `setprop sys.powerctl reboot` from Magisk's context reboots the XP3,
  and how long its boots take against the five-minute limit (08.1);
- how long Android takes from `sys.shutdown.requested` to going down
  (08.6);
- the safe-mode key on the hardware (08.5).

## Before you start

- **The phone.** Use a rooted test phone, never one holding the owner's data
  unless that data is backed up. It needs Magisk 24.0 and a saved copy of
  the stock boot image (the site's Restore puts it back).
- **Time.** About two hours. Section 08 has several reboots of up to eight
  minutes each.
- **What gets written.** Nothing here writes `/system`. The test modules
  write only their own files in `/data/adb`. The test app is
  `com.wave.xp3.testapp`.
- **The ways out, before 08.** Know all three:
  - Magisk's safe mode: hold the side Volume Down key from power-on until
    the boot animation shows. Magisk 24.0 checks once, when post-fs-data
    starts: the key must already be down then and stay down for 3 seconds
    (`check_key_combo` in `bootstages.cpp`). On the XP3 that key is on
    `qpnp_pon` (`getevent -p` lists 0072, KEY_VOLUMEDOWN), so Magisk can
    see it. 08.5 confirms it on the phone.
  - The site's Rescue.
  - From adb, once the phone is up:
    `adb shell su -c 'touch /data/adb/modules/<id>/remove' && adb reboot`.
- **When something doesn't match.** Stop at that step and don't carry on
  hoping it sorts itself out. Collect the state (below), write down the step,
  what you saw and what you expected, then do the cleanup. A failure in 06.6
  means the watchdog isn't in place: **don't start 08**.

Collecting the state, at any point:

```sh
d facts; d modules; d watchdog
adb -s $S shell su -c 'ls -la /data/adb/wave /data/adb/post-fs-data.d /data/adb/service.d; cat /data/adb/wave/log'
adb -s $S logcat -d > wave-logcat.txt
```

## Setup

Build from a clean checkout of the commit you are testing:

```sh
S=<serial>                                    # adb devices
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :app:app:assembleDebug
adb -s $S install -r app/app/build/outputs/apk/debug/app-debug.apk
app/root-tests/build.sh                       # test modules, test app, plans
(cd app/root-tests/build && python3 -m http.server 8765 --bind 127.0.0.1) &
adb -s $S reverse tcp:8765 tcp:8765           # the phone's 127.0.0.1:8765
adb -s $S shell su -c id
```

Expect:
- `Success` from the install;
- `build.sh` lists the zips, `testapp-1.0.apk`, `testapp-2.0.apk`, `big.zip`
  and twelve `*.json` plans;
- the last line says `uid=0(root)`. Magisk may ask about the shell first:
  allow it.

If not:
- An install refused for the signature means another Wave is on the phone:
  uninstall it first, after checking it holds nothing you need.
- `su: not found` means the phone isn't rooted.

The debug build adds `DebugActivity`, which only adb can start (it needs
`android.permission.DUMP`). Define these two helpers in the same shell:

```sh
d() { # d ACTION [WAIT_FOR] [SECONDS]: runs a DebugActivity action and prints its log
  adb -s $S logcat -c
  adb -s $S shell am start -W --activity-clear-task -n com.wave.xp3/.DebugActivity --es do "$1" >/dev/null
  t=0; until adb -s $S logcat -d -s WaveDebug | grep -q "${2:-exit \|error:\|unknown:}"; do
    sleep 1; t=$((t+1)); [ $t -ge ${3:-30} ] && break; done
  adb -s $S logcat -d -s WaveDebug | grep WaveDebug | cut -d' ' -f7-
}
p() { # p PLAN: runs app/root-tests/build/PLAN.json through EngineService and waits for the result
  adb -s $S push app/root-tests/build/$1.json /sdcard/Android/data/com.wave.xp3/files/plan.json >/dev/null &&
  d plan 'finished;\|refused:\|busy\|error:\|no plan' 600
}
```

- `d` takes the actions `facts`, `modules`, `watchdog`, `install-watchdog`,
  `ack` and `self-result`.
- `p <plan>` runs a plan through the same engine the screens use. It prints
  `plan: [...]`, then `finished; succeeded=... reboot=... self=...` and one
  line per item.

`--activity-clear-task` matters: without it, a second `am start` only brings
the old screen to the front, and nothing runs.

## 06: the install engine

**06.1 Root is asked for, not taken.** Wave must never have been allowed root
on this phone. If it has, remove it from Magisk > Superuser first.

- Do: `d facts`. When Magisk's prompt appears on the phone, choose Deny.
  - Expect: `error: ...NoRoot: Root was refused. Open Magisk, Superuser, and
    allow Wave.`
- Do: `adb -s $S shell am force-stop com.wave.xp3`, then `d facts` again.
  This time choose Grant, or allow Wave in Magisk > Superuser beforehand.
  - Expect: `exit 0`, `magisk=24.0:MAGISK` (Magisk's own version string),
    `magiskCode=24000`, and empty `watchdogPfd=` and `watchdogService=`.
    Wave now remembers that root works, so from here its APK installs use
    `pm` through root, not Installer.
- If not:
  - No prompt and an immediate refusal means Magisk is set to deny
    automatically: change it in Magisk's settings.
  - A wait that ends in a timeout means the prompt wasn't answered in time.

**06.2 APK install, update, remove.**

- Do: `p apk-install`
  - Expect: `INSTALL com.wave.xp3.testapp: DONE` and `succeeded=true`. Then
    `adb -s $S shell dumpsys package com.wave.xp3.testapp | grep -E
    'versionName|installerPackageName'` shows `versionName=1.0` and
    `installerPackageName=null`. Null means `pm` installed it through root.
    `com.installer.xp3` would mean it went through Installer, the stock
    path: in that case 06.1 didn't take.
  - Also: `adb -s $S shell run-as com.wave.xp3 cat files/receipts.json`
    lists `com.wave.xp3.testapp` at version `1.0`, source `root-tests`.
- Do: `p apk-update`
  - Expect: `UPDATE com.wave.xp3.testapp: DONE`, and now `versionName=2.0`.
- Do: `p apk-remove`
  - Expect: `REMOVE com.wave.xp3.testapp: DONE` with **no** uninstall prompt
    on the phone. On stock, Android asks "Do you want to uninstall this
    app?". `pm path com.wave.xp3.testapp` prints nothing, and the receipts
    file reads `{"schema":1,"packages":{}}`.
- If not: the item's line has the reason (pm's message). Record it.

**06.3 Checks before installing.** Run this after 06.2's remove, because a
plan to install 1.0 over 2.0 is refused as a downgrade.

- Do: `p bad-hash`
  - Expect: `FAILED (The download doesn't match the source.)`
- Do: `p bad-package`
  - Expect: `FAILED (The download is com.wave.xp3.testapp, not
    com.wave.xp3.testother.)`
- After both, `pm path com.wave.xp3.testapp` is empty, and `adb -s $S shell
  run-as com.wave.xp3 ls cache/downloads` lists nothing.
- If not: anything installed, or a file left in `cache/downloads`, is a bug.

**06.4 A cut download leaves nothing behind.**

- Do: `p big-download` in one shell. While it downloads (30 MB, a few
  seconds), run `adb -s $S reverse --remove tcp:8765` in another.
  - Expect: `FAILED`, with "The download was cut short." or a "couldn't
    reach" message.
  - Also: `run-as com.wave.xp3 ls -l cache/downloads` lists nothing, because
    the engine cleans up when a run ends.
- Put the reverse back: `adb -s $S reverse tcp:8765 tcp:8765`.
- If the download finished before you cut it: it fails as "The download
  isn't a module." That's expected, since `big.zip` is noise. Only the empty
  cache counts. Resuming is covered by the desktop tests.

**06.5 Out of space.**

- Do: `p no-space`
  - Expect: `FAILED (Not enough space: it needs 505 MB.)`. The XP3 has less
    free than that. Nothing is left in the cache.
- If it downloads instead: the phone has more than 505 MB free. Record the
  free space (`adb shell df /data`) and skip this step.

**06.6 A module, and the watchdog put in place.**

- Do: `p module-install`
  - Expect:
    - `INSTALL wave_test_ok: DONE`, `succeeded=true reboot=true`, and the
      line `Boot watchdog 3 installed.`;
    - `d facts` shows `watchdogPfd=3` and `watchdogService=3`;
    - this command:
      ```sh
      adb -s $S shell su -c 'ls -l /data/adb/post-fs-data.d /data/adb/service.d; cat /data/adb/wave/pending; ls /data/adb/modules_update /data/adb/modules/wave_test_ok'
      ```
      shows `wave-watchdog.sh` in both folders, `-rwxr-xr-x root root`.
      `pending` says `wave_test_ok`. The module waits in `modules_update`,
      and `modules/wave_test_ok` contains `update`;
    - `d modules` shows `@modules_update wave_test_ok`.
- Do: `adb -s $S reboot`. Once the home screen shows, wait two minutes:
  the watchdog needs a minute of steady running.
  - Expect:
    - `adb -s $S shell su -c 'cat /data/adb/wave_test_ok.mounted; ls
      /data/adb/wave; tail -n 5 /data/adb/wave/log'` shows `version=1.0`
      (the module ran);
    - no `pending` or `trying` in `/data/adb/wave`;
    - the log ends with `service: boot settled after Ns; modules kept`.
- If not:
  - No watchdog lines: the scripts weren't installed or aren't executable.
    Collect the state and stop.
  - `trying` still there after five minutes, or the module disabled: the
    watchdog misjudged a good boot. Collect the state and stop.
  - Either way, **don't start 08**.

**06.7 Module update and removal.**

- Do: `p module-update`, then reboot and wait two minutes.
  - Expect: `wave_test_ok.mounted` says `version=1.1`.
- Do: `p module-remove`.
  - Expect: `REMOVE wave_test_ok: DONE`, `reboot=true`, and
    `/data/adb/modules/wave_test_ok/remove` exists.
- Do: reboot.
  - Expect: `/data/adb/modules/wave_test_ok` and
    `/data/adb/wave_test_ok.mounted` are both gone.

**06.8 Wave updates itself.**

1. Build a second debug APK with a higher `versionCode` **and**
   `versionName`, in a scratch copy so the checkout stays clean:
   ```sh
   rm -rf /tmp/wave-next && mkdir /tmp/wave-next && git archive HEAD | tar -x -C /tmp/wave-next
   cp local.properties /tmp/wave-next/
   (cd /tmp/wave-next && sed -i 's/versionCode \([0-9]*\)/versionCode 9\1/; s/versionName "\(.*\)"/versionName "\1.1"/' app/app/build.gradle \
      && JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew -q :app:app:assembleDebug)
   WAVE_NEXT_APK=/tmp/wave-next/app/app/build/outputs/apk/debug/app-debug.apk app/root-tests/build.sh
   ```
   `build.sh` then also writes `self-update.json`.
2. Do: `p self-update`.
   - Expect: Wave closes part way through, so the log may stop after
     `plan:` or show `self=true`. Within a minute Wave opens again by
     itself, with the toast "Wave was updated".
   - `dumpsys package com.wave.xp3 | grep versionName` shows the new
     version. `d self-result` then prints `exit 0` and nothing else,
     because the toast read the result and cleared it.
   - If not:
     - "Wave wasn't updated" with pm's reason: record the reason.
     - If Wave doesn't come back: open it by hand, note what it says, and
       collect `/data/adb/wave/self-update`.

**06.9 Wave's own screens use root.** Do this before the cleanup.

- Do: `p apk-install`, then open Wave from the launcher. On Installed,
  Wave Test App is listed (from its receipt). Press Menu on its row, then
  Remove, then Confirm.
  - Expect: no Android uninstall prompt, the queue ends in Done, and the
    app is gone.
  - If Android's prompt appears: the screens didn't use root although 06.1
    allowed it.

## 08: the boot watchdog

Before you start, `d facts` must show `watchdogPfd=3` and
`watchdogService=3` (from 06.6).

**08.1 A broken mod is turned off.** Only the first boot after a mod is
installed or turned on is judged: Wave lists it in
`/data/adb/wave/pending`, and that boot puts it on trial.

- Do: `p broken-install`.
  - Expect: `INSTALL wave_test_broken: DONE`, `reboot=true`, and
    `/data/adb/wave/pending` says `wave_test_broken`.
- Do: arm it and reboot right away:
  ```sh
  adb -s $S shell su -c 'touch /data/adb/wave_test_broken.armed'
  adb -s $S reboot
  ```
- Expect: the phone sits at the boot animation. The module keeps stopping
  the framework. About five minutes after the boot started, the watchdog
  reboots the phone once. The second boot completes normally, with
  `wave_test_broken` turned off. Allow eight minutes in all. Then:
  ```sh
  adb -s $S shell su -c 'cat /data/adb/wave/disabled; tail -n 8 /data/adb/wave/log; cat /data/adb/wave_test_broken.log; ls /data/adb/modules/wave_test_broken'
  ```
  - `disabled` has one line: `<time>	wave_test_broken	boot-timeout`;
  - the log has `service: boot not completed after 300s; rebooting once`,
    then `post-fs-data: disabled wave_test_broken (boot-timeout)`;
  - the module's folder has a `disable` file.
- If the boot came up on its own after about six minutes: the module gave
  it back and the watchdog never rebooted. `disabled` is missing. The
  watchdog's late half didn't run or didn't count: collect the log and
  stop.
- If the phone keeps rebooting: use a way out (safe mode, Rescue), then
  record exactly what it did. This is the case the design exists to prevent.

**08.2 Wave says so.**

- Do: open Wave.
  - Expect an alert: icon, "Wave Test Broken", "Turned off", and the text
    "With version 1.0: the phone didn't finish starting within five
    minutes after it was installed. Wave turned it off, so the phone starts
    without it." The focus is on OK, and Left reaches Remove It.
  - Installed shows its row as "Turned off: boot failed".
- Do: press OK.
  - Expect: `d watchdog` shows nothing under `@disabled`, because the line
    moved to `/data/adb/wave/disabled.seen`. After Wave reopens, the row
    reads "Turned off".

**08.3 Turn On and Remove It.**

- Do: on Installed, press Menu on its row, then Turn On.
  - Expect: no `disable` file in `/data/adb/modules/wave_test_broken`.
    `/data/adb/wave/pending` lists it. It isn't armed, so the next boot
    settles and keeps it.
- Do: reboot, wait two minutes.
  - Expect: the log says `boot settled`, and the module is enabled.
- Do: bring back the alert by hand. Add the line again, and force-stop
  Wave so it asks again:
  ```sh
  adb -s $S shell su -c 'printf "%s\twave_test_broken\tboot-timeout\n" $(date +%s) >> /data/adb/wave/disabled'
  adb -s $S shell am force-stop com.wave.xp3
  ```
- Do: open Wave, move Left to Remove It and press it, then Confirm.
  - Expect: the queue ends in Done, saying a restart is needed.
    `/data/adb/modules/wave_test_broken/remove` exists.
- Do: reboot.
  - Expect: the module's folder is gone.

**08.4 An incomplete boot.**

- Do: `p module-install` (wave_test_ok, on trial again), then reboot. At the boot
  animation, before the home screen, cut the boot: hold Power until the
  phone restarts, or take the battery out.
- Expect: on the next boot, `disabled` has `wave_test_ok	boot-incomplete`,
  and the log has `disabled wave_test_ok (boot-incomplete)`. Wave's alert
  says "With version 1.0: the phone restarted before it finished starting,
  the first time with it installed. Wave turned it off, so the phone
  starts without it."
- Then press Remove It, Confirm, and reboot, so it's gone before 08.5.
- If the boot came up anyway: the cut came too late. The watchdog only
  judges a boot that never settled. Try again earlier.

**08.5 Magisk's safe mode.**

- Do: `p module-install`, reboot, and wait two minutes, until the log says
  `boot settled`. Then reboot and hold Volume Down from the moment you
  press Power until the boot animation shows (see "The ways out").
- Expect: every module comes up disabled, which is Magisk's own doing.
  `cat /data/adb/wave/disabled` is empty, because the watchdog didn't do
  it.
  - Open Wave: there's no alert. Installed lists Wave Test OK as "Turned
    off", not "boot failed".
  - Menu on its row, then Turn On, then reboot brings it back with a fresh
    `wave_test_ok.mounted`.
- Record which key timing worked. It's the open question in
  `docs/status.md` (Watchdog row).

**08.6 A normal restart doesn't cost a good mod.**

- Do: put wave_test_ok on trial again. Run `p module-remove`, reboot,
  then `p module-install`, so `/data/adb/wave/pending` lists it. Reboot.
  As soon as the home screen appears (within the minute), restart from the
  power menu.
- Expect: the module stays enabled. The log says `service: shutdown asked
  for after boot completed; modules kept`.
- If it was disabled with `boot-incomplete`: a user restart was taken for a
  crash. Record it, and how long the phone took from the power menu's
  Restart to the screen going dark. Watchdog 2 polled every five seconds
  and missed short shutdowns. Version 3 polls every second, so this
  shouldn't happen.

## Clean up

```sh
adb -s $S shell su -c 'for m in wave_test_ok wave_test_broken; do [ -d /data/adb/modules/$m ] && touch /data/adb/modules/$m/remove; done; rm -f /data/adb/wave_test_*'
adb -s $S uninstall com.wave.xp3.testapp
adb -s $S reverse --remove tcp:8765
adb -s $S shell rm -f /sdcard/Android/data/com.wave.xp3/files/plan.json
adb -s $S reboot
```

Leave the watchdog and `/data/adb/wave` in place: they belong to Wave. To
take them out too, run
`adb -s $S shell su -c 'rm -f /data/adb/post-fs-data.d/wave-watchdog.sh /data/adb/service.d/wave-watchdog.sh; rm -rf /data/adb/wave'`.
Uninstall the debug Wave if the phone didn't have Wave before.

## Recording

In `docs/tasks/README.md`, under 06 and 08, write one line per step: the
step number, pass or fail, and for a fail what was seen (the logged line,
the files). Also record:
- the commit tested, Magisk's version and the phone's firmware
  (`getprop ro.build.display.id`);
- the safe-mode key timing (08.5);
- how long the broken boot took from reboot to the second home screen
  (08.1).

Update the Root and Watchdog rows in `docs/status.md` to match.
