#!/usr/bin/env bash
# Fails if a bootstrap zip contains the upstream Termux prefix (/data/data/com.termux) outside
# comments, docs and tests: such files would read or write paths our app can't access.
# Usage: scripts/check-prefix-leaks.sh out/bootstrap/bootstrap-aarch64.zip
set -euo pipefail

zip="$(realpath "$1")"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
(cd "$tmp" && unzip -q "$zip")

leaks=0
while IFS= read -r -d '' rel; do
    case "$rel" in
        ./share/doc/*|./share/man/*|./libexec/installed-tests/*|./include/*) continue ;;
    esac
    f="$tmp/${rel#./}"
    if head -c4 "$f" | grep -q 'ELF'; then
        hit="$(grep -a -o '/data/data/com\.termux[^[:cntrl:]]*' "$f" | head -n1 || true)"
    else
        hit="$(grep -I -v -E '^[[:space:]]*(#|//|\*)' "$f" 2>/dev/null | grep -o '/data/data/com\.termux[^[:space:]"]*' | head -n1 || true)"
    fi
    if [ -n "$hit" ]; then
        echo "LEAK ${rel#./}: $hit"
        leaks=$((leaks + 1))
    fi
done < <(cd "$tmp" && find . -type f -print0)

if [ "$leaks" -gt 0 ]; then
    echo "$leaks file(s) still use /data/data/com.termux" >&2
    exit 1
fi
echo "no com.termux paths in $(basename "$zip")"
