# Handset smoke check

Use a reserved XP3800 only. Build a debug APK with
`./gradlew :app:app:assembleDebug`. If another Wave build or data is already
on the phone, don't overwrite or remove it without approval.

Keep the phone lock for the whole session, including any d-pad work. From
the repository root:

```sh
tools/qa/phone-run.sh --who "your name, Wave smoke check" -- bash
```

In that shell, set `SERIAL` to your reserved phone's serial, then run:

```sh
adb -s "$SERIAL" install -r app/app/build/outputs/apk/debug/app-debug.apk
adb -s "$SERIAL" shell am start -S -W -n com.wave.xp3/.app.WaveActivity --es fixture browse
```

Use only the d-pad and keypad:

1. Wave opens on Home. Move Right through Sources, Installed, and Search, then back.
2. On Sources, check that the title bar has no Refresh or Add links. Press Up on All Sources and confirm focus stays in the list. Check that Menu still offers Refresh Sources and Add a Source. Open a source, a section, and a package's details. Back returns to the list with the same row focused.
3. On Installed, check that there is no Update All link in the title bar. With the browse fixture, confirm Menu still offers Update All (2).
4. In Search, check that the empty field says "Search for packages". Switch to predictive T9, type a package name, and open a matching result.
5. Repeat the launch with `--es fixture noroot` and check that OK on the Home root row opens a screen with only "Wave requires root access" and a focused "Grant access" button.
6. With `browse`, open an install or update confirmation. Check that Confirm shows Statistics, Modifications and (for updates) Update, the plan fits or scrolls without clipping, the bar says Back, a reboot plan says Reboot required in grey just above the foot, Left/Right moves focus between Add to Queue and Confirm, and Back leaves. A remove-only plan starts on Add to Queue.
7. With `browse`, press OK on an Installed row's Update and Open, and on a row without either (Details opens). Press Add to Queue on a confirmation: Installed's badge says Q'd and its list starts with Queue. Get another package: its confirmation lists both. Confirm Queue and Clear Queue are in the menu.
8. On a package with versions listed, Menu offers See Version History, which lists every version with its date.

Record the build, serial, and any failure. The fixture doesn't install
packages or test real root. For targeted install or watchdog verification,
see [the rooted phone procedure](../../app/ROOT-TESTING.md).

Exit the shell to release the lock. The wrapper won't run the command if
the lock is still held at timeout (exit 75), and it never steals a lock.
Use `--wait SECONDS` to change the 20-minute default.

# Interactive GitHub Actions emulator

Use this from a Wave Oak worker when the session VM has no `/dev/kvm`.
GitHub Actions boots an isolated Android 8.1 (API 27), 240×320 mdpi,
keyboard/d-pad emulator and builds and installs one **debug** APK from the
exact commit you ask it to test. This is not a physical XP3800, a rooted mod
check, or a test of removable media or real handset services.

From the `wave` checkout (requires Python 3.9+ and Oak Git access):

```sh
emu=tools/qa/emulator
repo=../wave_packages                         # or '.' for Wave, or your private checkout
app=list                                     # app directory; use 'app' for Wave itself
sid=$($emu --repo "$repo" start "$app")
$emu --repo "$repo" "$sid" wait                # waits for boot AND install; initial build can take minutes
$emu --repo "$repo" "$sid" launch
$emu --repo "$repo" "$sid" key DPAD_DOWN DPAD_CENTER
$emu --repo "$repo" --out /tmp/xp3.png "$sid" shot
$emu --repo "$repo" --out /tmp/xp3.xml "$sid" ui
$emu --repo "$repo" "$sid" shell 'getprop ro.build.version.sdk; wm size; wm density'
$emu --repo "$repo" "$sid" close               # stops the runner and deletes both temporary refs
```

- `start` pushes **local committed `HEAD`**, not working tree changes. The
  checkout must already include `.github/workflows/emulator.yml`; update or
  rebase onto current `master` if necessary. Test an unmerged fix by
  committing it locally and starting a fresh emulator for that commit. This
  does not merge or publish a release. Use only trusted commits: the launch
  ref runs that commit's workflow and Gradle build.
- The device stays up between commands. Use `text 'test query'` (ASCII only),
  `log` / `log --clear`, `stop`, or `reset` (clear app data). `shell` runs
  inside the Android guest, not on the Actions host. `shot` and `ui` default
  to `shot.png` and `ui.xml` in the current directory. Use fixture data only.
  To start Wave with its `browse` UI fixture, run:

  ```sh
  $emu --repo . "$sid" shell 'am start -S -W -n com.wave.xp3/.app.WaveActivity --es fixture browse'
  ```
- Each session uses `emulator/<session>/start` (candidate commit) and `io`
  (commands and replies). Start separate sessions for separate agents.
  These are normal Git refs, not a private channel or credential: anyone
  with repo read access can read them, and anyone with write access can
  drive them. On public repos this means screenshots, hierarchy, logs,
  and every Git object you push can be publicly visible **even after cleanup**.
  Never use real accounts, tokens, setup files, private content or unreviewed
  data. Use the private checkout for private apps; do not move its APKs,
  captures, or output into public channels.
- If a command times out, use `status` then `result` (with `--out` if needed)
  to recover the reply instead of repeating input. If boot never becomes
  ready, find the job via the read-only API, replacing `REPO` with the repo
  name (`wave`, `wave_packages`, or `wave_private_packages`):

  ```sh
  oak gh api repos/thooton/REPO/actions/workflows/emulator.yml/runs \
    --jq ".workflow_runs[] | select(.head_branch == \"emulator/$sid/start\") | [.id,.status,.conclusion,.html_url] | @tsv"
  ```

  Use `clean` to delete both refs after a failed, abandoned, or timed-out
  session. A running bridge stops on a missing mailbox; GitHub caps the job
  at 60 minutes even if setup can't observe cleanup. Start a new session
  rather than rerunning an old Actions job. No Actions dispatch permission,
  Actions secret, public tunnel, or Oak deployment is needed.

Record the tested commit, Actions run ID, screen(s) or input verified, and
any limits. Workflow creation or an Actions job starting is not a device
check: wait for readiness, drive the UI, and inspect the results.
