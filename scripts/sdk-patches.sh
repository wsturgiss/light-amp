#!/usr/bin/env bash
#
# Manage Amp's patch overlay on the light-sdk submodule.
#
#   scripts/sdk-patches.sh apply          apply every patch to a pristine submodule
#   scripts/sdk-patches.sh revert         restore the submodule to pristine
#   scripts/sdk-patches.sh check          verify the submodule == pristine + patch set
#   scripts/sdk-patches.sh regen <name>   rewrite one patch from the submodule's
#                                         current state of the files it touches
#
# The Gradle build applies the set automatically when the submodule is clean
# (see settings.gradle.kts), so `apply` is rarely needed by hand. `revert` is
# the step before moving the submodule pin; `regen` is how spike work in the
# submodule gets folded back into its patch file.

set -euo pipefail
cd "$(dirname "$0")/.."

PATCH_DIR=light-sdk-patch
SDK=light-sdk

# Every path a patch touches, one per line (new files included, /dev/null not).
files_of() {
    grep -E '^(\+\+\+|---) [ab]/' "$1" | sed -E 's#^[+-]{3} [ab]/##' | sort -u
}

case "${1:-}" in
apply)
    for p in "$PATCH_DIR"/*.patch; do
        git -C "$SDK" apply --check "$(pwd)/$p"
    done
    for p in "$PATCH_DIR"/*.patch; do
        git -C "$SDK" apply "$(pwd)/$p"
        echo "applied ${p##*/}"
    done
    ;;
revert)
    for p in "$PATCH_DIR"/*.patch; do
        files_of "$p" | while read -r f; do
            if git -C "$SDK" ls-files --error-unmatch "$f" >/dev/null 2>&1; then
                git -C "$SDK" checkout -- "$f"
            else
                rm -f "$SDK/$f"
            fi
        done
    done
    # Directories a new-file patch created, now empty
    find "$SDK/sdk" -type d -empty -delete 2>/dev/null || true
    echo "light-sdk restored to pristine $(git -C "$SDK" rev-parse --short HEAD)"
    ;;
check)
    status=0
    for p in "$PATCH_DIR"/*.patch; do
        if git -C "$SDK" apply --reverse --check "$(pwd)/$p" 2>/dev/null; then
            echo "ok       ${p##*/}"
        else
            echo "DIVERGED ${p##*/}  (submodule content no longer matches this patch)"
            status=1
        fi
    done
    exit $status
    ;;
regen)
    name="${2:?usage: sdk-patches.sh regen <patch-file-name>}"
    p="$PATCH_DIR/${name%.patch}.patch"
    [ -f "$p" ] || { echo "no such patch: $p" >&2; exit 1; }
    files=$(files_of "$p")
    [ -n "$files" ] || { echo "$p lists no files — restore it from git before regenerating" >&2; exit 1; }
    # Intent-to-add so files the patch creates show up in git diff
    echo "$files" | while read -r f; do
        [ -f "$SDK/$f" ] && git -C "$SDK" add -N "$f" 2>/dev/null || true
    done
    # Diff against HEAD, not the index, so a stray staged state (e.g. from a
    # three-way apply during a rebase) can't produce an empty patch.
    # shellcheck disable=SC2086 — the paths are repo-relative and space-free
    git -C "$SDK" diff HEAD -- $files > "$p"
    echo "$files" | while read -r f; do
        git -C "$SDK" reset -q -- "$f" 2>/dev/null || true
    done
    echo "regenerated ${p##*/} ($(grep -c '^@@' "$p") hunks)"
    ;;
*)
    sed -n '2,15p' "$0"
    exit 1
    ;;
esac
