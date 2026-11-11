#!/usr/bin/env bash
# Builds the FoldCode packages and bootstrap (aarch64, io.fold prefix):
#  1. compiles Termux's default bootstrap set + EXTRA in the termux-packages Docker builder
#     (its own zip is discarded: it packs every built .deb, build-only dependencies included),
#  2. publishes all .debs into the signed apt repo out/repo,
#  3. generates the bootstrap from that repo, so it holds only the listed packages and their
#     runtime dependencies (and a correct second stage),
#  4. fails if anything still points at /data/data/com.termux.
# Output: out/bootstrap/bootstrap-aarch64.zip, out/debs/, out/repo/. Log: out/build/bootstrap.log
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
tp="$root/third_party/termux-packages"
EXTRA="${EXTRA:-git,openssh,ripgrep,nodejs-lts,npm}"

"$root/scripts/setup-termux-packages.sh"
mkdir -p "$root/out/build" "$root/out/bootstrap" "$root/out/debs"

echo "== $(date -Is) compile: default bootstrap set + $EXTRA"
(cd "$tp" && ./scripts/run-docker.sh ./scripts/build-bootstraps.sh --architectures aarch64 --add "$EXTRA")
rm -f "$tp/bootstrap-aarch64.zip"

echo "== $(date -Is) repo"
cp -f "$tp"/output/*.deb "$root/out/debs/"
python3 -I "$root/scripts/make-apt-repo.py"

echo "== $(date -Is) bootstrap from repo"
# Runs inside the builder (it needs jq); the repo is read through a file:// URL from a
# hard-linked copy inside the mounted termux-packages dir.
rm -rf "$tp/.foldcode-repo"
cp -al "$root/out/repo" "$tp/.foldcode-repo"
(cd "$tp" && ./scripts/run-docker.sh ./scripts/generate-bootstraps.sh --architectures aarch64 \
    --repository "file:///home/builder/termux-packages/.foldcode-repo" --add "$EXTRA")
rm -rf "$tp/.foldcode-repo"
mv -f "$tp/bootstrap-aarch64.zip" "$root/out/bootstrap/"

"$root/scripts/check-prefix-leaks.sh" "$root/out/bootstrap/bootstrap-aarch64.zip"
echo "== $(date -Is) done: $(du -h "$root/out/bootstrap/bootstrap-aarch64.zip" | cut -f1) bootstrap, $(ls "$root/out/debs" | wc -l) debs"
