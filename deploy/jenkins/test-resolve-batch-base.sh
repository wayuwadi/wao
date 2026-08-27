#!/bin/sh
set -eu

fixture="$(mktemp -d)"
trap 'rm -rf "$fixture"' EXIT HUP INT TERM
git -C "$fixture" init -q
git -C "$fixture" config user.name WAO
git -C "$fixture" config user.email wao@example.invalid

for name in first second third; do
  printf '%s\n' "$name" >"$fixture/value"
  git -C "$fixture" add value
  git -C "$fixture" commit -qm "$name"
done

first="$(git -C "$fixture" rev-list --reverse HEAD | head -n 1)"
second="$(git -C "$fixture" rev-list --reverse HEAD | sed -n '2p')"
third="$(git -C "$fixture" rev-parse HEAD)"

resolved="$(cd "$fixture" && WAO_DEPLOYED_VERSION="$(printf '%.7s' "$first")" \
  sh "$OLDPWD/deploy/jenkins/resolve-batch-base.sh" HEAD)"
test "$resolved" = "$first"

resolved="$(cd "$fixture" && WAO_DEPLOYED_VERSION=not-a-version GIT_PREVIOUS_COMMIT="$second" \
  sh "$OLDPWD/deploy/jenkins/resolve-batch-base.sh" HEAD)"
test "$resolved" = "$second"

resolved="$(cd "$fixture" && WAO_DEPLOYED_VERSION=not-a-version \
  GIT_PREVIOUS_SUCCESSFUL_COMMIT="$first" GIT_PREVIOUS_COMMIT="$second" \
  sh "$OLDPWD/deploy/jenkins/resolve-batch-base.sh" "$third")"
test "$resolved" = "$first"

echo 'Batch base resolution tests passed.'
