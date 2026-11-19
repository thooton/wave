# 08: The boot watchdog

**Repo** W (`app/`). **Needs** 06.

## Read first
`../design.md` section 5. Magisk 24.0's source for the order of early boot
(`native/jni/core/bootstages.cpp`, `module.cpp`): when `post-fs-data.d`
scripts run relative to modules being collected and mounted, and how safe
mode disables modules. `../launcher_argon/AGENTS.md` (what a launcher crash
loop means) and the mods' `module/service.sh`.

## Goal
A module installed through Wave that stops the phone booting is turned off
by the next boot, without a computer.

## Deliver
- Two scripts shipped inside the app and installed through root into
  `/data/adb/post-fs-data.d/` and `/data/adb/service.d/` (reinstalled when
  the app's copy is newer), plus the state under `/data/adb/wave/`.
- The behaviour in the design: pending list written by the engine; cleared
  after a boot that completes and stays up; modules on a stale list disabled
  before Magisk mounts them; a reboot forced when boot hasn't completed in a
  few minutes; a record of what was disabled and why.
- In the app: after such a boot, say which mod was turned off, and offer to
  remove it or try again.
- A written test procedure, and a deliberately broken test module to run it
  with.

## Done when
On a reserved rooted test phone with a verified stock boot backup and EDL
recovery ready: the broken module is installed through Wave, the phone fails
to boot once, comes up on the next boot with the module disabled, and Wave
explains.

## Watch out
- The ordering the design relies on was read from Magisk v24.0's source
  (`post_fs_data` runs `exec_common_scripts("post-fs-data")` and then
  `handle_modules()`, whose `collect_modules` skips a module with a
  `disable` file). **Confirm it on the phone** before building on it: a
  `post-fs-data.d` script that disables a harmless test module, then check
  the module didn't mount. If the phone disagrees, update `../design.md`.
- Magisk's own safe mode (Volume Down held for three seconds during boot, or
  `persist.sys.safemode=1`) disables every module and leaves them disabled.
  Check that it triggers with the XP3's volume keys, document it for users,
  and make the app handle "everything is disabled" well.
- A launcher that crashes after boot completes is not caught by "boot
  completed". Decide whether to also watch for the home activity staying up,
  and keep false positives at zero: a watchdog that disables healthy mods is
  worse than none.
- The forced reboot must not loop: one retry, then leave the phone alone.
