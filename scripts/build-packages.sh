#!/usr/bin/env bash
# Builds individual packages (and their dependencies) for io.fold in the termux-packages Docker
# builder and refreshes the signed apt repo in out/repo.
# Usage: scripts/build-packages.sh <package> [package…]     Log: out/build/packages.log (when redirected)
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
tp="$root/third_party/termux-packages"
[ $# -gt 0 ] || { echo "usage: $0 <package> [package…]" >&2; exit 2; }

"$root/scripts/setup-termux-packages.sh"
mkdir -p "$root/out/debs"

for pkg in "$@"; do
    echo "== $(date -Is) build $pkg"
    (cd "$tp" && ./scripts/run-docker.sh ./build-package.sh -a aarch64 "$pkg")
done

echo "== $(date -Is) repo"
cp -f "$tp"/output/*.deb "$root/out/debs/"
python3 -I "$root/scripts/make-apt-repo.py"
echo "== $(date -Is) done: $*"
