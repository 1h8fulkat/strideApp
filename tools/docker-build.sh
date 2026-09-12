#!/usr/bin/env bash
# Build the console APK in a container. Nothing but Docker need be installed.
#
#   tools/docker-build.sh            debug APK (default)
#   tools/docker-build.sh test       the JVM unit tests, no device needed
#   tools/docker-build.sh release    release APK; needs signing in local.properties
#   tools/docker-build.sh clean      throw the build outputs away
#   tools/docker-build.sh image      rebuild the container image and stop
#
# The image carries the JDK and the Android SDK; see tools/docker/Dockerfile.
# Two things are kept on the host between runs, because they must outlive any
# container:
#
#   ~/.gradle-stride   the Gradle dependency cache. Roughly 500 MB, downloaded
#                      once. Override with STRIDE_GRADLE_HOME.
#   ~/.android         the debug keystore — the standard Android location, so
#                      a build here and a build from Android Studio on the same
#                      machine sign identically and can replace each other on
#                      the console. Override with ANDROID_USER_HOME.
#
# See docs/DOCKER.md for the whole story, including why that keystore matters
# more than it looks like it should.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/.." && pwd)"
PROJECT="$REPO/console/stride"

IMAGE="${STRIDE_IMAGE:-stride-build:latest}"
GRADLE_HOME="${STRIDE_GRADLE_HOME:-$HOME/.gradle-stride}"
ANDROID_USER_HOME="${ANDROID_USER_HOME:-$HOME/.android}"

VARIANT="${1:-debug}"

mkdir -p "$GRADLE_HOME" "$ANDROID_USER_HOME"

build_image() {
  echo ">>> building $IMAGE (a few minutes the first time)"
  docker build -t "$IMAGE" "$HERE/docker"
}

if [ "$VARIANT" = "image" ]; then build_image; exit 0; fi
if ! docker image inspect "$IMAGE" >/dev/null 2>&1; then build_image; fi

# A JVM finds the debug keystore through `user.home`, and `user.home` comes
# from the passwd entry for the uid it is running as — which, running as your
# host uid inside someone else's base image, is either missing or belongs to a
# different user whose home this container cannot write. Android Gradle then
# quietly mints a *throwaway* debug key on every single build, so two builds of
# the same source get different signatures and neither can update the other on
# the console. ANDROID_USER_HOME below is the direct fix; this is the same fix
# one layer down, for anything else that asks the same question.
#
# The entry is substituted rather than appended: getpwuid returns the first
# match, and eclipse-temurin already ships a user at uid 1000.
PASSWD="$GRADLE_HOME/passwd"
if [ ! -s "$PASSWD" ]; then
  docker run --rm "$IMAGE" cat /etc/passwd \
    | awk -F: -v u="$(id -u)" '$3 != u' > "$PASSWD"
  echo "builder:x:$(id -u):$(id -g)::/gradle:/bin/bash" >> "$PASSWD"
fi

gradle() {
  docker run --rm \
    -u "$(id -u):$(id -g)" \
    -e HOME=/gradle \
    -e GRADLE_USER_HOME=/gradle \
    -e ANDROID_USER_HOME=/androidhome \
    -v "$GRADLE_HOME:/gradle" \
    -v "$ANDROID_USER_HOME:/androidhome" \
    -v "$PASSWD:/etc/passwd:ro" \
    -v "$REPO:/src" \
    -w /src/console/stride \
    "$IMAGE" ./gradlew --no-daemon --console=plain "$@"
}

case "$VARIANT" in
  clean)
    gradle clean
    echo ">>> cleaned"
    exit 0
    ;;
  release)
    gradle assembleRelease
    APK="$PROJECT/app/build/outputs/apk/release/app-release.apk"
    if [ ! -f "$APK" ]; then
      echo "FAILED: the release build produced no signed APK." >&2
      echo "        Set the stride.keystore.* lines in console/stride/local.properties;" >&2
      echo "        they are documented at the top of console/stride/app/build.gradle.kts." >&2
      exit 1
    fi
    ;;
  debug)
    gradle assembleDebug
    APK="$PROJECT/app/build/outputs/apk/debug/app-debug.apk"
    ;;
  test)
    # The JVM unit tests, which is Gpx against stride_gpx.py's own output on
    # three real routes. Seconds, no device, and the one check that can catch a
    # route driving the deck differently before it reaches the deck.
    gradle testDebugUnitTest
    echo ">>> unit tests passed"
    exit 0
    ;;
  *)
    echo "usage: $(basename "$0") [debug|release|test|clean|image]" >&2
    exit 2
    ;;
esac

echo ">>> built $APK ($(du -h "$APK" | cut -f1))"

# Printed every time, because it is the number that decides whether the next
# install replaces what is on the console or is refused by it. See docs/DOCKER.md.
docker run --rm -u "$(id -u):$(id -g)" -e HOME=/gradle \
  -v "$GRADLE_HOME:/gradle" -v "$REPO:/src" "$IMAGE" \
  apksigner verify --print-certs "/src/${APK#"$REPO/"}" 2>/dev/null \
  | grep -i "SHA-256 digest" | head -1 | sed 's/^/    signer /'
