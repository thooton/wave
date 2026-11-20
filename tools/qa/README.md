# Wave handset QA

The default suite here is fixture-only and root-free. The **rooted suite** (`suite.py --rooted`, `rooted/`)
is separate, changes root state and reboots the phone: see "Rooted suite" at the end before running it.

## Rule 1: every adb command goes through `phone-run.sh`

The phone is shared. `tools/qa/phone-run.sh` is the only supported way to touch
it: it takes `.oak/phone.lock`, runs your command, and releases the lock when the
command ends, fails, or is interrupted (SIGINT, SIGTERM, SIGHUP).

```sh
tools/qa/phone-run.sh --who "agent3 UI walk, ~8 min" -- python3 tools/qa/suite.py
tools/qa/phone-run.sh --who "agent1 first install" --wait 1800 -- bash my-adb-script.sh
```

- It waits up to 20 minutes (`--wait SECONDS`, or `$PHONE_RUN_WAIT`) and prints
  the holder while it waits. If the lock is still taken it exits **75 without
  running your command**. A script must treat that as failure, never carry on.
- `--who` is required and is shown to everyone else: your agent name and what you
  are doing. Keep sessions short.
- Anything that runs `adb` (your own scripts, `adb install`, `input`, `screencap`,
  a one-off `adb shell`) goes inside it. Do not run adb on the phone outside it,
  and do not `mkdir` the lock by hand.
- It never steals or removes a lock it did not make. A holder that died without
  releasing is not stolen: ask the manager.
- `run.py` and `suite.py` recognise a session started this way and do not take the
  lock a second time; run on their own they still take it themselves.
- Exit codes: your command's own status, 75 lock timeout, 64 usage, 129/130/143
  for HUP/INT/TERM. `PHONE_LOCK_DIR` moves the lock (tests only).

Run from the repository root with Python 3.11+, `adb` on `PATH`, and Android
SDK build-tools (`aapt` on `PATH` or under `ANDROID_HOME`). With XP3800
`8516067e` awake and USB debugging authorized:

```sh
tools/qa/phone-run.sh --who "you, what" -- python3 tools/qa/run.py --dry-run
tools/qa/phone-run.sh --who "you, what" -- python3 tools/qa/run.py --apk /path/to/wave.apk \
  --scenario tools/qa/scenarios/07-stock.json
tools/qa/phone-run.sh --who "you, what" -- python3 tools/qa/run.py \
  --apk app/app/build/outputs/apk/debug/app-debug.apk --fixture browse --scenario tools/qa/scenarios/07-browse-preview.json
tools/qa/phone-run.sh --who "you, what" -- python3 tools/qa/suite.py \
  --apk app/app/build/outputs/apk/debug/app-debug.apk   # every fixture scenario
python3 -m unittest discover -s tools/qa -v   # no device needed
```

`--dry-run` really installs the current release APK (`app/app/build/outputs/apk/release/app-release.apk`),
launches it, and removes only that temporary install. It selects the older
Updates-menu smoke (verified with 0.1.0 and 0.3.0) or the newer Home-to-Sources
smoke using the APK's launcher activity. No in-app installs are confirmed.
Use `--scenario` for specific paths.
The harness reserves `.oak/phone.lock` for the device session, uses only this
serial, and refuses to replace an installed Wave. Wake/unlock the handset
before running; the harness neither changes Wi-Fi nor sends keys outside Wave.
Use only a trusted QA build and reviewed scenarios. An app bug or changed UI
could give ENTER different behavior, despite the screen guards.

Open `index.html` in the printed run directory. It includes the APK SHA-256,
240×320 captures, one action per state, check outcomes and health notes. Exit
0 means pass, 1 failed UI or health check, and 2 blocked or incomplete. Output
is under ignored `.oak/tmp/wave-qa/` unless `--out NEW_DIR` is supplied. Treat
reports as private until screenshots have been checked for personal data. The
logcat check uses a run marker without clearing logs; only attributed Wave
crash/ANR evidence is reported. The harness deletes its temporary accessibility
dumps. If killed with SIGKILL or disconnected, check the lock owner and ask
the manager before removing a stale lock or temporary install.

