#!/system/bin/sh
# Runs only if Magisk collected this module (it isn't disabled), so the
# marker says the module was mounted in this boot.
MODDIR=${0%/*}
echo "$(date +%s) $(grep '^version=' "$MODDIR/module.prop")" > /data/adb/wave_test_ok.mounted
