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
6. With `browse`, open an install or update confirmation. Check that the previous screen dims, the plan fits or scrolls without clipping, Left/Right moves focus between actions, and Back cancels. A remove-only plan starts on Cancel.

Record the build, serial, and any failure. The fixture doesn't install
packages or test real root. For targeted install or watchdog verification,
see [the rooted phone procedure](../../app/ROOT-TESTING.md).

Exit the shell to release the lock. The wrapper won't run the command if
the lock is still held at timeout (exit 75), and it never steals a lock.
Use `--wait SECONDS` to change the 20-minute default.
