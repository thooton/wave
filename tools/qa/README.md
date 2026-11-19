# Wave handset QA

Run from the repository root with Python 3.11+, `adb` on `PATH`, and Android
SDK build-tools (`aapt` on `PATH` or under `ANDROID_HOME`). With XP3800
`8516067e` awake and USB debugging authorized:

```sh
python3 tools/qa/run.py --dry-run
python3 tools/qa/run.py --apk /path/to/wave.apk --scenario tools/qa/scenarios/07-stock.json
python3 -m unittest discover -s tools/qa -v   # no device needed
```

`--dry-run` really installs the current release APK (`app/app/build/outputs/apk/release/app-release.apk`),
launches it, opens the foundation build's Updates menu, closes it, and removes
only that temporary install. No in-app installs are confirmed. This mode
expects the older Updates screen (verified with 0.1.0 and 0.3.0); use
`--scenario` for other milestones.
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
stock root gate, four tabs including the empty Search field, source > section
> package > plan > Back. The browse preview assumes an explicit fixture build
with `spec/example-source/` preseeded as the first actual source, keyboard
focus at the first row, and List absent. It is a candidate scenario, not a
verified on-device route. Typing a search phrase would require the number
keys or a preseeded test query; this harness deliberately exposes only the
listed d-pad/menu keys. No package installs, source edits, or reboots belong
in these UI scenarios.
