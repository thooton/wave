#!/sbin/.magisk/busybox/busybox sh
# One boot of a phone running Magisk 24.0, inside the sandbox sim.py makes.
# What magiskd does is copied from Magisk v24.0's source, step by step:
#   bootstages.cpp  post_fs_data(): safe mode (persist.sys.safemode or Volume
#                   Down) disables every module and runs no scripts; else
#                   exec_common_scripts("post-fs-data"), then handle_modules()
#   scripting.cpp   scripts run as `<busybox> sh <file>` with ASH_STANDALONE=1
#                   and PATH=<magiskd's PATH>:<MAGISKTMP>; post-fs-data.d
#                   only regular executable files, blocking for at most 35 s
#                   in all; service.d in the background (late_start)
#   module.cpp      prepare_modules() (modules_update replaces modules, a
#                   `disable` carries over), collect_modules() (`remove` runs
#                   uninstall.sh and deletes; `update` dropped; `disable`
#                   skipped), module post-fs-data.sh, then service.sh
# Android itself is pretend: zygote/system_server is a process named
# system_server, sys.boot_completed is a file, and what the boot does
# (hang, crash, restart, power cut) comes from /fake/scenario.
BB=/sbin/.magisk/busybox/busybox
MAGISKTMP=/sbin
# init's PATH on the XP3 (Android 8.1, as `adb shell echo $PATH` shows),
# which magiskd inherits.
INITPATH=/sbin:/system/sbin:/system/bin:/system/xbin:/vendor/bin:/vendor/xbin
run() { $BB env -i ASH_STANDALONE=1 "PATH=$INITPATH:$MAGISKTMP" "$@"; }
ev() { echo "$($BB date +%s) $*" >> /fake/events; }
. /fake/scenario
ADB=/data/adb
SAFE=

# --- post_fs_data -----------------------------------------------------------
if [ "$(cat /fake/props/persist.sys.safemode 2>/dev/null)" = 1 ] || [ -n "$VOLUME_DOWN" ]; then
    SAFE=1
    for m in $ADB/modules/*; do [ -d "$m" ] && [ "${m##*/}" != .core ] && touch "$m/disable"; done
    ev "safe mode: every module disabled, no scripts"
else
    start=$($BB date +%s)
    for f in $ADB/post-fs-data.d/*; do
        [ -f "$f" ] && [ ! -L "$f" ] && [ -x "$f" ] || continue
        left=$((35 - ($($BB date +%s) - start)))
        if [ $left -le 0 ]; then
            # Past the 35 s, Magisk still starts the rest but no longer waits.
            ( run $BB sh "$f" >> /fake/pfs.out 2>&1; ev "post-fs-data.d ${f##*/} exit $? (not waited for)" ) &
            continue
        fi
        rc=/fake/pfs.rc.${f##*/}; rm -f "$rc"
        ( run $BB sh "$f" >> /fake/pfs.out 2>&1; echo $? > "$rc" ) &
        while [ ! -f "$rc" ] && [ $(($($BB date +%s) - start)) -lt 35 ]; do $BB usleep 100000; done
        if [ -f "$rc" ]; then
            ev "post-fs-data.d ${f##*/} exit $(cat "$rc")"
        else
            ev "post-fs-data.d ${f##*/} still running at 35 s: blocking phase timeout, not waited for"
        fi
    done
    # prepare_modules
    if [ -d $ADB/modules_update ]; then
        for u in $ADB/modules_update/*; do
            [ -d "$u" ] || continue
            id=${u##*/}
            if [ -e "$ADB/modules/$id" ]; then
                [ -e "$ADB/modules/$id/disable" ] && touch "$u/disable"
                rm -rf "$ADB/modules/$id"
            fi
            mv "$u" "$ADB/modules/$id"
            ev "upgrade / new module: $id"
        done
        rm -rf $ADB/modules_update
    fi
    # collect_modules
    MOUNTED=
    for m in $ADB/modules/*; do
        [ -d "$m" ] || continue
        id=${m##*/}
        [ "$id" = .core ] && continue
        if [ -e "$m/remove" ]; then
            [ -f "$m/uninstall.sh" ] && run $BB sh "$m/uninstall.sh"
            rm -rf "$m"
            ev "removed $id"
            continue
        fi
        rm -f "$m/update"
        if [ -e "$m/disable" ]; then ev "left out (disabled) $id"; continue; fi
        MOUNTED="$MOUNTED $id"
    done
    ev "mounted:$MOUNTED"
    for id in $MOUNTED; do
        [ -e "$ADB/modules/$id/post-fs-data.sh" ] && run $BB sh "$ADB/modules/$id/post-fs-data.sh"
    done
fi

# --- late_start -------------------------------------------------------------
if [ -z "$SAFE" ]; then
    for f in $ADB/service.d/*; do
        [ -f "$f" ] && [ ! -L "$f" ] && [ -x "$f" ] || continue
        ( run $BB sh "$f" >> /fake/service.out 2>&1; ev "service.d ${f##*/} exit $?" ) &
    done
    for id in $MOUNTED; do
        [ -e "$ADB/modules/$id/service.sh" ] && ( run $BB sh "$ADB/modules/$id/service.sh" >/dev/null 2>&1 ) &
    done
fi

# --- pretend Android ---------------------------------------------------------
t=0; ss=; up=0
while :; do
    if [ -f /fake/props/sys.powerctl ]; then ev "reboot asked for: $(cat /fake/props/sys.powerctl)"; break; fi
    if [ -f /fake/rebooted ]; then ev "reboot by /system/bin/reboot"; break; fi
    if [ -f /fake/zygote.stopped ]; then
        [ -n "$ss" ] && kill $ss 2>/dev/null; ss=; up=0
    else
        if [ -z "$ss" ] || ! kill -0 $ss 2>/dev/null; then
            /usr/bin/bash -c 'exec -a system_server /usr/bin/sleep 100000' & ss=$!; up=0
        fi
        up=$((up + 1))
        [ -z "$HANG" ] && [ $up -ge ${BOOT_AT:-15} ] && [ ! -f /fake/props/sys.boot_completed ] && { echo 1 > /fake/props/sys.boot_completed; ev "boot completed"; }
        if [ -n "$CRASH_EVERY" ] && [ -f /fake/props/sys.boot_completed ] && [ $((t % CRASH_EVERY)) = 0 ]; then
            kill $ss; ss=
        fi
    fi
    if [ -n "$RESTART_AT" ] && [ $t -ge $RESTART_AT ]; then
        echo 1 > /fake/props/sys.shutdown.requested; ev "user restart"; $BB sleep 2; break
    fi
    if [ -n "$CUT_AT" ] && [ $t -ge $CUT_AT ]; then ev "power cut"; break; fi
    if [ $t -ge ${MAX:-400} ]; then ev "boot left running ${t}s, ended by the test"; break; fi
    $BB sleep 1; t=$((t + 1))
done