## Scenarios

JSON: `{ "name": "…", "steps": [ … ] }`. Start with
`{"name":"Opening","action":"launch","text_all":["Home"]}`. Every later
step is a separate named state with `key` equal to `DPAD_UP`, `DPAD_DOWN`,
`DPAD_LEFT`, `DPAD_RIGHT`, `ENTER`, `BACK` or `MENU`; `action: "capture"`
records a settled state without keys. Other fields:

| Field | Meaning |
| :--- | :--- |
| `wait_ms` | Time to let the next state settle, 350 ms by default. Increase for launches and network-dependent screens. |
| `before_text` | All listed strings must appear in Wave's current accessibility tree before the key. Required for ENTER. Back out of any install plan; never accept it. |
| `text_all`, `text_any` | Match all or any case-insensitive text/content-description substrings from Wave's accessibility tree. Use distinctive screen labels, not just the ever-present tab name. |
| `changed` | Whether the previous and current screenshots differ by `min_diff` or more; default 0.003 (0.3% of cropped pixels). |
| `focus_moved` | Detect a changed selected/focused accessible node; fall back to the pixel difference if focus isn't exposed. A visual-only pass is explicitly labeled as inferred. |
| `region` | `[x, y, width, height]` crop for current/previous and reference comparisons. Default `[0,25,240,270]` omits system bars; prefer a tighter region for painted selection indicators. |
| `reference`, `max_diff` | A reviewed 240×320 RGB/RGBA PNG relative to the scenario (within its directory); at most `max_diff` of cropped pixels may differ, default 0.005 (0.5%). Can stand in for inaccessible text. It is a visual comparison, not OCR. |

`foundation-list.json` exercises row focus for a connected foundation build
with a populated catalog. The `07-…` scenarios follow the mockup checklist:
stock root information, four tabs including the empty Search field, source > section
> package > plan > Back. The browse preview and tabs use the debug-only
`browse` fixture from `FakeWaveData`, with known source and package labels.
The harness inspects the exact APK snapshot before touching the phone and
refuses to run a fixture if the app is not debuggable or lacks `FakeWaveData`.
Typing a search phrase would require the number keys or a preseeded test
query; this harness deliberately exposes only the listed d-pad/menu keys. No package installs, source edits, or reboots belong
in these UI scenarios.

Scenario files may also carry `"fixture"` (one of `browse`, `reboot`, `errors`,
`offline`, `uptodate`, `big`, `noroot`, `newer`, `kept`) and, with a fixture, `"query"` (letters and digits
only, seeds the Search field). `--fixture` and `--fixture-query` override them.
`suite.py` runs every `07-*.json` that names a fixture, one report each, plus an
index. Add `--wait-lock SECONDS` to `run.py` to queue for the phone lock instead of
failing (the suite waits up to 10 minutes per scenario). Unknown key names are
rejected before any phone access, with the accepted names listed.

Accessibility caveat: Android reports only what is on screen. A row below the fold
is not in the hierarchy until focus scrolls it into view, so assert text after the
key that reveals it.

`repeat` (1 to 600, direction keys only) sends the key that many times in one
`input keyevent` call, then runs the step's checks once; use it to reach the end of
the `big` fixture's 500-package lists. `focus_on` checks the focused node's label,
`text_none` that none of the listed strings is on screen, and `fixture_confirm`
lets an ENTER step press Confirm, only in a fixture scenario (the queue is pretend).

## What a pass proves, and what it does not

A PASS shows that, on the reserved XP3800 with the APK named in the report, the
app installed, launched, stayed in the foreground, drew a 240×320 screen,
responded to each d-pad key, showed the text each step expects (as exposed to
accessibility), moved focus or changed the screen where a step says so, and
produced no Wave `FATAL EXCEPTION`, recorded crash or ANR during the run.

It does not prove:

