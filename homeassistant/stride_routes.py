#!/usr/bin/env python3
"""
Inspect and curate the routes the console has.

    python3 stride_routes.py                 list what the console holds
    python3 stride_routes.py drop "Friday"   remove routes matching a name
    python3 stride_routes.py keep "Hospital" remove everything else
    python3 stride_routes.py clear           remove all of them

A route reaches the treadmill by a path with three hops and one surprise:

    phone  --POST-->  webhook automation  --retained MQTT-->  console

The surprise is the retain flag. `stride/routes` is a *retained* topic, so the
broker keeps the last payload forever and hands it to the console the moment it
connects — which is the whole reason the console can boot with no network and
still offer you a walk. It also means the routes outlive Home Assistant
restarts, broker restarts and reflashes of the console, and that nothing on the
phone can tell you what is actually up there.

Hence this. It reads the retained payload and can rewrite it.

**The phone is the source of truth, and this tool is not.** Removing a route
here does not remove it from the phone; the next time the phone decides its
route set has changed, it will publish its own list and undo whatever was done
here. That is the correct outcome — but it means "drop" is for clearing out
routes the phone no longer has, not for disagreeing with the phone. To remove a
route properly, delete it in the app and press Send again on the Routes screen.
"""
import json
import subprocess
import sys

from stride_config import broker, conf, mqtt

TOPIC = "stride/routes"


def read() -> list:
    """The retained payload, or [] if the topic is empty.

    `-W 5` is a timeout rather than a wait: a retained message arrives
    immediately, so anything slower than that means there is nothing there and
    the subscription would otherwise hang until the heat death of the house.
    """
    try:
        out = subprocess.run(
            ["mosquitto_sub", "-h", broker(),
             "-u", conf("mqtt_user"), "-P", conf("mqtt_pass"),
             "-t", TOPIC, "-C", "1", "-W", "5"],
            capture_output=True, text=True, timeout=20).stdout.strip()
    except FileNotFoundError:
        sys.exit("mosquitto_sub not found. brew install mosquitto, or apt install "
                 "mosquitto-clients.")
    except subprocess.TimeoutExpired:
        return []
    if not out:
        return []
    try:
        return json.loads(out)
    except json.JSONDecodeError as e:
        sys.exit(f"the retained payload is not JSON ({e}). Left alone.")


def show(routes: list):
    if not routes:
        print(f"{TOPIC} is empty — the console has no routes.")
        return
    print(f"{len(routes)} route(s) retained on {TOPIC}:\n")
    for r in routes:
        segs = r.get("segments") or []
        km = (r.get("distance_m") or 0) / 1000
        print(f"  {str(r.get('name'))[:38]:40} {km:5.2f} km  "
              f"{len(segs):3} segments  climb {r.get('climb_m') or 0:.0f} m  "
              f"id {str(r.get('id'))[:8]}")


def write(routes: list, was: int):
    """Replace the retained payload.

    Publishing the full array is the only way to remove anything: the console
    replaces its whole list from whatever arrives, so there is no notion of
    deleting one route on the wire.
    """
    mqtt(TOPIC, json.dumps(routes), retain=True)
    print(f"\npublished {len(routes)} route(s) to {TOPIC} (was {was}).")
    print("The console replaces its cached list from this and writes it to disk.")
    if not routes:
        print("Note: an empty payload is a real instruction, not a clear —\n"
              "      the topic stays retained and the console now holds none.")


def main():
    args = sys.argv[1:]
    routes = read()

    if not args:
        show(routes)
        return

    cmd = args[0].lower()
    term = " ".join(args[1:]).strip().lower()

    if cmd == "clear":
        show(routes)
        write([], len(routes))
        return

    if cmd not in ("drop", "keep") or not term:
        sys.exit(__doc__.strip())

    def matches(r):
        return term in str(r.get("name", "")).lower() or \
               str(r.get("id", "")).lower().startswith(term)

    kept = [r for r in routes if (not matches(r) if cmd == "drop" else matches(r))]
    gone = [r for r in routes if r not in kept]

    if not gone:
        print(f"nothing matched {term!r}.")
        show(routes)
        return
    for r in gone:
        print(f"removing  {r.get('name')}")
    write(kept, len(routes))


if __name__ == "__main__":
    main()
