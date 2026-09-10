#!/usr/bin/env python3
"""
Turn GPX files into routes the treadmill can walk.

    python3 stride_gpx.py preview walk.gpx     what it would become, publish nothing
    python3 stride_gpx.py list                 which files it can see, and how
    python3 stride_gpx.py sync                 convert them all and publish
    python3 stride_gpx.py watch                the same, whenever they change

Put a GPX on Home Assistant under `www/treadmill/routes/` — reachable at
`/local/treadmill/routes/<name>.gpx` — and it appears on the console. Delete
it and it goes away.

WHY THERE IS A LIST TO CONFIGURE AT ALL

Home Assistant will happily serve those files and will not tell you what they
are: `/local/` answers 403 to a directory request, by design, and there is no
API that enumerates it. Fetching is easy; *discovery* is the part with no
obvious answer. So this looks for a listing in two places, in order, and says
which one it used:

  1. A `folder` sensor in Home Assistant pointed at the directory. Its
     `file_list` attribute is a real listing that updates by itself, so
     dropping a file in is genuinely all you do. One-time cost is four lines
     of YAML and a restart; the entity does not need naming, because a folder
     sensor is found by having a `file_list` at all. Name it as
     `gpx_folder_sensor:` only to break a tie between several.

  2. `index.json` beside the GPX files — a plain list of names:
     `["friday-river-loop.gpx", "the-hill.gpx"]`. No Home Assistant changes,
     but you edit it whenever you add a walk.

  3. Failing both, `gpx_dir:` — an ordinary local folder, for running this on
     the machine that holds the files.

THE LIST IS THE LIBRARY

Whatever is listed becomes the console's routes, entire. Removing a file
removes the route, because the alternative — adding to whatever is already
retained — is a second copy of the truth that somebody eventually has to
reconcile by hand. An empty list publishes nothing: far likelier a wrong path
than an instruction to delete everything you own.

WHAT A ROUTE IS, AND WHAT IS THROWN AWAY

The console has no map and wants none. A route is a gradient profile against
*ground covered* — `[startM, endM, incline]` — so the hill arrives where it
did outdoors however slowly you take it indoors. Latitude, longitude and your
pace on the day are all discarded here. See Routes.kt.

THE TWO THINGS THAT MAKE THIS MORE THAN AN XML PARSER

**Elevation noise.** Consecutive trackpoints are metres apart with elevation
from a terrain model rounded to a decimetre, so a point-to-point gradient is
mostly quantisation. Converted naively, a real 5 km suburban loop came out as
98 segments: an incline change every 30 seconds, on a deck that takes about 20
seconds to make one. It would never once have settled. So gradient is measured
over a window of ground (`--window`), quantised to what the deck actually
steps in, and runs of equal grade are merged; anything still shorter than
`--min-segment` is absorbed into its neighbour.

**The deck stops at -3%.** No treadmill descends like a hill does. Anything
steeper is clamped — here, so it can tell you, and again by the console, which
would clamp it anyway. Every import prints the descent it had to flatten. The
ascents are untouched: nothing outdoors is steeper going down than this deck
can climb going up.

Neither is a fix. They are both the machine, stated out loud.

LEAVING THE WATCHER RUNNING

`watch` polls, because there is nothing to subscribe to over HTTP and a route
library changes about once a fortnight. To keep it up across reboots:

    # /etc/systemd/system/stride-gpx.service
    [Unit]
    Description=STRIDE GPX route watcher
    After=network-online.target
    [Service]
    ExecStart=/usr/bin/python3 /path/to/homeassistant/stride_gpx.py watch
    Restart=always
    User=youruser
    [Install]
    WantedBy=multi-user.target

    systemctl enable --now stride-gpx

`sync` is the same thing once, for a cron job or a Home Assistant
`shell_command:` if you would rather leave nothing running.
"""
import hashlib
import json
import math
import os
import re
import sys
import time
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET

from stride_config import HA, conf, mqtt, token

TOPIC = "stride/routes"

# Gradient is measured over this much ground, quantised to this, and held for
# at least this far. Defaults chosen against a real 5 km walk: ~30 segments,
# one change every 97 s at 6.2 km/h, and 34 m of the original 42 m of climb.
WINDOW = 150.0
MIN_SEGMENT = 120.0
STEP = 0.5

POLL_SEC = 30.0
HTTP_TIMEOUT = 20