- That a screen looks right. Text and pixel-change checks cannot see layout,
  colour, truncation or alignment. The reference is the side-by-side comparison
  of the `site/mockups/phone/` captures with `site/mockups/shots/`.
  Screenshots in a report are for a human to read.
- That an install, removal, update or reboot happened. Fixture scenarios run a
  pretend queue in a debug-only data provider and change nothing on the phone;
  release-build scenarios never confirm a plan. Real installs need their own
  verification (see `docs/first-phone.md`).
- Anything that needs root: mods, the watchdog turning a module off, Check
  Again granting `su`, Reboot Now. The fixture suite never exercises them; the
  separate rooted suite below does, on a rooted phone only.
- Network behaviour. Fixtures bypass sources, Git and downloads; offline
  scenarios check only the messages Wave shows.
- Coverage beyond the listed paths. A step passes if its checks pass; whatever
  is not asserted is not checked. A crash outside the run, or in another app,
  is not seen.
- That the build in the report is the one that ships. The report records the
  APK's version and SHA-256; compare it with the release you mean.

Timing: the accessibility tree can lag the drawn frame by a moment (a list's selection
after a key, a window handover). Foreground ownership is polled for up to 3 seconds,
and an accessibility-based check (focus, text, absent text) is retried twice, half a
second apart, against the same screenshot before the step fails. Pixel and reference
checks are never retried.

## Rooted suite (`suite.py --rooted`): changes the phone and reboots it

**This is not the default suite and it is not harmless.** It needs the reserved
XP3800 to be rooted (Magisk). It installs Wave with real data (no fixture), lets
Wave take root, installs and removes two test mods, and reboots the phone
several times, once into a boot that a mod holds on purpose until Wave's
watchdog gives up on it (about five minutes). Everything else in this folder
leaves the phone as it found it by construction; this suite restores it by
effort, and says so when it could not.

    tools/qa/phone-run.sh --who "agentN rooted QA" --wait 5400 -- python3 tools/qa/suite.py --rooted

(`suite.py --rooted` re-runs itself under `phone-run.sh` if you leave that out.)
It takes about 25 minutes. Reports go to `.oak/tmp/wave-qa/rooted-suite-<UTC>/index.html`,
one folder per scenario with screenshots and the `su` output each step checked.
`--apk` takes a rooted-QA APK; without it the suite builds one from `HEAD` (a clean
`git archive`, so uncommitted changes are not tested). Single scenario:
`python3 tools/qa/rooted/rooted.py --apk APK --scenario tools/qa/rooted/scenarios/R02-install-mod.json`.

What it does, in `rooted/`:

- `apk.py` builds the debug APK of a commit in a scratch export with two changes that never ship: the
  built-in source URL is `https://localhost:4483/mods.git`, and a network-security config trusts
  `source.py`'s throwaway certificate for `localhost`/`127.0.0.1`. The APK is checked against the certificate
  before it is installed.
- `source.py` builds a real Git source (`wave.json`, a System section, two `package.json` files) holding
  `app/root-tests/wave_test_ok` and `wave_test_broken`, unchanged, and serves it and their zips over HTTPS on
  `127.0.0.1:4483`. The phone reaches it through `adb reverse`, which the harness re-adds after every boot.
- `rooted.py` runs one scenario. Scenarios are the same JSON as the others plus `"rooted": true` and `do`
  steps (`su`, `wait_su`, `pregrant`, `answer_prompt`, `mark_log`, `health`, `reboot`, `wait_boot`). A
  rooted scenario may press Confirm and Reboot Now with `rooted_confirm`; `ENTER` still needs `before_text`.
  Keys are still only the d-pad, `ENTER`, `BACK` and `MENU`.

