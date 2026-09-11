#!/usr/bin/env bash
# Run the console's interfaces headlessly. Nothing but Docker need be installed.
#
#   tools/ui-test.sh            everything
#   tools/ui-test.sh ui         the interface suite only (path, map, console)
#   tools/ui-test.sh engine     the Chromium 51 checks only
#   tools/ui-test.sh es         the language-ceiling audit only
#
# What each one is for is written at the top of the file it runs. The short
# version: `es` stops a `?.` reaching a machine that cannot run it, `engine`
# covers the two ways this console's seven-year-old WebView differs from the one
# you are developing on, and `ui` drives original.html through the frames Kotlin
# would push and asserts on what comes out.
#
# None of it touches the treadmill. It is the pass to run before
# tools/docker-deploy.sh, not instead of walking on the thing afterwards.
#
# Node and the two packages live in a container and a gitignored
# node_modules; the first run needs the network for `npm install`.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/.." && pwd)"
IMAGE="${STRIDE_NODE_IMAGE:-node:20-alpine}"

WHICH="${1:-all}"

run() { docker run --rm -v "$REPO:/repo" -w /repo/tools/uitest "$IMAGE" "$@"; }

if [ ! -d "$HERE/uitest/node_modules" ]; then
  echo ">>> installing jsdom and acorn into tools/uitest/node_modules (one time)"
  run npm install --silent --no-audit --no-fund
fi

fail=0
one() {
  echo
  echo "=== $1 ${2:-} ==========================================="
  run node "$1.js" ${2:-} || fail=1
}

case "$WHICH" in
  ui)     for m in path map console; do one ui "$m"; done ;;
  engine) one engine ;;
  es)     one es ;;
  all)
    one es
    one engine
    for m in path map console; do one ui "$m"; done
    ;;
  *) echo "usage: $0 [all|ui|engine|es]" >&2; exit 2 ;;
esac

echo
if [ "$fail" -eq 0 ]; then
  echo ">>> all checks passed"
else
  echo ">>> FAILURES above" >&2
fi
exit "$fail"
