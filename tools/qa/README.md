# Wave handset QA

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
`offline`, `uptodate`, `big`, `noroot`) and, with a fixture, `"query"` (letters and digits
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
  Again granting `su`, Reboot Now. The phone is stock, so those paths were not
  exercised.
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