| Scenario | Proves |
| --- | --- |
| `R01-root-prompt` | Wave's Check Again raises Magisk's prompt; Down, Right, OK on the d-pad grants it; Wave says "Wave has root" and Magisk holds a grant for Wave. |
| `R02-install-mod` | A mod installs from the local HTTPS source; "Reboot to Finish" appears; its row opens "Reboot Now?"; Reboot Now restarts the phone; the module ran (`version=1.0` marker), the watchdog logs "boot settled" and keeps it. |
| `R03-watchdog-alert` | From the record the watchdog leaves (written by the harness, no reboot): the "Turned off" alert with OK and Remove It, Installed's "Turned off", Turn On (clears `disable`, puts it on trial), Turn Off's absence, and Remove It. |
| `R04-broken-boot` | A module that holds the boot, armed with a marker file: the first boot never completes, the watchdog reboots exactly once, turns the module off (`disabled`, the `disable` file, the log lines), the alert asks, Remove It removes it, and a final restart shows it gone. |

Before it starts, `suite.py --rooted` and `rooted.py` refuse (exit 2, nothing changed) unless `su -c id` gives
uid 0, Magisk's grant table holds only uid 2000 (the shell), no `wave_test_*` module or marker is on the phone,
Wave is not installed, and the battery is at least 30%. After every scenario, passed, failed or interrupted,
`rooted.py` waits for the phone (up to 12 minutes), then removes the test modules and markers, restores
`/data/adb/wave` and the watchdog scripts to the first listing, uninstalls Wave, deletes every Magisk grant that
was not there at the start (twice, because Magisk writes a late "deny" when a prompt is cancelled), removes
`adb reverse`, and verifies all of it. A restore it could not verify makes the scenario INCOMPLETE and stops
the suite. The suite ends with its own check of the same facts.

What a rooted run proves: the paths above worked on this phone, with this Magisk (24.0), through the real
install engine, the real watchdog scripts and the real boot, and that Wave's own screens say what happened. It
does not prove: that a different phone, Magisk or ROM behaves the same; that a real third-party mod works
(the test modules do nothing); that a mod which damages `/system` or the boot partitions is survivable (the
broken module only stops zygote, and disarms itself before it does, so it breaks one boot at most, and gives
the boot back after six minutes even if everything else fails); the first-run rooting itself (Magisk install,
the rooting guide); or anything the checks do not assert. R03's alert comes from a record the harness writes
in the watchdog's format, so R03 proves Wave's reading of the record and R04 proves the watchdog's writing of it.
Magisk's prompt window and its toasts are not in the accessibility tree, so R01 detects the prompt by window
focus and the Wave toast is checked only by the screenshot a person reads.

### If the phone does not come back

The suite waits 13 minutes for a boot, then 12 more in the restore, and says
"RESTORE NOT DONE" or "did not finish booting" if it gives up. The phone is not lost:

1. Wait a further five minutes; the broken module gives the boot back by itself after six.
2. Hold power for about 10 seconds to cut power, then boot again (a battery pull if that does nothing).
   A mod that was left behind can be disabled with `adb shell su -c 'touch /data/adb/modules/wave_test_broken/remove'`
   and a reboot, or `rm -rf /data/adb/modules/wave_test_* /data/adb/wave_test_*` once `adb` and `su` answer.
3. If it still does not boot, start Magisk's safe mode (hold Volume Down while it boots; it turns all modules
   off), or use the Rescue page (`site/rescue/`, needs the phone to reach adb).
4. As the last resort flash the boot image back. The backups are in `~/Documents/wave-phone-backups/8516067e/`:
   `boot-stock.img` (stock) and `boot-magisk.img` (Magisk-patched, what the phone ran), with `SHA256SUMS` and
   `SHA256SUMS-magisk`. Check the hashes, then fastboot or the vendor tool as in `docs/root.md` and
   `app/ROOT-TESTING.md` ("Ways out"). Flashing `boot-stock.img` unroots the phone.

After any manual recovery, run `suite.py --rooted` once more or check by hand: `su -c 'magisk --sqlite "SELECT uid FROM policies"'`
shows only `uid=2000`, `ls /data/adb/modules` shows no `wave_test_*`, and `pm path com.wave.xp3` prints nothing.

## Setup-file import (`setup/`): the six apps, on the phone, d-pad only

