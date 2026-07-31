#!/usr/bin/env python3
"""
One-shot: un-double STRIDE's entity ids, keeping their history.

Home Assistant builds an entity_id from the *device* name plus the *entity*
name, so a sensor named after its own device gets the word twice. STRIDE did
this in two places:

    sensor.stride_sam_sam_treadmill_distance     device "STRIDE — Sam",
                                                 sensor "Sam Treadmill Distance"
    sensor.treadmill_treadmill_speed             device "Treadmill",
                                                 sensor "Treadmill Speed"

The second was found months after the first, sitting next to it.

The console no longer does that — the sensors are called "Treadmill Distance"
and the device already says whose they are. But an entity_id is assigned once,
at first discovery, and then lives in the entity registry: changing the
discovery payload renames the *friendly name* and leaves the id exactly as it
was. Only a registry rename moves it.

**A registry rename takes the history with it.** Home Assistant migrates both
recorder history and long-term statistics when an entity_id changes, which is
why this is the safe way round and hand-editing the database is not.

**What it does NOT update, and you must:** anything that refers to these
entities *by id* rather than by registry link — `utility_meter` sources,
`history_stats` entity_ids, template sensors, automations, dashboard cards.
Those keep pointing at the old name and go unavailable. On the install this was
written against that meant four utility_meters and two history_stats sensors,
which is a five-minute fix and a nasty surprise if nobody says so first.

If you have an existing STRIDE install with rollups built on the old ids, the
honest answer is that renaming buys you tidier entity ids and costs you an
afternoon of small edits. A fresh install gets the clean names for free and
needs none of this.

Run:  python3 ha/stride_fix_person_entities.py            (dry run, lists them)
      python3 ha/stride_fix_person_entities.py --apply    (renames them)
"""
import json
import os
import re
import sys

from stride_config import HA, WS, conf, mqtt, token

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)


# Both STRIDE device families. The match is structural — a leading run of
# name-parts repeated immediately — so it catches any device named after itself,
# including ones added after this was written.
PREFIXES = ("sensor.stride_", "sensor.treadmill_")


class Socket:
    """The entity registry is websocket-only; there is no REST equivalent."""

    def __init__(self, tok):
        import websocket
        self.ws = websocket.create_connection(WS, timeout=30)
        self.ws.recv()
        self.ws.send(json.dumps({"type": "auth", "access_token": tok}))
        if json.loads(self.ws.recv())["type"] != "auth_ok":
            sys.exit("home assistant rejected the token")
        self.n = 0

    def call(self, msg):
        self.n += 1
        msg["id"] = self.n
        self.ws.send(json.dumps(msg))
        while True:
            r = json.loads(self.ws.recv())
            if r.get("id") == self.n:
                if not r.get("success", True):
                    return {"__error__": r.get("error")}
                return r.get("result")


def doubled(entity_id: str) -> str or None:
    """
    `sensor.treadmill_treadmill_speed` -> `sensor.treadmill_speed`.

    Finds an adjacent repeated run of name-parts *anywhere* in the id, not just
    at the front. The first version only looked at the front and reported
    "nothing to rename" against a database full of doubled ids: the repeat in
    `stride_sam_sam_treadmill_distance` starts at the second part, not the
    first.

    Longest run first, so `a_b_a_b_c` collapses to `a_b_c` rather than being
    half-fixed. Restricted to STRIDE's own prefixes, because "two adjacent
    identical words" is a shape that occurs innocently elsewhere.
    """
    if not any(entity_id.startswith(p) for p in PREFIXES):
        return None
    domain, _, rest = entity_id.partition(".")
    parts = rest.split("_")

    for n in range(len(parts) // 2, 0, -1):
        for i in range(len(parts) - 2 * n + 1):
            if parts[i:i + n] == parts[i + n:i + 2 * n]:
                return domain + "." + "_".join(parts[:i + n] + parts[i + 2 * n:])
    return None


def main():
    apply = "--apply" in sys.argv
    s = Socket(token())
    entities = s.call({"type": "config/entity_registry/list"})

    moves = []
    for e in entities:
        new = doubled(e["entity_id"])
        if new and new != e["entity_id"]:
            moves.append((e["entity_id"], new))

    if not moves:
        print("  nothing to rename — no doubled ids found")
        return 0

    existing = {e["entity_id"] for e in entities}
    print(f"  {len(moves)} entities to rename:\n")
    for old, new in moves:
        clash = "  ** TARGET EXISTS, SKIPPING **" if new in existing else ""
        print(f"    {old}\n      -> {new}{clash}")

    if not apply:
        print("\n  dry run — nothing changed. Re-run with --apply")
        return 0

    print()
    done = 0
    for old, new in moves:
        if new in existing:
            print(f"  skipped {old} (target exists)")
            continue
        r = s.call({"type": "config/entity_registry/update",
                    "entity_id": old, "new_entity_id": new})
        if isinstance(r, dict) and "__error__" in r:
            print(f"  FAILED {old}: {r['__error__']}")
        else:
            print(f"  renamed {old}\n       -> {new}")
            done += 1

    print(f"\n  {done} renamed. History and long-term statistics move with them.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
