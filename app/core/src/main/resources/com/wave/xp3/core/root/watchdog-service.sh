#!/system/bin/sh
# wave-watchdog: 5
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
# as good too, so a quick restart doesn't cost a healthy module. Android sets
# sys.shutdown.requested only a few seconds before the phone goes down, so
# in that minute it looks every second; before boot completes, every five.
# Each look starts at most one program (getprop): system_server is found once
# and then followed through /proc by the shell itself. On the XP3 a program
# costs about 30 ms of CPU to start.
#
# Magisk runs this in the background (late_start); it may wait.

ADB=${WAVE_ADB:-/data/adb}
W=$ADB/wave
PROC=${WAVE_PROC:-/proc}
LIMIT=${WAVE_BOOT_LIMIT:-300}
SETTLE=${WAVE_SETTLE:-60}
STEP=${WAVE_STEP:-1}
WAIT=${WAVE_WAIT:-5}

log() {
    echo "$(date +%s) service: $*" >> "$W/log"
}

# Sets `start` to when process $1 started (in ticks since boot), or to
# nothing if it has gone (or died, unreaped). The shell reads it itself, so no
# program starts; a new process, even with the same pid, starts later.
started() {
    start=
    line=
    [ -n "$1" ] && [ -r "$PROC/$1/stat" ] && read -r line 2>/dev/null < "$PROC/$1/stat"
    [ -n "$line" ] || return 0
    set -- $line
    [ $# -ge 22 ] && [ "$3" != Z ] && shift 21 && start=$1
    return 0
}

if [ ! -f "$W/trying" ]; then
    rm -f "$W/forced.last"
    exit 0
fi

waited=0
settled=0
completed=
pid=
known=
while :; do
    step=$WAIT
    [ -n "$completed" ] || [ "$(getprop sys.boot_completed)" != 1 ] || completed=1
    if [ -n "$completed" ]; then
        step=$STEP
        started "$pid"
        if [ -n "$start" ] && [ "$start" = "$known" ]; then
            settled=$((settled + STEP))
        else
            pid=$(pidof system_server)
            started "$pid"
            known=$start
            settled=0
        fi
        if [ "$settled" -ge "$SETTLE" ]; then
            rm -f "$W/trying" "$W/forced.last"
            log "boot settled after ${waited}s; modules kept"
            sync  # else a crash soon after could bring `trying` back (f2fs)
            exit 0
        fi
        if [ -n "$(getprop sys.shutdown.requested)" ]; then
            rm -f "$W/trying" "$W/forced.last"
            log "shutdown asked for after boot completed; modules kept"
            sync
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
    sleep "$step"
    waited=$((waited + step))
done
