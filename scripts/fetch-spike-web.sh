#!/usr/bin/env bash
# Copies the web libraries used by the phase 0 keyboard spike (Monaco editor, xterm.js)
# from web/node_modules into the app's assets. Versions are pinned in web/package.json.
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
web="$root/web"
dest="$root/app/src/main/assets/spike/vendor"

if [ ! -d "$web/node_modules/monaco-editor" ] || [ ! -d "$web/node_modules/@xterm/xterm" ]; then
    (cd "$web" && npm install --ignore-scripts --no-audit --no-fund)
fi

rm -rf "$dest"
mkdir -p "$dest/monaco" "$dest/xterm"
cp -r "$web/node_modules/monaco-editor/min/vs" "$dest/monaco/vs"
cp "$web/node_modules/@xterm/xterm/lib/xterm.js" "$dest/xterm/"
cp "$web/node_modules/@xterm/xterm/css/xterm.css" "$dest/xterm/"

echo "Spike web vendor files copied to $dest ($(du -sh "$dest" | cut -f1))"
