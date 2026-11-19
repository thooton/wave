#!/system/bin/sh
# wave-watchdog: 4
#
# Wave's boot watchdog, early half: /data/adb/post-fs-data.d/wave-watchdog.sh,
# installed by the Wave app (com.wave.xp3). See docs/design.md section 5.
#
# Magisk 24.0 runs the post-fs-data.d scripts before it collects modules
# (bootstages.cpp post_fs_data: exec_common_scripts, then handle_modules), and
# collect_modules leaves out any module with a `disable` file. So a module
# disabled here is not mounted in this boot.
#
# State, all in /data/adb/wave:
#   pending      modules Wave installed or updated since the last boot
#   trying       the modules the current boot is the first to try
#   disabled     what this script turned off: "<time>\t<id>\t<reason>"
#   forced       the late half rebooted the phone because boot never finished
#   forced.last  this boot follows such a forced reboot (no second one)
#   log          a short log
#
# Must be quick and must never fail the boot: Magisk waits for it.
#
# Every change is synced before the script ends. The XP3's /data is f2fs,
# which keeps a rename only once it checkpoints (about a minute) unless it is
# synced: a phone that crashed or lost power before then came back with
# `pending` instead of `trying`, so the module was tried again, not turned off.

ADB=${WAVE_ADB:-/data/adb}
W=$ADB/wave
[ -d "$W" ] || exit 0

now=$(date +%s)
changed=
log() {
    echo "$now post-fs-data: $*" >> "$W/log"
}

if [ -f "$W/forced" ]; then
    mv -f "$W/forced" "$W/forced.last"
fi

if [ -f "$W/trying" ]; then
    # The boot that first tried these modules never settled: it hung,
    # crashed back to the boot logo, or was restarted before it was up for a
    # minute. Leave them out of this one.
    if [ -f "$W/forced.last" ]; then
        reason=boot-timeout
    else
        reason=boot-incomplete
    fi
    if [ -r "$W/trying" ]; then
        while read -r id || [ -n "$id" ]; do
            case "$id" in
                '' | . | .. | */*) continue ;;
            esac
            hit=
            for d in "$ADB/modules/$id" "$ADB/modules_update/$id"; do
                if [ -d "$d" ]; then
                    touch "$d/disable" && hit=1
                fi
            done
            if [ -n "$hit" ]; then
                printf '%s\t%s\t%s\n' "$now" "$id" "$reason" >> "$W/disabled"
                log "disabled $id ($reason)"
            fi
        done < "$W/trying"
    else
        # A redirect from an unreadable file would end this script (POSIX
        # sh exits on a failed redirection of a compound command).
        log "trying unreadable; dropped"
    fi
    rm -f "$W/trying"
    changed=1
fi

if [ -f "$W/pending" ]; then
    mv -f "$W/pending" "$W/trying"
    log "trying $(tr '\n' ' ' < "$W/trying" 2>/dev/null)"
    changed=1
fi
[ -n "$changed" ] && sync

# Keep the log short.
if [ -f "$W/log" ] && [ "$(wc -l < "$W/log")" -gt 200 ]; then
    tail -n 100 "$W/log" > "$W/log.new" && mv -f "$W/log.new" "$W/log"
fi
exit 0