def deck_limits():
    """What the belt can do. Overridable, because not every board is this one.

    The console clamps to whatever its own board reports regardless — this is
    here so the numbers printed at import match the walk you will get.
    """
    return (float(conf("deck_min_grade", "-3") or -3),
            float(conf("deck_max_grade", "15") or 15))


def local_base():
    """The `/local/...` URL the GPX files sit under, no trailing slash."""
    path = (conf("ha_gpx_path", "treadmill/routes") or "treadmill/routes").strip("/")
    return f"{HA}/local/{path}"


# --- the geometry ----------------------------------------------------------

def trackpoints(data):
    """Every trkpt in a GPX document, in file order.

    Namespace-agnostic: GPX in the wild carries a different xmlns per exporter,
    and matching on the tag suffix is the only thing that reads all of them.
    """
    root = ET.fromstring(data)
    pts = []
    for p in root.iter():
        if not p.tag.endswith("trkpt"):
            continue
        ele = next((c.text for c in p if c.tag.endswith("ele")), None)
        if ele is None:
            continue
        pts.append((float(p.get("lat")), float(p.get("lon")), float(ele)))
    return pts


def metres(a, b):
    """Haversine on the mean Earth radius. Good to a few cm at these lengths,
    which is far past what the elevation data deserves."""
    r = 6371008.8
    p1, p2 = math.radians(a[0]), math.radians(b[0])
    dp, dl = p2 - p1, math.radians(b[1] - a[1])
    h = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * r * math.asin(math.sqrt(h))


def profile(pts):
    """(distance, elevation) along the track.

    Points closer than half a metre are dropped: editors repeat a vertex when
    a route doubles back on itself, and a zero-length step is a division
    waiting to happen for ground nobody walks.
    """
    out, d = [(0.0, pts[0][2])], 0.0
    for a, b in zip(pts, pts[1:]):
        step = metres(a, b)
        if step < 0.5:
            continue
        d += step
        out.append((d, b[2]))
    return out


def elevation_at(prof, x):
    if x <= prof[0][0]:
        return prof[0][1]
    if x >= prof[-1][0]:
        return prof[-1][1]
    for (d0, e0), (d1, e1) in zip(prof, prof[1:]):
        if x <= d1:
            span = d1 - d0
            return e0 if span <= 0 else e0 + (e1 - e0) * ((x - d0) / span)
    return prof[-1][1]


def segments(prof, window, min_segment, step, floor, ceiling):
    """The profile as stretches of held gradient. Returns (segments, flattened).

    `flattened` is metres of drop the deck cannot give you, measured before the
    clamp, so the caller can say so rather than quietly delivering a gentler
    walk than the one that was recorded.
    """
    total = prof[-1][0]
    raw, lost, x = [], 0.0, 0.0
    while x < total - 1e-9:
        end = min(x + window, total)
        span = end - x
        grade = (elevation_at(prof, end) - elevation_at(prof, x)) / span * 100
        held = round(grade / step) * step
        if held < floor:
            lost += (floor - held) / 100 * span
            held = floor
        held = min(held, ceiling)
        raw.append([x, end, held])
        x = end

    merged = [raw[0]]
    for s in raw[1:]:
        if abs(s[2] - merged[-1][2]) < 1e-9:
            merged[-1][1] = s[1]
        else:
            merged.append(s)

    kept = []
    for s in merged:
        if kept and (s[1] - s[0]) < min_segment:
            kept[-1][1] = s[1]          # too short to be worth moving for
        else:
            kept.append(s)
    return kept, lost


def route_id(filename):
    """Stable across re-imports, so replacing a file replaces its route rather
    than adding a second copy of it."""
    stem = os.path.splitext(os.path.basename(filename))[0].lower()
    return "gpx-" + (re.sub(r"[^a-z0-9]+", "-", stem).strip("-") or "route")


def route_name(filename):
    """The filename, tidied.

    Not the GPX's own <name>: exporters put "New file 1" in there, and a
    filename is something you chose. Underscores and hyphens become spaces.
    """
    stem = os.path.splitext(os.path.basename(filename))[0]
    stem = re.sub(r"[_-]+", " ", stem).strip()
    return (stem[:1].upper() + stem[1:]) if stem else "Route"


