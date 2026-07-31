#!/usr/bin/env bash
# NordicUnchained — fire the moment ADB comes up, before eru re-locks.
# Order matters: eru dies first, everything else is cleanup.

set -u
# The console's address. Override with STRIDE_DEVICE, or leave it and the
# script asks adb what is attached — which is right far more often than a
# hardcoded IP, and is the difference between this working on one bench and
# working on anyone's.
DEV="${STRIDE_DEVICE:-$(adb devices | awk 'NR>1 && $2=="device" {print $1; exit}')}"
if [ -z "$DEV" ]; then
  echo "No device. Plug in over USB, or: export STRIDE_DEVICE=<ip>:5555" >&2
  exit 1
fi
HERE="$(cd "$(dirname "$0")" && pwd)"

say() { printf '\n>>> %s\n' "$*"; }
sh_() { adb -s "$DEV" shell "$@"; }

say "Connecting to $DEV"
for i in $(seq 1 30); do
    adb connect "$DEV" 2>&1 | grep -q connected && break
    sleep 1
done
adb -s "$DEV" wait-for-device || { echo "FAILED: no device"; exit 1; }

# ---- 1. Kill eru FIRST. This is the thing that re-locks us on boot. ----
say "Disabling com.ifit.eru (the re-locker)"
sh_ pm disable-user --user 0 com.ifit.eru
sh_ appops set com.ifit.eru SYSTEM_ALERT_WINDOW deny
sh_ appops set com.ifit.eru WRITE_SETTINGS deny

# ---- 2. Snapshot state while we still have a shell, for diagnosis. ----
say "Capturing device state"
{
    echo "=== date ==="; date
    echo "=== owners ==="; sh_ dpm list-owners
    echo "=== adb props ==="; sh_ getprop | grep -i adb
    echo "=== selinux ==="; sh_ getenforce
    echo "=== android ==="; sh_ getprop ro.build.version.release
    echo "=== ifit packages ==="; sh_ pm list packages | grep -i ifit
    echo "=== eru state ==="; sh_ dumpsys package com.ifit.eru | head -60
} > "$HERE/device-state.txt" 2>&1
echo "  -> saved to device-state.txt"

# ---- 3. Make ADB survive reboot so we stop losing access. ----
say "Persisting ADB"
sh_ settings put global adb_enabled 1
sh_ setprop persist.adb.tcp.port 5555
sh_ settings put global development_settings_enabled 1
# keep the screen alive while we work
sh_ settings put global stay_on_while_plugged_in 7

# ---- 4. Launcher replacement. ----
say "Disabling com.ifit.launcher"
sh_ pm disable-user --user 0 com.ifit.launcher

if [ -f "$HERE/NovaLauncher.apk" ]; then
    say "Installing Nova Launcher"
    adb -s "$DEV" install -r "$HERE/NovaLauncher.apk"
fi

for apk in SmartTube_stable_31.94_universal.apk fullscreenhide.apk; do
    if [ -f "$HERE/$apk" ]; then
        say "Installing $apk"
        adb -s "$DEV" install -r "$HERE/$apk"
    fi
done

say "Current launchers"
sh_ cmd package query-activities \
    -a android.intent.action.MAIN -c android.intent.category.HOME \
    2>/dev/null | grep -i packageName | sort -u

say "DONE — eru disabled. Pick Nova as home on the console, then reboot to verify it sticks."
