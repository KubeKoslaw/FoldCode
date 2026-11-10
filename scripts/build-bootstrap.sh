#!/usr/bin/env bash
# Builds the FoldCode bootstrap (aarch64) from source in the termux-packages Docker builder:
# Termux's default bootstrap set plus the extra packages below, all for the io.fold prefix.
# Output: out/bootstrap/bootstrap-aarch64.zip and the .debs in out/debs/.
# Log: out/build/bootstrap.log
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
tp="$root/third_party/termux-packages"
EXTRA="${EXTRA:-git,openssh,ripgrep}"

"$root/scripts/setup-termux-packages.sh"
mkdir -p "$root/out/build" "$root/out/bootstrap" "$root/out/debs"

cd "$tp"
echo "== $(date -Is) build-bootstraps aarch64 + $EXTRA"
./scripts/run-docker.sh ./scripts/build-bootstraps.sh --architectures aarch64 --add "$EXTRA"

cp -f "$tp/bootstrap-aarch64.zip" "$root/out/bootstrap/"
cp -f "$tp"/output/*.deb "$root/out/debs/" 2>/dev/null || true
echo "== $(date -Is) done: $(du -h "$root/out/bootstrap/bootstrap-aarch64.zip" | cut -f1) bootstrap, $(ls "$root/out/debs" | wc -l) debs"
