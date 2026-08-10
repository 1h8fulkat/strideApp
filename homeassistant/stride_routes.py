#!/usr/bin/env python3
"""
Inspect and curate the routes the console has.

    python3 stride_routes.py                 what the broker is retaining
    python3 stride_routes.py console         what the treadmill actually has
    python3 stride_routes.py drop "Friday"   remove routes matching a name
    python3 stride_routes.py keep "Hospital" remove everything else
    python3 stride_routes.py clear           remove all of them

`console` exists because the two can disagree, and when they do the treadmill
is what matters. The console caches to disk and only replaces that cache when a
payload arrives, so one that was asleep, offline or mid-reboot keeps yesterday's
list while the broker holds today's — and nothing in Home Assistant can see the
difference.

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


def adb_target(serial: str = "") -> str:
    """Which console to talk to.

    In order: what you named on the command line, what `console_adb:` names in
    stride.conf, or whatever adb has attached — which is right far more often
    than a fixed address, and is the difference between this working on one
    bench and working on anyone's. It used to be a hardcoded IP, which was one
    house's LAN baked into a published tool.
    """
    if serial:
        return serial
    named = conf("console_adb", "")
    if named:
        return named
    try:
        out = subprocess.run(["adb", "devices"],
                             capture_output=True, text=True, timeout=10).stdout
    except FileNotFoundError:
        sys.exit("adb not found. brew install android-platform-tools.")
    except subprocess.TimeoutExpired:
        sys.exit("adb did not answer.")
    found = [line.split()[0] for line in out.splitlines()[1:]
             if line.strip().endswith("device")]
    if len(found) == 1:
        return found[0]
    if not found:
        sys.exit("No console attached. Plug in over USB, or set console_adb: "
                 "in stride.conf, or name one:\n"
                 "    stride_routes.py console <ip>:5555")
    sys.exit("More than one device attached. Name the one you mean:\n" +
             "\n".join(f"    stride_routes.py console {d}" for d in found))


def console(serial: str = "") -> list:
    """What the treadmill has on disk, read over ADB.

    The console's copy is the one that decides what you can actually walk, and
    it is not derivable from the broker: it updates only when a payload
    arrives, so a console that was asleep or offline keeps an older list
    indefinitely and nothing upstream can tell.
    """
    serial = adb_target(serial)
    pkg = "dev.stride.hud"
    try:
        out = subprocess.run(
            ["adb", "-s", serial, "shell",
             f"run-as {pkg} cat files/routes.json"],
            capture_output=True, text=True, timeout=25).stdout.strip()
    except FileNotFoundError:
        sys.exit("adb not found. brew install android-platform-tools.")
    except subprocess.TimeoutExpired:
        sys.exit(f"no answer from {serial}. The console re-locks ADB after a "
                 "power cycle — see the notes on iFit eru.")
    if not out:
        sys.exit(f"could not read {pkg}'s cache on {serial}. Is the console "
                 "awake, and is ADB still authorised?")
    try:
        return json.loads(out)
    except json.JSONDecodeError as e:
        sys.exit(f"the console's cache is not JSON ({e}).")


def show(routes: list, where: str = None):
    where = where or f"retained on {TOPIC}"
    if not routes:
        print(f"no routes {where}.")
        return
    print(f"{len(routes)} route(s) {where}:\n")
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

    if cmd == "console":
        on_device = console(*args[1:2])
        show(on_device, "cached on the treadmill")
        ids = {str(r.get("id")): r.get("name") for r in routes}
        theirs = {str(r.get("id")): r.get("name") for r in on_device}
        if ids == theirs:
            print("\nbroker and console agree.")
        else:
            print("\nthey DISAGREE — the console is what you can actually walk:")
            for i in set(ids) | set(theirs):
                if ids.get(i) != theirs.get(i):
                    print(f"   {i[:8]}  broker={ids.get(i, '—')!r}  "
                          f"console={theirs.get(i, '—')!r}")
        return

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
