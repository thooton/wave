#!/bin/sh
# Runs Wave's root scripts and its boot watchdog on a real phone, rooted or not,
# as the shell user and only inside /data/local/tmp/wave-sim (removed after):
#   - Wave's root scripts (as the app builds them) under the phone's own
#     /system/bin/sh and /system/bin commands, against a pretend /data/adb;
#   - the watchdog's two halves under Magisk 24.0's armeabi-v7a busybox, as
#     magiskd runs them (ASH_STANDALONE=1), with the phone's real getprop and
#     pidof. setprop, /system/bin/reboot, chown and magisk are stubs that only
#     record the call, so nothing can reboot or change the phone.
#   app/root-tests/magisk/phone.sh <serial> [Magisk-v24.0.apk]
set -eu
S=$1
here=$(cd "$(dirname "$0")" && pwd)
repo=$(cd "$here/../../.." && pwd)
apk=${2:-$repo/../rphone/Magisk-v24.0.apk}
res=$repo/app/core/src/main/resources/com/wave/xp3/core/root
cp="$repo/app/core/build/classes/java/main:$repo/app/core/build/resources/main"
d=$(mktemp -d)
trap 'rm -rf "$d"' EXIT
mkdir -p "$d/sim/stubs"
"${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk}/bin/java" -cp "$cp" "$here/Gen.java" "$d/sim/ws"
unzip -p "$apk" lib/armeabi-v7a/libbusybox.so > "$d/sim/busybox"
cp "$res/watchdog-post-fs-data.sh" "$d/sim/pfs.sh"
sed 's|/system/bin/reboot|$STUBS/reboot|' "$res/watchdog-service.sh" > "$d/sim/service.sh"
for f in setprop reboot chown magisk; do cp "$here/phone/$f" "$d/sim/stubs/"; done
cp "$here/phone/run.sh" "$d/sim/run.sh"
adb -s "$S" shell rm -rf /data/local/tmp/wave-sim
adb -s "$S" push "$d/sim" /data/local/tmp/wave-sim >/dev/null
adb -s "$S" shell sh /data/local/tmp/wave-sim/run.sh | tee "$d/out"
adb -s "$S" shell rm -rf /data/local/tmp/wave-sim
! grep -q '^FAIL' "$d/out"
