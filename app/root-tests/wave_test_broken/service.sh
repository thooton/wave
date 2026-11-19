#!/system/bin/sh
# A deliberately broken mod, for the boot watchdog test (app/ROOT-TESTING.md).
#
# Fail-safe by construction:
#   - does nothing unless /data/adb/wave_test_broken.armed exists;
#   - deletes that file before doing anything, so it breaks one boot at most,
#     even if the watchdog, Magisk and everything else fail;
#   - holds the framework down for at most six minutes, then starts it
#     again, so even that one boot comes up on its own;
#   - writes nothing but its own files in /data/adb, and never /system.
#
# While armed it stops zygote every few seconds, so Android never finishes
# booting (sys.boot_completed stays unset), which is what the watchdog's
# late half waits five minutes for before rebooting once.
ARMED=/data/adb/wave_test_broken.armed
LOG=/data/adb/wave_test_broken.log
[ -f "$ARMED" ] || exit 0
rm -f "$ARMED"
sync
echo "$(date +%s) holding boot" >> "$LOG"
i=0
while [ $i -lt 72 ]; do
    if [ "$(getprop sys.boot_completed)" = 1 ]; then
        echo "$(date +%s) boot completed anyway" >> "$LOG"
        exit 0
    fi
    setprop ctl.stop zygote
    sleep 5
    i=$((i + 1))
done
echo "$(date +%s) giving the boot back" >> "$LOG"
setprop ctl.start zygote
