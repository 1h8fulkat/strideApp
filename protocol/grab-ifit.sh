#!/usr/bin/env bash
# Pull every iFit APK off the console. Run as soon as ADB is available after a
# factory reset — before disabling or uninstalling anything.
#
# Safe to re-run; skips files already pulled.
set -uo pipefail

DEV="${DEV:-$(adb devices | awk 'NR>1 && $2=="device" {print $1; exit}')}"
HERE="$(cd "$(dirname "$0")" && pwd)"
OUT="$HERE/apks"
mkdir -p "$OUT"

adb connect "$DEV" >/dev/null 2>&1
if ! adb -s "$DEV" shell true >/dev/null 2>&1; then
    echo "FAILED: no adb connection to $DEV"
    exit 1
fi

echo ">>> connected: $(adb -s "$DEV" shell getprop ro.product.model 2>/dev/null | tr -d '\r')"
echo

# -f prints "package:<path>=<name>"; -u includes uninstalled-but-present ones.
# (macOS ships bash 3.2 — no mapfile, so use a temp file.)
ROWS_FILE="$(mktemp)"
trap 'rm -f "$ROWS_FILE"' EXIT
adb -s "$DEV" shell pm list packages -f -u 2>/dev/null \
    | tr -d '\r' \
    | grep -iE 'ifit|icon|cardio|fitpro|treadmill' > "$ROWS_FILE"

if [ ! -s "$ROWS_FILE" ]; then
    echo "No iFit packages found. Is the reset finished?"
    exit 1
fi

while IFS= read -r row; do
    [ -n "$row" ] || continue
    line="${row#package:}"
    path="${line%=*}"
    pkg="${line##*=}"
    dest="$OUT/${pkg}.apk"

    if [ -f "$dest" ]; then
        echo "  = $pkg (already have it)"
        continue
    fi

    printf '  → %-32s %s\n' "$pkg" "$path"
    if adb -s "$DEV" pull "$path" "$dest" >/dev/null 2>&1; then
        echo "    pulled $(du -h "$dest" | cut -f1)"
    else
        echo "    FAILED to pull"
    fi
done < "$ROWS_FILE"

echo
echo ">>> in $OUT:"
ls -la "$OUT"
echo
echo "Next: jadx -d src-cardio --no-res -q apks/<cardio pkg>.apk"
