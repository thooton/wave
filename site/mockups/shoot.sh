#!/bin/bash
# Screenshots every frame of app.html at 240x320 into shots/<id>.png, and the
# whole gallery into shots/all.png. Needs node and chromium (or google-chrome).
#   site/mockups/shoot.sh            all frames
#   site/mockups/shoot.sh id1 id2    just those
set -e
here=$(cd "$(dirname "$0")" && pwd)
chrome=$(command -v chromium || command -v google-chrome || command -v chromium-browser)
mkdir -p "$here/shots"
ids=("$@")
if [ ${#ids[@]} -eq 0 ]; then
  mapfile -t ids < <(node -e "for (const f of require('$here/frames.js').FRAMES) console.log(f.id)")
fi
profile=$(mktemp -d)
shoot() {
  "$chrome" --headless=new --disable-gpu --hide-scrollbars --no-first-run --user-data-dir="$profile" \
    --force-device-scale-factor=1 --window-size="$1" --virtual-time-budget=2000 \
    --screenshot="$2" "$3" >/dev/null 2>&1
}
for id in "${ids[@]}"; do
  shoot 240,320 "$here/shots/$id.png" "file://$here/app.html?frame=$id"
  echo "shots/$id.png"
done
if [ $# -eq 0 ]; then
  shoot 1300,9000 "$here/shots/all.png" "file://$here/app.html"
  echo "shots/all.png"
fi
rm -rf "$profile"
