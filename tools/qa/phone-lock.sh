#!/bin/sh
# Take the shared phone lock or fail. Usage (stops a script on failure):
#   sh phone-lock.sh "<name> <what>" || exit 1
# Release: rm -rf /home/sam/Documents/wave/.oak/phone.lock
L=/home/sam/Documents/wave/.oak/phone.lock
if mkdir "$L" 2>/dev/null; then
    echo "$1" > "$L/owner"
    echo "GOT"
    exit 0
fi
echo "BUSY: $(cat "$L/owner" 2>/dev/null)" >&2
exit 1
