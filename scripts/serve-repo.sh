#!/usr/bin/env bash
# Serves out/repo to the phone for development: an HTTP server bound to localhost on the PC,
# and `adb reverse` so the phone's 127.0.0.1:8686 reaches it over the adb link (USB or Wi-Fi).
# Nothing is exposed on the LAN. Usage: scripts/serve-repo.sh [adb-serial]
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
ADB="${ADB:-$HOME/Android/Sdk/platform-tools/adb}"
serial="${1:-$("$ADB" devices | awk 'NR>1 && $2=="device" && $1 !~ /^emulator-/ {print $1; exit}')}"
port=8686

"$ADB" -s "$serial" reverse "tcp:$port" "tcp:$port"
echo "phone 127.0.0.1:$port → PC 127.0.0.1:$port ($serial)"
exec python3 -m http.server "$port" --bind 127.0.0.1 --directory "$root/out/repo"
