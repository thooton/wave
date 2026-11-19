#!/system/bin/sh
# wave-watchdog: 1
#
# Wave's boot watchdog, late half: /data/adb/service.d/wave-watchdog.sh,
# installed by the Wave app (com.wave.xp3). See docs/design.md section 5 and
# the early half in post-fs-data.d, which owns the state it reads.
#
# Only does anything in a boot that is trying newly installed modules
# (/data/adb/wave/trying). Then:
#   - once boot has completed and system_server has stayed the same process
#     for a minute, the modules are good: `trying` is cleared;
#   - if that hasn't happened within five minutes, it reboots the phone once,
#     and the early half of the next boot turns those modules off;
#   - never a second forced reboot in a row (forced.last): then it leaves the
#     phone alone.
# A clean shutdown or restart the user asks for after boot completed counts
# as good too, so a quick restart doesn't cost a healthy module.
#
# Magisk runs this in the background (late_start); it may wait.

ADB=${WAVE_ADB:-/data/adb}
W=$ADB/wave
LIMIT=${WAVE_BOOT_LIMIT:-300}
SETTLE=${WAVE_SETTLE:-60}
STEP=${WAVE_STEP:-5}

log() {
    echo "$(date +%s) service: $*" >> "$W/log"
}

if [ ! -f "$W/trying" ]; then
    rm -f "$W/forced.last"
    exit 0
fi

waited=0
settled=0
completed=
pid=
while :; do
    if [ "$(getprop sys.boot_completed)" = 1 ]; then
        completed=1
        now_pid=$(pidof system_server)
        if [ -n "$now_pid" ] && [ "$now_pid" = "$pid" ]; then
            settled=$((settled + STEP))
        else
            pid=$now_pid
            settled=0
        fi
        if [ "$settled" -ge "$SETTLE" ]; then
            rm -f "$W/trying" "$W/forced.last"
            log "boot settled after ${waited}s; modules kept"
            exit 0
        fi
        if [ -n "$(getprop sys.shutdown.requested)" ]; then
            rm -f "$W/trying" "$W/forced.last"
            log "shutdown asked for after boot completed; modules kept"
            exit 0
        fi
    fi
    if [ "$waited" -ge "$LIMIT" ]; then
        if [ -f "$W/forced.last" ]; then
            log "not settled after ${waited}s, after a forced reboot already; leaving the phone alone"
            rm -f "$W/forced.last"
            exit 0
        fi
        if [ -n "$completed" ]; then
            log "boot completed but system_server kept restarting; rebooting once"
        else
            log "boot not completed after ${waited}s; rebooting once"
        fi
        echo "$(date +%s)" > "$W/forced"
        sync
        setprop sys.powerctl reboot
        sleep 20
        /system/bin/reboot
        exit 0
    fi
    sleep "$STEP"
    waited=$((waited + STEP))
done
