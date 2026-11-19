#!/bin/sh
# Refreshes common/ (common/core, common/ui) from wave_packages, the only
# place the shared code is edited, and records the commit it came from in
# common/UPSTREAM.
#
#   tools/sync-common.sh [path-to-wave_packages]
#
# The path defaults to ../wave_packages beside this repo, or ../sonim (its
# name before the rename) when that doesn't exist. The copy is of what is
# committed there (git archive of HEAD), so local edits in wave_packages
# never leak in. Bump Wave's version when the copy changes what it builds.
set -eu

here=$(cd "$(dirname "$0")/.." && pwd)
src=${1:-}
if [ -z "$src" ]; then
    for candidate in "$here/../wave_packages" "$here/../sonim"; do
        if [ -d "$candidate/common" ]; then src=$candidate; break; fi
    done
fi
if [ -z "$src" ] || [ ! -d "$src/common" ]; then
    echo "sync-common: no wave_packages checkout found (pass its path)" >&2
    exit 1
fi
src=$(cd "$src" && pwd)

commit=$(git -C "$src" rev-parse HEAD)
if [ -n "$(git -C "$src" status --porcelain -- common)" ]; then
    echo "sync-common: note: $src/common has uncommitted changes; copying HEAD only" >&2
fi

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
git -C "$src" archive --format=tar HEAD common/core common/ui | tar -x -C "$tmp"

for module in core ui; do
    rm -rf "$here/common/$module"
    cp -R "$tmp/common/$module" "$here/common/$module"
done

remote=$(git -C "$src" remote get-url origin 2>/dev/null || echo "$src")
cat > "$here/common/UPSTREAM" <<END
$remote
$commit
END
echo "common/ synced from $remote at $commit"
