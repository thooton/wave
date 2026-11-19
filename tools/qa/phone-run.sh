#!/usr/bin/env bash
# The one way to touch the reserved XP3800: hold .oak/phone.lock for exactly as
# long as COMMAND runs, and never run COMMAND without it.
#
#   tools/qa/phone-run.sh --who "agent3 UI walk" [--wait SECONDS] -- COMMAND [ARG...]
#
# Takes the lock (mkdir is atomic), waiting up to --wait seconds (default 1200,
# or $PHONE_RUN_WAIT) and printing the holder while it waits. If the lock is not
# free in time it exits 75 without running anything. Otherwise it runs COMMAND,
# passes its exit status through, and releases the lock on normal exit, on
# failure, and on SIGINT/SIGTERM/SIGHUP (the signal is forwarded to COMMAND,
# which gets 10 seconds before SIGKILL). It only ever removes a lock it made.
# A holder that died without releasing is never stolen: ask the manager.
# COMMAND sees WAVE_PHONE_LOCK_TOKEN, which tools/qa/run.py accepts as proof
# that the lock is already held. Exit codes: 64 usage, 75 lock timeout.
set -u
set -o pipefail

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
LOCK=${PHONE_LOCK_DIR:-$ROOT/.oak/phone.lock}
WAIT=${PHONE_RUN_WAIT:-1200}
WHO=${PHONE_RUN_WHO:-}

usage() { sed -n '2,16p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//' >&2; exit 64; }

while [ $# -gt 0 ]; do
  case $1 in
    --who) [ $# -ge 2 ] || usage; WHO=$2; shift 2 ;;
    --wait) [ $# -ge 2 ] || usage; WAIT=$2; shift 2 ;;
    --) shift; break ;;
    -h|--help) usage ;;
    *) break ;;
  esac
done
[ $# -gt 0 ] || usage
case $WAIT in ''|*[!0-9]*) echo "phone-run: --wait must be whole seconds" >&2; exit 64 ;; esac
[ -n "$WHO" ] || { echo 'phone-run: say who you are, e.g. --who "agent3 UI walk"' >&2; exit 64; }
case $WHO in *$'\n'*) echo "phone-run: --who must be one line" >&2; exit 64 ;; esac

TOKEN="phone-run pid=$$ $(date +%s%N)"
HELD=0
CHILD=

release() {
  if [ "$HELD" = 1 ] && [ "$(cat "$LOCK/owner" 2>/dev/null)" = "$WHO [$TOKEN]" ]; then
    rm -f "$LOCK/owner" && rmdir "$LOCK" 2>/dev/null
  fi
  HELD=0
}

on_signal() {
  trap '' INT TERM HUP
  if [ -n "$CHILD" ] && kill -0 "$CHILD" 2>/dev/null; then
    kill -"$1" "$CHILD" 2>/dev/null
    for _ in $(seq 1 100); do kill -0 "$CHILD" 2>/dev/null || break; sleep 0.1; done
    kill -0 "$CHILD" 2>/dev/null && kill -KILL "$CHILD" 2>/dev/null
    wait "$CHILD" 2>/dev/null
  fi
  release
  case $1 in INT) exit 130 ;; TERM) exit 143 ;; *) exit 129 ;; esac
}
trap release EXIT
trap 'on_signal INT' INT
trap 'on_signal TERM' TERM
trap 'on_signal HUP' HUP

mkdir -p "$(dirname "$LOCK")"
deadline=$(( $(date +%s) + WAIT ))
last_note=0
until mkdir "$LOCK" 2>/dev/null; do
  now=$(date +%s)
  holder=$(cat "$LOCK/owner" 2>/dev/null || echo '(no owner file yet)')
  if [ "$now" -ge "$deadline" ]; then
    echo "phone-run: the phone is still reserved by: $holder" >&2
    echo "phone-run: gave up after ${WAIT}s; nothing was run" >&2
    exit 75
  fi
  if [ $((now - last_note)) -ge 30 ] || [ "$last_note" = 0 ]; then
    echo "phone-run: waiting for the phone, reserved by: $holder ($((deadline - now))s left)" >&2
    last_note=$now
  fi
  sleep 2
done
HELD=1
if ! printf '%s [%s]\n' "$WHO" "$TOKEN" > "$LOCK/owner"; then
  echo "phone-run: could not write the lock owner file" >&2
  exit 1
fi
echo "phone-run: holding the phone as: $WHO" >&2

WAVE_PHONE_LOCK_TOKEN="$WHO [$TOKEN]" "$@" <&0 &
CHILD=$!
wait "$CHILD"
status=$?
CHILD=
exit "$status"
