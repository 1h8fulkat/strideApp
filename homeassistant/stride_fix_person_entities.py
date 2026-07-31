#!/usr/bin/env python3
"""
One-shot: un-double the per-person treadmill entity ids, keeping their history.

Home Assistant builds an entity_id from the *device* name plus the *entity*
name. The console published a device called "STRIDE — Sam" whose sensors were
called "Sam Treadmill Distance", so the name landed twice:

    sensor.stride_sam_sam_treadmill_distance
    sensor.stride_alex_alex_treadmill_distance

The console no longer does that — the sensors are called "Treadmill Distance"
and the device already says whose they are. But an entity_id is assigned once,
at first discovery, and then lives in the entity registry: changing the
discovery payload renames the *friendly name* and leaves the id exactly as it
was. Only a registry rename moves it.

**A registry rename takes the history with it.** Home Assistant migrates both
recorder history and long-term statistics when an entity_id changes, which is
why this is the safe way round and hand-editing the database is not.

Run:  python3 ha/stride_fix_person_entities.py            (dry run, lists them)
      python3 ha/stride_fix_person_entities.py --apply    (renames them)
"""
import json
import os
import re
import sys

from stride_config import HA, conf, mqtt, token

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)


PREFIX = "sensor.stride_"


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
    """`sensor.stride_sam_sam_treadmill_time` -> the un-doubled id.

    Matched structurally rather than against a list of names: the point is to
    catch `<person>_<person>` wherever it occurs, including for anyone added
    after this was written.
    """
    if not entity_id.startswith(PREFIX):
        return None
    rest = entity_id[len(PREFIX):]
    # Find the longest leading run that repeats immediately.
    parts = rest.split("_")
    for n in range(len(parts) // 2, 0, -1):
        if parts[:n] == parts[n:2 * n]:
            return PREFIX + "_".join(parts[n:])
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
