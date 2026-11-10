#!/usr/bin/env bash
# Phase 0 on the real phone: installs the probe APK, collects device facts, runs the keyboard
# spike with the system keyboard allowed and suppressed, then records the fold probe while you
# walk the phone through every posture. Results go to out/phase0/<serial>/.
#
# Usage: scripts/phone-phase0.sh [adb-serial]
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
ADB="${ADB:-$HOME/Android/Sdk/platform-tools/adb}"
serial="${1:-$("$ADB" devices | awk 'NR>1 && $2=="device" && $1 !~ /^emulator-/ {print $1; exit}')}"
if [ -z "$serial" ]; then
    echo "No phone found by adb (check 'adb devices': USB permissions or wireless pairing)." >&2
    exit 1
fi
a() { "$ADB" -s "$serial" "$@"; }

out="$root/out/phase0/$serial"
files=/sdcard/Android/data/io.fold/files
mkdir -p "$out"

wait_for() { # wait_for <remote file> <grep pattern> <tries>
    a shell "for i in \$(seq 1 $3); do grep -q '$2' $1 2>/dev/null && exit 0; sleep 2; done; exit 1"
}

echo "== Installing on $serial"
(cd "$root" && ./gradlew assembleDebug -q)
a install -r "$root/app/build/outputs/apk/debug/app-debug.apk" | tail -1
a shell rm -f "$files/device-report.txt" "$files/kbd-spike.log" "$files/fold-probe.log"

echo "== Device report"
a shell am start -S -n io.fold/.HomeActivity >/dev/null
wait_for "$files/device-report.txt" "Exec from app data dir" 30
a shell cat "$files/device-report.txt" | tee "$out/device-report.txt"

echo "== System facts"
{
    echo "## getprop (filtered)"
    a shell getprop | grep -iE "magic|honor|marketname|ro.build.version|ro.product.model|hinge|fold|posture|webview" || true
    echo; echo "## phantom process limits"
    echo "max_phantom_processes = $(a shell device_config get activity_manager max_phantom_processes)"
    echo "settings_enable_monitor_phantom_procs = $(a shell settings get global settings_enable_monitor_phantom_procs)"
    echo; echo "## wm"
    a shell wm size; a shell wm density
    echo; echo "## device_state"
    a shell dumpsys device_state | head -60
    echo; echo "## displays"
    a shell dumpsys display | grep -E "DisplayDeviceInfo\{" || true
    echo; echo "## webview"
    a shell dumpsys webviewupdate | grep -iE "current webview package|Valid package" || true
} > "$out/system.txt" 2>&1
echo "saved $out/system.txt"

echo "== Keyboard spike"
for mode in system altfocus; do
    a shell rm -f "$files/kbd-spike.log"
    a shell am start -S -n io.fold/.probe.KeyboardSpikeActivity --ez autotest true --es ime "$mode" >/dev/null
    wait_for "$files/kbd-spike.log" "SUMMARY" 120 || echo "(spike $mode did not finish)"
    a shell cat "$files/kbd-spike.log" > "$out/kbd-spike-$mode.log"
    grep SUMMARY "$out/kbd-spike-$mode.log" || true
done

echo
echo "== Fold probe"
a shell am start -S -n io.fold/.probe.FoldProbeActivity >/dev/null
echo "On the phone, go slowly through:"
echo "  1. unfolded flat   2. laptop (inner screen landscape, bent ~90°)"
echo "  3. book (inner screen portrait, bent ~90°)   4. folded (outer screen)   5. unfold again"
read -r -p "Press Enter when done… "
a shell cat "$files/fold-probe.log" > "$out/fold-probe.log"
grep -E "posture|layout" "$out/fold-probe.log" | tail -30
echo
echo "All results in $out"
