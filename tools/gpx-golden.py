#!/usr/bin/env python3
"""Regenerate the golden digest GpxTest compares the Kotlin converter against.

    tools/gpx-golden.py

`console/stride/app/src/main/java/dev/stride/hud/Gpx.kt` is a port of the
conversion half of `homeassistant/stride_gpx.py`. The port exists so the
console can read Home Assistant itself instead of waiting for somebody to run
the script on a third machine — but the numbers it produces drive a deck, so
"it looks right" is not good enough. This writes down what the script says about
three real routes and `GpxTest` holds the Kotlin to it.

A flat line-oriented digest rather than JSON, deliberately. The test then needs
no JSON parser on the JVM classpath, and a disagreement names the exact value:

    rolling-loop-5k.gpx seg 7 900.0 1200.0 0.0

rather than "the documents differ". Run this whenever the script's conversion
changes on purpose, and read the diff before committing it — a change here is a
change to how every route walks.

The deck limits are pinned rather than read from stride.conf: the golden is a
statement about the converter, not about whichever treadmill happens to be
plugged in. They match what this board reports (-3.0 .. 15.0 %).
"""
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
FIXTURES = os.path.join(REPO, "console/stride/app/src/test/resources/gpx")
DIGEST = os.path.join(FIXTURES, "expected-digest.txt")

FLOOR, CEILING = -3.0, 15.0

sys.path.insert(0, os.path.join(REPO, "homeassistant"))
import stride_gpx as g  # noqa: E402


def n(v, places):
    """The same fixed-decimal rendering Gpx.num produces, including -0.0 -> 0.0."""
    out = f"%.{places}f" % v
    return out.replace("-0.0", "0.0", 1) if float(out) == 0 else out


def digest(filename, data):
    # Pinned, so the golden does not move when a config file does.
    g.deck_limits = lambda: (FLOOR, CEILING)
    route, notes = g.convert(filename, data)
    out = []
    def say(*parts):
        out.append(" ".join([filename] + [str(p) for p in parts]))

    say("id", route["id"])
    say("name", route["name"])
    say("distance_m", n(route["distance_m"], 1))
    say("climb_m", n(route["climb_m"], 1))
    say("segments", len(route["segments"]))
    for i, (a, b, gr) in enumerate(route["segments"]):
        say("seg", i, n(a, 1), n(b, 1), n(gr, 1))
    say("track", len(route["track"]))
    for i, (lat, lon, d) in enumerate(route["track"]):
        say("tk", i, n(lat, 6), n(lon, 6), n(d, 1))
    say("elev", len(route["elev"]))
    for i, (x, y) in enumerate(route["elev"]):
        say("el", i, n(x, 1), n(y, 1))
    say("bounds", *[n(v, 6) for v in route["bounds"]])
    return out, notes


def main():
    names = sorted(f for f in os.listdir(FIXTURES) if f.lower().endswith(".gpx"))
    if not names:
        sys.exit(f"no .gpx fixtures in {FIXTURES}")
    lines = []
    for f in names:
        with open(os.path.join(FIXTURES, f), "rb") as fh:
            rows, notes = digest(f, fh.read())
        lines += rows
        print(f"  {f:26} {len(rows):5} facts   "
              f"{notes['points']} points -> {notes['kept']} kept, "
              f"{notes['samples']} samples")
    with open(DIGEST, "w") as fh:
        fh.write("\n".join(lines) + "\n")
    print(f"\nwrote {DIGEST} ({len(lines)} lines)")
    print("Check the diff before committing: it is how every route walks.")


if __name__ == "__main__":
    main()