Checks the setup transport end to end: the site's real setup page makes a file, the file reaches a phone, a person
with only a d-pad imports it, and the app takes it. It installs side-by-side copies (`<package>.wavetest`) so the
owner's installed apps and their data are never touched; it only reads their path, version and update time before
and after, and fails if they differ. It needs the rooted phone only to look inside the copy's private storage
(`su`, no prompt; the shell is already granted). It does not reboot or change Magisk.

    python3 tools/qa/setup/build_copies.py APK_DIR          # sonim HEAD, .wavetest ids, ~8 min cold
    python3 tools/qa/setup/generate.py FILE_DIR             # real page in headless Chromium, no phone
    tools/qa/phone-run.sh --who "agentN setup import" --wait 3000 -- \
        python3 tools/qa/setup/import_setup.py --apks APK_DIR --files FILE_DIR --out REPORT_DIR [--picker] [app ...]

`build_copies.py` fails if a built APK is not `<package>.wavetest` (a hard-coded id breaks a copy). `generate.py`
needs `chromium` and `python3-websocket-client`; the values are obviously fake and every file is compared with the
payload the page should have produced; the page may request only its own static assets and blob downloads.
`apps.py` is the one table of ids, activities and fake values, checked against `site/setup/descriptors.mjs` by
`test_setup_import.py` (so a renamed app or a new required setting fails on the host).