def convert(filename, data, window=WINDOW, min_segment=MIN_SEGMENT, step=STEP):
    pts = trackpoints(data)
    if len(pts) < 2:
        raise ValueError("no usable trackpoints (needs lat, lon and ele)")
    prof = profile(pts)
    if prof[-1][0] < 50:
        raise ValueError(f"only {prof[-1][0]:.0f} m long")
    floor, ceiling = deck_limits()
    segs, flattened = segments(prof, window, min_segment, step, floor, ceiling)
    climb = sum((b - a) * g / 100 for a, b, g in segs if g > 0)
    route = {
        "id": route_id(filename),
        "name": route_name(filename),
        "distance_m": round(prof[-1][0], 1),
        "climb_m": round(climb, 1),
        "difficulty": 1.0,
        "segments": [[round(a, 1), round(b, 1), round(g, 1)] for a, b, g in segs],
    }
    raw_climb = sum(max(0.0, b[1] - a[1]) for a, b in zip(prof, prof[1:]))
    return route, {"flattened": flattened, "raw_climb": raw_climb, "points": len(pts)}


# --- finding the files -----------------------------------------------------

def http(url, bearer=False):
    headers = {"Authorization": f"Bearer {token()}"} if bearer else {}
    req = urllib.request.Request(url, headers=headers)
    return urllib.request.urlopen(req, timeout=HTTP_TIMEOUT).read()


def gpx_only(file_list):
    """Basenames of the .gpx in a folder sensor's listing.

    Only the basename matters: the paths in there are on the Home Assistant
    box, and the file is fetched over `/local/` rather than read off that
    disk.
    """
    return sorted(os.path.basename(f) for f in file_list
                  if f.lower().endswith(".gpx"))


def from_folder_sensor(entity):
    """Filenames from a Home Assistant `folder` sensor.

    The one listing that maintains itself. `file_list` is full paths on the
    Home Assistant box; only the basename matters here, because the file is
    fetched over `/local/` rather than read off that disk.
    """
    body = json.loads(http(f"{HA}/api/states/{entity}", bearer=True))
    files = body.get("attributes", {}).get("file_list")
    if files is None:
        raise SystemExit(f"{entity} has no file_list attribute - is it a folder sensor?")
    return gpx_only(files)


def from_index(base):
    """Filenames from an index.json beside the GPX files.

    Accepts a bare list of names, or a list of objects with a "file" key, so a
    generated index can carry more later without breaking this one.
    """
    body = json.loads(http(f"{base}/index.json"))
    names = []
    for entry in body:
        name = entry if isinstance(entry, str) else entry.get("file", "")
        if name.lower().endswith(".gpx"):
            names.append(os.path.basename(name))
    return sorted(names)


def find_folder_sensor():
    """The folder sensor, without being told its name.

    `folder` builds an entity id out of the path, so it is guessable — and
    guessing wrong is a setup step that fails for a reason nobody can see.
    A folder sensor is instead recognised by what makes it one: a `file_list`
    attribute. Where there is more than one, the tie is broken by the path
    the config already names.

    Returns (entity_id, filenames) — the listing comes back with it, because
    the scan has already read the very attribute a second call would go and
    fetch. None means "keep looking" rather than "this is broken": there is
    an index.json road behind this one.
    """
    if not conf("ha_api_token", ""):
        return None
    try:
        states = json.loads(http(f"{HA}/api/states", bearer=True))
    except Exception:
        return None
    found = [s for s in states
             if isinstance(s.get("attributes", {}).get("file_list"), list)]
    if not found:
        return None
    if len(found) > 1:
        want = (conf("ha_gpx_path", "treadmill/routes") or "").strip("/")
        exact = [s for s in found
                 if str(s["attributes"].get("path", "")).rstrip("/").endswith(want)]
        if len(exact) != 1:
            names = ", ".join(s["entity_id"] for s in found)
            raise SystemExit(
                f"More than one folder sensor here and none matching {want!r}:\n"
                f"  {names}\n"
                f"  Name the one you mean as gpx_folder_sensor: in stride.conf.")
        found = exact
    picked = found[0]
    return picked["entity_id"], gpx_only(picked["attributes"]["file_list"])


