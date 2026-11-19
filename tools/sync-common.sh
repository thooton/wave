#!/bin/sh
# Refreshes common/ (common/core, common/ui) from wave_packages, the only
# place the shared code is edited, and records the commit it came from in
# common/UPSTREAM.
#
#   tools/sync-common.sh [--check] [path-to-wave_packages]
#
# The path defaults to ../wave_packages beside this repo, or ../sonim (its
# name before the rename) when that doesn't exist. The copy is of what is
# committed there (git archive of HEAD), so local edits in wave_packages
# never leak in. Bump Wave's version when the copy changes what it builds.
# --check compares the working copy with the exact recorded commit and
# separately reports whether P has committed more changes since then.
set -eu

here=$(cd "$(dirname "$0")/.." && pwd)
check=0
if [ "${1:-}" = --check ]; then
    check=1
    shift
fi
if [ "$#" -gt 1 ]; then
    echo "usage: $0 [--check] [path-to-wave_packages]" >&2
    exit 2
fi
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

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
head=$(git -C "$src" rev-parse HEAD)
commit=$head
if [ "$check" -eq 1 ]; then
    commit=$(sed -n '2p' "$here/common/UPSTREAM")
    if [ "${#commit}" -lt 40 ] || printf '%s\n' "$commit" | grep -q '[^0-9a-f]'; then
        echo 'sync-common: invalid or missing recorded commit in common/UPSTREAM' >&2
        exit 2
    fi
fi
git -C "$src" archive --format=tar "$commit" common/core common/ui > "$tmp/common.tar"
tar -xf "$tmp/common.tar" -C "$tmp"

if [ "$check" -eq 1 ]; then
    for module in core ui; do
        diff -rq -x build -x .gradle "$tmp/common/$module" "$here/common/$module"
    done
    echo "common/ matches recorded $commit"
    if ! git -C "$src" diff --quiet "$commit" "$head" -- common/core common/ui; then
        echo "sync-common: upstream common/ has newer changes at $head (not copied)" >&2
    fi
    exit 0
fi

if [ -n "$(git -C "$src" status --porcelain -- common)" ]; then
    echo "sync-common: note: $src/common has uncommitted changes; copying HEAD only" >&2
fi

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
