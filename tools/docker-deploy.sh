#!/usr/bin/env bash
# Install the console APK onto the treadmill and tail its log, over adb in a
# container. Nothing but Docker need be installed.
#
#   tools/docker-deploy.sh                  install, launch, follow the log
#   tools/docker-deploy.sh --backup-only    save the console's app data here
#   tools/docker-deploy.sh --replace        uninstall first, install, then put
#                                           the backed-up data back
#   tools/docker-deploy.sh --shell          an adb shell on the console
#
# The console's address comes from STRIDE_DEVICE and defaults to the one the
# install guide sets up:
#
#   STRIDE_DEVICE=192.168.1.50:5555 tools/docker-deploy.sh
#
# A plain install keeps everything. --replace is for the one case that needs
# it: an APK signed with a different key than the one already on the console.
# Android refuses that update outright and uninstalling is the only way
# through — which takes the settings, the people and the walk history with it.
# So this backs those up first and puts them back afterwards.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/.." && pwd)"

IMAGE="${STRIDE_IMAGE:-stride-build:latest}"
ANDROID_USER_HOME="${ANDROID_USER_HOME:-$HOME/.android}"
DEV="${STRIDE_DEVICE:-192.168.10.10:5555}"
PKG="dev.stride.hud"
APK="${STRIDE_APK:-$REPO/console/stride/app/build/outputs/apk/debug/app-debug.apk}"
BACKUPS="${STRIDE_BACKUPS:-$REPO/.backups}"

MODE="${1:-install}"

docker image inspect "$IMAGE" >/dev/null 2>&1 || {
  echo "No $IMAGE. Run tools/docker-build.sh first." >&2; exit 1; }
[ -f "$APK" ] || { echo "No APK at $APK. Run tools/docker-build.sh first." >&2; exit 1; }
mkdir -p "$BACKUPS" "$ANDROID_USER_HOME"

# --network host so the container can reach the treadmill on your LAN, and
# ~/.android mounted so adb uses the key the console has already authorised.
# Without that mount every run generates a fresh key and the console puts up
# its "Allow USB debugging?" prompt again — which nobody is standing in front
# of, because the point of adb over Wi-Fi is that you are not.
adb_() {
  docker run --rm -i --network host \
    -u "$(id -u):$(id -g)" \
    -e HOME=/androidhome \
    -v "$ANDROID_USER_HOME:/androidhome" \
    -v "$REPO:/src" -v "$BACKUPS:/backups" \
    "$IMAGE" adb "$@"
}
adbt_() {   # the same, for anything that streams until you stop it
  # -t only when there is actually a terminal. Asking for one without a tty is
  # a hard error from docker, so a scripted or CI run would fail at the last
  # line having already done every useful thing.
  local tty=; [ -t 0 ] && tty=-t
  docker run --rm -i $tty --network host \
    -u "$(id -u):$(id -g)" \
    -e HOME=/androidhome \
    -v "$ANDROID_USER_HOME:/androidhome" \
    "$IMAGE" adb "$@"
}

echo ">>> connecting to $DEV"
adb_ connect "$DEV" >/dev/null 2>&1 || true
if [ "$(adb_ -s "$DEV" get-state 2>&1 | tr -d '\r')" != "device" ]; then
  echo "FAILED: $DEV is not answering as an authorised device." >&2
  echo "        Is the treadmill powered on? Is adb over Wi-Fi still enabled" >&2
  echo "        (see docs/INSTALL.md 1.4)? If the console is showing an" >&2
  echo "        \"Allow USB debugging?\" prompt, accept it and run this again." >&2
  exit 1
fi

# `run-as` works because the build on the console is debuggable. On a release
# build it returns nothing and the backup is empty — which is worth finding out
# before an uninstall rather than after one.
backup() {
  local out="$BACKUPS/stride-data-$(date +%Y%m%d-%H%M%S).tar"
  echo ">>> backing up $PKG data"
  adb_ -s "$DEV" exec-out \
    "run-as $PKG sh -c 'cd /data/data/$PKG && tar cf - shared_prefs files 2>/dev/null'" \
    > "$out" || true
  if [ ! -s "$out" ]; then
    rm -f "$out"
    echo "    nothing came back — either the app is not installed, or the build"
    echo "    on the console is a release build and cannot be read into. There"
    echo "    is nothing here to preserve."
    return 1
  fi
  cp -f "$out" "$BACKUPS/stride-data-latest.tar"
  echo "    saved $(du -h "$out" | cut -f1) to $out"
}

# Best effort. The files land owned by the app because run-as *is* the app's
# uid, but SELinux labels are not restored — check the console afterwards
# rather than assuming.
restore() {
  local tar="$BACKUPS/stride-data-latest.tar"
  [ -s "$tar" ] || { echo ">>> nothing to restore"; return 0; }
  echo ">>> restoring app data"
  adb_ -s "$DEV" push /backups/stride-data-latest.tar /data/local/tmp/stride-restore.tar >/dev/null
  adb_ -s "$DEV" shell chmod 644 /data/local/tmp/stride-restore.tar
  adb_ -s "$DEV" shell \
    "run-as $PKG sh -c 'cd /data/data/$PKG && tar xf /data/local/tmp/stride-restore.tar'" || true
  adb_ -s "$DEV" shell rm -f /data/local/tmp/stride-restore.tar
  echo "    restored — open Settings on the console and confirm it took"
}

case "$MODE" in
  --shell)        adbt_ -s "$DEV" shell; exit 0 ;;
  --backup-only)  backup || exit 1; exit 0 ;;
  --replace)
    backup || true
    echo ">>> uninstalling $PKG"
    adb_ -s "$DEV" uninstall "$PKG" || true
    echo ">>> installing"
    adb_ -s "$DEV" install "/src/${APK#"$REPO/"}"
    restore
    ;;
  install)
    echo ">>> installing (keeping app data)"
    if ! adb_ -s "$DEV" install -r "/src/${APK#"$REPO/"}" 2>&1 | tee /tmp/stride-install.log; then true; fi
    if grep -qi "INSTALL_FAILED_UPDATE_INCOMPATIBLE\|signatures do not match" /tmp/stride-install.log; then
      cat >&2 <<'EOF'

This APK is signed with a different key than the one on the console, and
Android will not update across a signature change.

  Best   copy the debug.keystore from the machine that built what is
         installed now into ~/.android/, rebuild, and run this again.
         Everything on the console is kept.

  Else   tools/docker-deploy.sh --replace
         Uninstalls, then restores a backup of the settings, people and
         history it took first.
EOF
      exit 1
    fi
    ;;
  *)
    echo "usage: $(basename "$0") [install|--replace|--backup-only|--shell]" >&2
    exit 2
    ;;
esac

# This console ships with `log.tag=E`, so nothing below an error is recorded
# and `logcat -s Stride:I` comes back empty — which reads exactly like an app
# that failed to start. Its audio driver also spams the ring buffer hard
# enough to evict everything else within the minute. Both are set here rather
# than left as something to remember.
adb_ -s "$DEV" shell setprop persist.log.tag.Stride I >/dev/null 2>&1 || true
adb_ -s "$DEV" logcat -G 16M >/dev/null 2>&1 || true

echo ">>> launching"
adb_ -s "$DEV" shell am start -n "$PKG/.MainActivity" >/dev/null
echo ">>> logcat (ctrl-c to stop)"
adb_ -s "$DEV" logcat -c
adbt_ -s "$DEV" logcat -s Stride:I
