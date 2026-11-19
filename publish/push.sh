#!/usr/bin/env bash
# Runs in the caller's workspace; SOURCE is a separate checkout kept clean.
set -euo pipefail
export GIT_TERMINAL_PROMPT=0

git_source() { git -C "$SOURCE" "$@"; }

refs=$(git_source ls-remote --symref origin HEAD)
branch=$(awk '$1 == "ref:" && $3 == "HEAD" { print $2 }' <<< "$refs")
if [[ "$branch" != refs/heads/* ]] || ! git check-ref-format "$branch"; then
  echo 'source: expected an existing default branch on the source repository' >&2
  exit 1
fi
if [[ -n $(git_source status --porcelain) ]]; then
  echo 'source: checkout has uncommitted changes' >&2
  exit 1
fi

options=()
if [[ -n "$FOLDER" ]]; then options+=("--folder=$FOLDER"); fi
if [[ -n "$AAPT2_PATH" ]]; then options+=(--aapt2 "$AAPT2_PATH"); fi

for attempt in 1 2 3 4 5; do
  git_source fetch --quiet --no-tags --depth=1 origin "$branch"
  base=$(git_source rev-parse FETCH_HEAD)
  git_source checkout --quiet --detach "$base"
  python3 "$WAVESRC" publish --source "$SOURCE" --section="$SECTION" \
    --meta "$META" --icon "$ICON" --binary "$BINARY" --url="$URL" \
    --changes-from "$CHANGES_FROM" --changes-path "$CHANGES_PATH" "${options[@]}"
  git_source add -- "$SECTION"
  if git_source diff --cached --quiet; then
    echo 'source: nothing to publish'
    exit 0
  fi
  git_source -c user.name='github-actions[bot]' \
    -c user.email='41898282+github-actions[bot]@users.noreply.github.com' \
    commit -m "Publish Wave package in $SECTION"
  if git_source push origin "HEAD:$branch"; then
    exit 0
  fi
  remote=$(git_source ls-remote --exit-code origin "$branch" | cut -f1)
  if [[ "$remote" == "$base" || "$attempt" == 5 ]]; then
    echo 'source: push failed' >&2
    exit 1
  fi
  echo 'source: default branch advanced; rebuilding the publication on the new tip' >&2
  sleep "$attempt"
done
