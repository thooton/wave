# Runs on the phone, from phone.sh.
# Runs as the shell user on the XP3, all inside /data/local/tmp/wave-sim.
T=/data/local/tmp/wave-sim
export WAVE_ADB=$T/adb STUBS=$T/stubs
cd $T; chmod 755 busybox stubs/*; rm -rf adb calls; mkdir -p adb/modules
INITPATH=/sbin:/system/sbin:/system/bin:/system/xbin:/vendor/bin:/vendor/xbin
ok() { if eval "$2"; then echo "PASS $1"; else echo "FAIL $1"; fi; }
su_() { # a Wave root script under the phone's own sh (mksh) and /system/bin
  sed "s/wave_module_id/wave_test_ok/g" ws/$1.sh > cmd.sh
  env -i TMPDIR=$T PATH=$STUBS:$INITPATH WAVE_ADB=$WAVE_ADB /system/bin/sh cmd.sh > out 2>&1; rc=$?; cat out | sed 's/^/    | /'; return $rc
}
mg() { # a watchdog half the way magiskd runs it: Magisk's busybox (armeabi-v7a), ASH_STANDALONE
  env -i ASH_STANDALONE=1 PATH=$STUBS:$INITPATH:/sbin WAVE_ADB=$WAVE_ADB STUBS=$STUBS "$@" ./busybox sh $T/$S > out 2>&1; rc=$?; cat out | sed 's/^/    | /'; return $rc
}
echo "sh: $(/system/bin/sh -c 'echo $KSH_VERSION')"
echo "busybox: $(./busybox | head -1)"
echo "== Wave's root scripts on mksh + toybox"
su_ install-watchdog; ok "install-watchdog exits 0" "[ $rc = 0 ]"
ok "post-fs-data.d script 755" "[ \"\$(stat -c %a adb/post-fs-data.d/wave-watchdog.sh)\" = 755 ]"
ok "service.d script 755" "[ \"\$(stat -c %a adb/service.d/wave-watchdog.sh)\" = 755 ]"
ok "wave dir 700" "[ \"\$(stat -c %a adb/wave)\" = 700 ]"
ok "installed scripts = the app's resources" "cmp -s adb/post-fs-data.d/wave-watchdog.sh pfs.sh"
su_ install-watchdog; ok "install-watchdog again (already there) exits 0" "[ $rc = 0 ]"
su_ install-module; ok "install-module exits 0" "[ $rc = 0 ]"
ok "pending lists it once" "[ \"\$(cat adb/wave/pending)\" = wave_test_ok ]"
su_ install-module; ok "again: still listed once" "[ \"\$(cat adb/wave/pending)\" = wave_test_ok ]"
su_ list-modules; ok "list-modules exits 0" "[ $rc = 0 ]"
su_ read-watchdog; ok "read-watchdog exits 0" "[ $rc = 0 ]"
echo "== the watchdog on Magisk's ARM busybox"
mv adb/modules_update/wave_test_ok/* adb/modules/wave_test_ok/ && rm -rf adb/modules_update adb/modules/wave_test_ok/update
S=adb/post-fs-data.d/wave-watchdog.sh
mg; ok "post-fs-data (pending): exit 0" "[ $rc = 0 ]"; ok "on trial" "[ -f adb/wave/trying ] && [ ! -f adb/wave/pending ]"
S=service.sh
mg env WAVE_SETTLE=4 WAVE_STEP=1 WAVE_BOOT_LIMIT=30; ok "service on this booted phone: exit 0" "[ $rc = 0 ]"
ok "settled with the real getprop/pidof" "tail -n1 adb/wave/log | grep -q 'boot settled after'"
ok "no reboot asked for" "[ ! -f calls ]"
echo wave_test_ok > adb/wave/pending; S=adb/post-fs-data.d/wave-watchdog.sh; mg; echo "    (on trial again)"
S=adb/post-fs-data.d/wave-watchdog.sh
mg; ok "post-fs-data after an unsettled boot: exit 0" "[ $rc = 0 ]"
ok "turned off: boot-incomplete" "grep -q 'wave_test_ok	boot-incomplete' adb/wave/disabled && [ -f adb/modules/wave_test_ok/disable ]"
su_ read-watchdog; ok "Wave reads it back" "grep -q '^@disabled' out && grep -q 'boot-incomplete' out"
su_ enable-module; ok "Turn On exits 0" "[ $rc = 0 ] && [ ! -f adb/modules/wave_test_ok/disable ] && grep -qx wave_test_ok adb/wave/pending"
mg; echo "    (on trial again)"
S=service.sh
mg env WAVE_SETTLE=4 WAVE_STEP=1 WAVE_BOOT_LIMIT=2 ./busybox sh -c 'getprop() { echo; }; . '$T/service.sh; ok "never-completed path: exit 0" "[ $rc = 0 ]"
ok "it asked for the reboot (stubbed)" "grep -q 'setprop sys.powerctl reboot' calls && grep -q '^reboot' calls"
ok "forced recorded" "[ -f adb/wave/forced ]"
su_ remove-module; ok "remove-module exits 0" "[ $rc = 0 ] && [ -f adb/modules/wave_test_ok/remove ]"