Per app, `import_setup.py` asserts: a fresh copy opens on "Set up <App>" and "Not set up yet." with no login;
the d-pad reaches the import button (Down first in Authenticator and Flashcards, which start on "On this phone
only"); "Import this setup?" shows a summary with secrets as "set"; Right reaches Import; the app opens, the file
is deleted, `files/wave-setup.json` exists and is owner-only; after a force-stop and relaunch the app does not ask
again; the menu's Setup... (Settings in Canvas) shows the setup and no secret value; no crash. Key paths: default
`Enter, Right, Enter` (+ `Down` first for the two command-center apps); `--picker` puts the file in
`/sdcard/Download` and goes Down, Enter (Choose a file...), Down, Enter (Downloads), Enter on the newest file, Right,
Enter. The picker lists the owner's own files, so the script refuses to press OK unless the focus is on
`wave-setup-*`.

Result (Charge 1.1.3, Mail 1.5.2, Places 1.7.2, Canvas 1.8.3, Authenticator 1.2.2, Flashcards 1.14.3 on the XP3800,
Android 8.1): all six pass from the app's own folder, and all six pass through "Choose a file..." too. The picker is
the stock Android 8 Files app ("File Explorer"): its d-pad highlights Recent, Downloads and the device in a list, OK
opens one, and a file row takes OK; the setup screen then shows the same "Import this setup?" dialog. So the
picker route is reachable with the d-pad on every app (a stock phone's user can copy the file to Downloads and
import it without touching Android/data). What it does not prove: a real backend (the fake hosts
do not resolve, so Mail and Canvas show offline messages afterwards), setup through "Type it in..." or a command
center dialog, or any other phone; and a different picker (a phone whose Files app is not the stock one) is untested.

## Soak (`soak.py`): hours of use, watched for leaks

Not part of `suite.py` and never run by it. It installs a temporary debug Wave (no root, no fixture), walks it for
hours and records its memory, then removes it. It changes no setting on the phone.

**Battery.** It refuses to start under 50% (`--battery-start`), reads `dumpsys battery` before every cycle (level
and temperature go into `samples.csv`) and stops, cleaning up, under 30% (`--battery-stop`). The screen is woken
for each cycle's keys and turned off after it, and stays off while sampling. This is not optional caution: the
first full run kept the screen on with `stay_on_while_plugged_in` and the CPU busy, and after about 15 minutes the
phone fell off USB entirely and did not come back until someone was there to see to it. A drop off adb that comes
back within 3 minutes (a USB blip; Wave keeps running on the phone through it) is ridden out, listed in the report
and counted in `samples.csv`'s `adb_drops`; a longer one, or a sixth, stops the run and cleans up.

    tools/qa/phone-run.sh --who "agentN Wave soak (~3 h)" --wait 1800 -- \
        python3 tools/qa/soak.py [--commit REV | --apk APK] [--walks 200] [--churn 150] [--flips 300] [--every 20]

The APK is `rooted/apk.py`'s debug build (built for `--commit`, `HEAD` by default; uncommitted changes are not in it):
its built-in source is `https://localhost:4483/mods.git` through `adb reverse`, served by `soak.py` itself from a
Git repository it writes under `--work` (68 packages in three sections). Three phases, keys sent with `sendevent` on
the keypad's own input device:

- **walk**: Home's featured package and back; Sources, the source, a section, a package, back out; Installed;
  Search with a typed query, a result's details, back; Home.
- **churn**: the same walk while the source changes: each cycle asks the source's menu for Refresh, and the server
  answers 3 s late with a new commit (five versions, three icons, one package in and one out). Every tenth cycle
  instead starts a 1.5 MB download that the server sends at about 160 KB/s and cuts half way (Wave resumes it with
  a Range request); Back puts Wave in the background part way, it is brought back, and the download fails its
  SHA-256 by design, so nothing is ever installed.
- **flips**: Home and relaunch; every fifth cycle Back from Home instead, which finishes the Activity, so the next
  launch builds a new one in the same process.

Every `--every` cycles it forces two garbage collections (SIGUSR1 through `run-as`) and records `dumpsys meminfo`
(PSS, USS as private dirty plus clean, Java and native heap size and allocated, graphics, Views, ViewRootImpl,
Activities, AppContexts, binders), the thread count, RSS and open descriptors. The XP3 has no `procrank`, so meminfo's
TOTAL stands in for it. At the end of each phase it takes a heap dump (`am dumpheap`) and `hprof.py` writes
`heap-PHASE.txt`: what holds each `WaveActivity` (the shortest chain of strong references from a GC root, with the
thread when the root is a stack) and instance counts of Wave's classes. With exactly one `WaveActivity` in a dump,
no closed screen is being kept. Output: `.oak/tmp/wave-qa/soak-<UTC>/` with `samples.csv`, `report.md` (per phase:
first, after warm-up, last, max and the least-squares slope per 100 cycles over the phase's second half),
`summary.json`, the heap dumps and the server's request log. `python3 tools/qa/hprof.py DUMP --why CLASS` reads any
dump the same way. `test_soak.py` checks the meminfo parsing, the slope, the source's churn and the heap dump reader
on the host.

A walk cycle takes about 23 s, a churn cycle about 30 s and a flip about 6 s, so the defaults take about 3 h. A flat
line is the result to look for: a slope that stays positive over a phase's second half, or more than one
`WaveActivity` in a dump, is a leak to chase. What it does not cover: rooted actions, a real install, the screens of
Add a Source and the queue's Confirm beyond the one download, low-memory kills (the phone never ran short), and
anything that only grows over days.

Results so far (Wave 0.7.27 and 0.7.28 debug builds, XP3800, 2026-11-20). The first probes found a real leak:
after two Activity rebuilds, heap dumps held 2 `WaveActivity` (2,339 Wave objects, 241 Views, PSS 31.6 MB, native
8.2 MB), kept by the self-update check's thread and the icon worker's stack. Fixed in 0.7.28; the same probe then
ends with 1 (1,184 objects, 81 Views, PSS 27.9 MB, native 4.3 MB). The full run on 0.7.28 was stopped by request
after about 95 of 200 walk cycles (samples to cycle 80, no churn or flips phase, no adb drop, battery at 100%):
Views 153, Activities 1, threads 27, descriptors 64 and the Java heap (2.2 MB allocated) were flat from cycle 20 on.
PSS rose from 25.6 to 27.8 MB and native allocations from 5.2 to 6.3 MB over cycles 20 to 80. That native growth
is freed when the Activity is rebuilt (6.9 to 4.3 MB in the probe's flips), which fits the icon caches (Android 8
keeps bitmap pixels in native memory) filling toward their 6 MB cap; the run didn't last long enough to show the
plateau, so it is unproven. `.oak/tmp/wave-qa/soak-probe1`, `soak-probe2`, `soak-full2`. Still to do: the full
three phases.
