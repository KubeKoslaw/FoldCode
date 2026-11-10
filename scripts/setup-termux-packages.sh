#!/usr/bin/env bash
# Prepares third_party/termux-packages for FoldCode: a pinned upstream commit with our patches
# (packages/patches/*.patch) and our own package recipes (packages/recipes/<name>/) on top.
# Safe to re-run: it resets the patched files to upstream first. Built output (output/) is kept.
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
dir="$root/third_party/termux-packages"
# termux-packages master as of 2026-11-10.
TP_COMMIT="99a6d65782a530ff86c200be82a440814eb00c76"

if [ ! -d "$dir/.git" ]; then
    git clone --depth 1 https://github.com/termux/termux-packages "$dir"
fi
if [ "$(git -C "$dir" rev-parse HEAD)" != "$TP_COMMIT" ]; then
    git -C "$dir" fetch --depth 1 origin "$TP_COMMIT"
fi

# Back to pristine upstream for tracked files, then apply our patches.
git -C "$dir" checkout -q -f "$TP_COMMIT"
for p in "$root"/packages/patches/*.patch; do
    [ -e "$p" ] || continue
    git -C "$dir" apply --whitespace=nowarn "$p"
    echo "applied $(basename "$p")"
done

# Our own recipes (code-server, fold-* meta packages…) are copied over upstream ones.
if [ -d "$root/packages/recipes" ]; then
    for r in "$root"/packages/recipes/*/; do
        [ -d "$r" ] || continue
        name="$(basename "$r")"
        rm -rf "$dir/packages/$name"
        cp -r "$r" "$dir/packages/$name"
        echo "recipe $name"
    done
fi

grep -q '^TERMUX_APP__PACKAGE_NAME="io.fold"$' "$dir/scripts/properties.sh"
echo "termux-packages ready at $TP_COMMIT for io.fold"