def discover():
    """Where the files are and what they are called: (source, [(name, loader)]).

    Ordered by how little you have to maintain by hand.
    """
    base = local_base()
    named = conf("gpx_folder_sensor", "")
    sensor, names = (named, from_folder_sensor(named)) if named \
        else (find_folder_sensor() or (None, None))
    if sensor:
        return (f"{sensor} -> {base}",
                [(n, (lambda n=n: http(f"{base}/{n}"))) for n in names])

    try:
        names = from_index(base)
        return (f"{base}/index.json",
                [(n, (lambda n=n: http(f"{base}/{n}"))) for n in names])
    except urllib.error.HTTPError as e:
        if e.code != 404:
            raise
    except urllib.error.URLError as e:
        raise SystemExit(f"cannot reach {base} ({e.reason}). Is ha_url right in stride.conf?")

    folder = conf("gpx_dir", "")
    if folder and os.path.isdir(folder):
        names = sorted(f for f in os.listdir(folder) if f.lower().endswith(".gpx"))
        return (folder,
                [(n, (lambda p=os.path.join(folder, n): open(p, "rb").read()))
                 for n in names])

    raise SystemExit(
        f"Nothing to read.\n\n"
        f"  No gpx_folder_sensor: in stride.conf, no index.json at {base},\n"
        f"  and gpx_dir: is unset or missing.\n\n"
        f"  Quickest start: put this next to your .gpx files as index.json —\n"
        f'      ["your-walk.gpx"]\n\n'
        f"  Or add a folder sensor and never edit a list again. Both are\n"
        f"  described in stride.conf.example.")


# --- doing it --------------------------------------------------------------

def describe(route, notes, pace_kph=6.2):
    segs = route["segments"]
    every = route["distance_m"] / len(segs)
    line = (f"  {route['name'][:34]:36} {route['distance_m']/1000:5.2f} km  "
            f"{len(segs):3} segments  climb {route['climb_m']:3.0f} m  "
            f"a change every {every:4.0f} m ({every/(pace_kph/3.6):3.0f} s at {pace_kph} km/h)")
    if notes["flattened"] >= 1:
        line += (f"\n  {'':36} note: {notes['flattened']:.0f} m of descent flattened "
                 f"by the deck's {deck_limits()[0]:.0f}% floor")
    return line


def build(entries):
    routes, blob = [], hashlib.sha256()
    for name, load in entries:
        try:
            data = load()
        except Exception as e:
            print(f"  {name:36} UNREADABLE - {e}")
            continue
        blob.update(data)
        try:
            route, notes = convert(name, data)
        except Exception as e:
            print(f"  {name:36} SKIPPED - {e}")
            continue
        routes.append(route)
        print(describe(route, notes))
    return routes, blob.hexdigest()


def sync(publish=True):
    source, entries = discover()
    print(f"reading {source}")
    if not entries:
        print("no .gpx files listed there.")
        print("Nothing published: an empty list is more likely a wrong path "
              "than an instruction to delete every route.")
        return None
    routes, digest = build(entries)
    if not routes:
        print("nothing converted; leaving the retained payload alone.")
        return None
    payload = json.dumps(routes)
    if publish:
        mqtt(TOPIC, payload, retain=True)
        print(f"\npublished {len(routes)} route(s) to {TOPIC} (retained).")
        print("The console replaces its list from this and writes it to disk.")
    return digest


def watch():
    print(f"watching {local_base()} every {POLL_SEC:.0f}s. Ctrl-C to stop.")
    last = None
    while True:
        try:
            source, entries = discover()
            _, digest = build(entries) if entries else ([], "empty")
            if digest != last:
                if last is not None:
                    print(f"[{time.strftime('%H:%M:%S')}] changed")
                sync()
                last = digest
        except SystemExit as e:
            print(e)
        except Exception as e:
            print(f"[{time.strftime('%H:%M:%S')}] {type(e).__name__}: {e}")
        time.sleep(POLL_SEC)


def main():
    args = sys.argv[1:]
    cmd = (args[0] if args else "sync").lower()

    if cmd == "preview":
        if len(args) < 2:
            sys.exit("which file? stride_gpx.py preview walk.gpx")
        target = args[1]
        data = (http(target) if target.startswith("http")
                else open(target, "rb").read())
        route, notes = convert(target, data)
        print(describe(route, notes))
        print(f"\n  {notes['points']} trackpoints, "
              f"{notes['raw_climb']:.0f} m of raw ascent before smoothing")
        print("\n  profile:")
        for a, b, g in route["segments"]:
            print(f"    {a:6.0f}-{b:6.0f} m  {g:+5.1f}%  "
                  f"{'-' if g < 0 else ' '}{'#' * max(1, int(abs(g) * 3))}")
        print("\nnothing published. `sync` to publish the library.")
        return

    if cmd == "list":
        source, entries = discover()
        print(f"reading {source}\n")
        for name, _ in entries:
            print(f"  {name}  ->  {route_name(name)}")
        print(f"\n{len(entries)} file(s). `sync` to convert and publish.")
        return

    if cmd == "sync":
        sync()
        return

    if cmd == "watch":
        watch()
        return

    sys.exit(__doc__.strip())


if __name__ == "__main__":
    main()
